import { useCallback } from 'react';
import { type ActionResult, type ActionState, useAction } from '@/hooks/use-action';
import { getConsultation, getConsultationAppointment } from '@/hooks/use-doctor-consultations';
import { useInvoices, useUninvoicedConsultations } from '@/hooks/use-invoices';
import type { PagedState } from '@/hooks/use-paged';
import { type ResourceState, useResource } from '@/hooks/use-resource';
import { apiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  cancelInvoice, createInvoice, getFees, listDoctorInvoices, sendInvoice, updateInvoice,
} from '@/lib/billing/api';
import type {
  AppointmentType, CancelInvoiceRequest, CreateInvoiceRequest, CreateInvoiceResult, Invoice, InvoiceStatus,
  UninvoicedConsultation, UpdateInvoiceRequest,
} from '@/lib/billing/types';

// The doctor's side of billing (api/be-billing-invoices.md): the invoices they issued, the completed
// consultations that still need one, and the calls that create, edit, send and cancel an invoice.
// Recording a payment is an administrator action and has no hook here.

// ─── Rules of the invoice state machine the screen needs ─────────────────────

/**
 * Every status except CANCELLED. An invoice in one of these blocks a new invoice for the same visit (the server
 * answers the existing one), so it is "the" invoice of a consultation.
 */
export const LIVE_INVOICE_STATUSES: readonly InvoiceStatus[] = ['DRAFT', 'SENT', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'REFUNDED'];

/** Only a draft can be edited (6.7). */
export function canEditInvoice(invoice: Pick<Invoice, 'status'>): boolean {
  return invoice.status === 'DRAFT';
}

/** A draft can be sent once it charges something: a zero-total invoice is refused with 409 (6.8). */
export function canSendInvoice(invoice: Pick<Invoice, 'status' | 'total'>): boolean {
  return invoice.status === 'DRAFT' && invoice.total > 0;
}

/** DRAFT, SENT or OVERDUE without any payment can be cancelled (6.9). */
export function canCancelInvoice(invoice: Pick<Invoice, 'status' | 'amountPaid'>): boolean {
  return (invoice.status === 'DRAFT' || invoice.status === 'SENT' || invoice.status === 'OVERDUE')
    && invoice.amountPaid <= 0;
}

/** True when `doctorSystemId` issued the invoice; only the issuer may change it. */
export function isIssuedBy(invoice: Pick<Invoice, 'doctorSystemId'>, doctorSystemId: string | null | undefined): boolean {
  return !!doctorSystemId && invoice.doctorSystemId === doctorSystemId;
}

// ─── Lists ────────────────────────────────────────────────────────────────────

export interface DoctorInvoicesOptions {
  /** Only this patient's invoices (a plain filter on the doctor's own list). */
  patientSystemId?: string | null;
  status?:          InvoiceStatus | readonly InvoiceStatus[] | null;
  consultationId?:  number | null;
  pageSize?:        number;
  enabled?:         boolean;
}

/** The invoices the signed-in doctor issued, drafts included, newest first (`GET /api/doctors/billing/invoices`). */
export function useDoctorInvoices(options: DoctorInvoicesOptions = {}): PagedState<Invoice> {
  return useInvoices({ ...options, scope: 'doctor' });
}

/** The doctor's completed consultations without a live invoice (`GET /api/doctors/billing/uninvoiced`). */
export function useDoctorUninvoiced(options: { pageSize?: number; enabled?: boolean } = {}): PagedState<UninvoicedConsultation> {
  return useUninvoicedConsultations(options);
}

// ─── The visit an invoice is created for ──────────────────────────────────────

/** What the "Create invoice" form needs to know about the consultation it bills. */
export interface InvoiceVisit {
  consultationId:  number;
  appointmentId:   number | null;
  /** Null when the appointment type could not be read: the form then starts with a blank price. */
  appointmentType: AppointmentType | null;
  patientSystemId: string;
  patientName:     string | null;
  completedAt:     string | null;
  scheduledAt:     string | null;
  /** The fee for this doctor and appointment type (doctor override, else clinic default, else 0.00). */
  suggestedFee:    number | null;
  currency:        string | null;
}

/** A row of the uninvoiced list as a visit to bill. */
export function visitFromUninvoiced(row: UninvoicedConsultation): InvoiceVisit {
  return {
    consultationId:  row.consultationId,
    appointmentId:   row.appointmentId,
    appointmentType: row.appointmentType,
    patientSystemId: row.patientSystemId,
    patientName:     row.patientName,
    completedAt:     row.completedAt,
    scheduledAt:     row.scheduledAt,
    suggestedFee:    row.suggestedFee,
    currency:        row.currency,
  };
}

/**
 * The live invoice of a consultation, or null when it has none (`GET /api/doctors/billing/invoices?consultationId=`).
 * Cancelled invoices are skipped: a cancelled visit can be invoiced again.
 */
export async function findConsultationInvoice(consultationId: number): Promise<Invoice | null> {
  const page = await listDoctorInvoices({ consultationId, status: LIVE_INVOICE_STATUSES }, 0, 1);
  return page.content[0] ?? null;
}

/** What opening billing with `consultationId` leads to. */
export type ConsultationInvoiceLookup =
  /** The consultation already has an invoice: open it. */
  | { kind: 'invoice'; invoice: Invoice }
  /** No invoice yet and the consultation can be billed: start "Create invoice" for it. */
  | { kind: 'create'; visit: InvoiceVisit }
  /** The consultation cannot be invoiced by this doctor; `message` says why. */
  | { kind: 'unavailable'; message: string };

async function resolveConsultationInvoice(consultationId: number, doctorSystemId: string): Promise<ConsultationInvoiceLookup> {
  const existing = await findConsultationInvoice(consultationId);
  if (existing) return { kind: 'invoice', invoice: existing };

  let consultation;
  try {
    consultation = await getConsultation(consultationId);
  } catch (e) {
    const failure = apiError(e);
    if (failure.status === 404) return { kind: 'unavailable', message: 'This consultation was not found, so it cannot be invoiced.' };
    if (failure.status === 403) return { kind: 'unavailable', message: 'You can only invoice your own consultations.' };
    throw e;
  }
  if (consultation.doctorSystemId && consultation.doctorSystemId !== doctorSystemId) {
    return { kind: 'unavailable', message: 'You can only invoice your own consultations.' };
  }
  if (consultation.status !== 'COMPLETED') {
    return {
      kind: 'unavailable',
      message: consultation.status === 'CANCELLED'
        ? 'This consultation was cancelled, so it cannot be invoiced.'
        : 'Only a completed consultation can be invoiced. Complete the consultation first.',
    };
  }

  // The fee depends on the appointment type; the consultation usually carries it, the appointment always does.
  let appointmentType: AppointmentType | null = consultation.appointmentType;
  let scheduledAt: string | null = null;
  if (consultation.appointmentId > 0) {
    const appointment = await getConsultationAppointment(consultation.appointmentId).catch(() => null);
    appointmentType = appointmentType ?? appointment?.appointmentType ?? null;
    scheduledAt = appointment?.scheduledAt || null;
  }
  const fees = await getFees(doctorSystemId);
  const fee = appointmentType ? fees.find(f => f.appointmentType === appointmentType) ?? null : null;

  return {
    kind: 'create',
    visit: {
      consultationId:  consultation.consultationId,
      appointmentId:   consultation.appointmentId > 0 ? consultation.appointmentId : null,
      appointmentType,
      patientSystemId: consultation.patientSystemId,
      patientName:     consultation.patientName,
      completedAt:     consultation.completedAt,
      scheduledAt,
      suggestedFee:    fee ? fee.amount : null,
      currency:        fee?.currency ?? fees[0]?.currency ?? null,
    },
  };
}

/**
 * Billing opened with `consultationId` (from the consultation editor's Invoice button): finds the consultation's
 * invoice through the consultationId filter, or prepares "Create invoice" for it priced from the fee schedule.
 * Nothing is requested until both ids are known.
 */
export function useConsultationInvoiceLookup(
  consultationId: number | null,
  doctorSystemId: string | null | undefined,
): ResourceState<ConsultationInvoiceLookup> {
  const doctorId = doctorSystemId?.trim() || null;
  return useResource<ConsultationInvoiceLookup>(
    () => resolveConsultationInvoice(consultationId as number, doctorId as string),
    [consultationId, doctorId],
    {
      enabled:      consultationId !== null && doctorId !== null,
      errorMessage: 'We could not look up the invoice for this consultation. Please try again.',
    },
  );
}

// ─── Writes ───────────────────────────────────────────────────────────────────

export interface CreateDoctorInvoice extends ActionState<[CreateInvoiceRequest], CreateInvoiceResult> {
  /** Starts a new Idempotency-Key. Call it each time the "Create invoice" form opens. */
  renewKey: () => void;
}

/**
 * `POST /api/billing/invoices` with one Idempotency-Key per form open: the key is repeated when a submit whose
 * outcome is unknown (no answer, a server error, a request still in flight) is retried, and replaced after a
 * success or a refusal that stored nothing (any other 4xx), since the next submit then carries corrected input.
 * `result.data.created` is false when an invoice already existed for the visit: the body was not applied.
 */
export function useCreateDoctorInvoice(): CreateDoctorInvoice {
  const key = useIdempotencyKey();
  const action = useAction(
    (request: CreateInvoiceRequest) => createInvoice(request, key.current()),
    { errorMessage: 'We could not create the invoice. Please try again.' },
  );
  const { run: runAction } = action;
  const { renew } = key;
  const run = useCallback(async (request: CreateInvoiceRequest): Promise<ActionResult<CreateInvoiceResult>> => {
    const result = await runAction(request);
    if (result.ok) {
      renew();
    } else if (result.error) {
      const { status, code } = result.error;
      if (status >= 400 && status < 500 && code !== 'IDEMPOTENCY_IN_PROGRESS') renew();
    }
    return result;
  }, [runAction, renew]);
  return { ...action, run, renewKey: renew };
}

/** `PUT /api/billing/invoices/{id}`: replaces the lines, discount and note of a draft. */
export function useUpdateDoctorInvoice(): ActionState<[number, UpdateInvoiceRequest], Invoice> {
  return useAction(
    (invoiceId: number, request: UpdateInvoiceRequest) => updateInvoice(invoiceId, request),
    { errorMessage: 'We could not save the invoice. Please try again.' },
  );
}

/** `POST /api/billing/invoices/{id}/send`: issues a draft to the patient. Repeating it on a sent invoice is a no-op. */
export function useSendDoctorInvoice(): ActionState<[number], Invoice> {
  return useAction(
    (invoiceId: number) => sendInvoice(invoiceId),
    { errorMessage: 'We could not send the invoice. Please try again.' },
  );
}

/** `POST /api/billing/invoices/{id}/cancel` with an optional reason (at most 500 characters). */
export function useCancelDoctorInvoice(): ActionState<[number, CancelInvoiceRequest], Invoice> {
  return useAction(
    (invoiceId: number, request: CancelInvoiceRequest) => cancelInvoice(invoiceId, request),
    { errorMessage: 'We could not cancel the invoice. Please try again.' },
  );
}
