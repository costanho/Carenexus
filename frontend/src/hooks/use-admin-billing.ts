import { useEffect, useRef, useState } from 'react';
import { type ActionState, useAction } from '@/hooks/use-action';
import { type PagedState, usePaged } from '@/hooks/use-paged';
import { type ResourceState, useResource } from '@/hooks/use-resource';
import {
  adjudicateClaim, countOpenClaims, downloadClaimDocument, getClaim, getDefaultFees, getInvoice, listAdminClaims,
  listAdminInvoices, listAdminPayments, listInvoicePayments, recordManualPayment, refundPayment, updateDefaultFees,
  voidInvoice,
} from '@/lib/admin/billing-api';
import {
  canRecordPayment, PAYABLE_INVOICE_STATUSES,
  type AdjudicateClaimRequest, type AdminClaimFilters, type AdminInvoiceFilters, type AdminPayment,
  type AdminPaymentFilters, type ClaimDocumentFile, type DefaultFeeInput, type Fee, type InsuranceClaim,
  type Invoice, type ManualPaymentRequest, type RefundPaymentRequest,
} from '@/lib/admin/billing-types';
import { apiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';

// Hooks of the administrator billing back office (lists, single reads and money actions).
//
// Lists and reads take an optional `reload` number: when the screen changes it (after a payment was recorded,
// a refund made, a claim decided), the hook loads again and keeps what is on screen until the answer arrives.

/** Calls `refetch` each time `signal` changes after the first render. */
function useReloadSignal(signal: number | undefined, refetch: () => void): void {
  const seen = useRef(signal);
  useEffect(() => {
    if (seen.current === signal) return;
    seen.current = signal;
    refetch();
  }, [signal, refetch]);
}

// ─── Invoices ─────────────────────────────────────────────────────────────────

/** Every invoice, newest first, filtered by status (one or a comma-separated list) and patient. */
export function useAdminInvoices(filters: AdminInvoiceFilters, reload?: number): PagedState<Invoice> {
  const state = usePaged<Invoice>(
    (page, size) => listAdminInvoices(filters, page, size),
    [filters.status, filters.patientSystemId],
    { keyOf: invoice => invoice.invoiceId, errorMessage: 'The invoices could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

/** One invoice by id; nothing is requested while `invoiceId` is null. */
export function useAdminInvoice(invoiceId: number | null, reload?: number): ResourceState<Invoice> {
  const state = useResource<Invoice>(
    () => getInvoice(invoiceId as number),
    [invoiceId],
    { enabled: invoiceId !== null, errorMessage: 'The invoice could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

/** The payment history of one invoice, newest first, failed attempts included. */
export function useInvoicePayments(invoiceId: number | null, reload?: number): PagedState<AdminPayment> {
  const state = usePaged<AdminPayment>(
    (page, size) => listInvoicePayments(invoiceId as number, page, size),
    [invoiceId],
    { enabled: invoiceId !== null, keyOf: payment => payment.paymentId, errorMessage: 'The payments of this invoice could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

/** The insurance claims made for one invoice, newest first. */
export function useInvoiceClaims(invoiceId: number | null, reload?: number): PagedState<InsuranceClaim> {
  const state = usePaged<InsuranceClaim>(
    (page, size) => listAdminClaims({ invoiceId: invoiceId as number }, page, size),
    [invoiceId],
    { enabled: invoiceId !== null, keyOf: claim => claim.claimId, errorMessage: 'The claims of this invoice could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

export interface PayableInvoicesState {
  /** The patient's invoices that can take a payment now (sent, partly paid or overdue, with a balance). */
  invoices:  Invoice[];
  /** True when the patient has more payable invoices than one request returns (100). */
  truncated: boolean;
  loading:   boolean;
  error:     string | null;
  refetch:   () => void;
}

/** The invoices of one patient a manual payment can be recorded against. Idle while `patientSystemId` is empty. */
export function usePayableInvoices(patientSystemId: string | null): PayableInvoicesState {
  const state = useResource(
    () => listAdminInvoices({ status: PAYABLE_INVOICE_STATUSES.join(','), patientSystemId: patientSystemId as string }, 0, 100),
    [patientSystemId],
    { enabled: !!patientSystemId, errorMessage: 'The invoices of this patient could not be loaded.' },
  );
  const rows = state.data?.content ?? [];
  return {
    invoices:  rows.filter(canRecordPayment),
    truncated: (state.data?.totalElements ?? 0) > rows.length,
    loading:   state.loading,
    error:     state.error,
    refetch:   state.refetch,
  };
}

/** Voids an invoice that has received no payment: `run(invoiceId, reason)`. */
export function useVoidInvoice(): ActionState<[invoiceId: number, reason: string], Invoice> {
  return useAction(
    (invoiceId: number, reason: string) => voidInvoice(invoiceId, { reason }),
    { errorMessage: 'The invoice could not be voided. Please try again.' },
  );
}

// ─── Payments ─────────────────────────────────────────────────────────────────

/** Every payment, newest first, filtered by status, method and patient. */
export function useAdminPayments(filters: AdminPaymentFilters, reload?: number): PagedState<AdminPayment> {
  const state = usePaged<AdminPayment>(
    (page, size) => listAdminPayments(filters, page, size),
    [filters.status, filters.method, filters.patientSystemId],
    { keyOf: payment => payment.paymentId, errorMessage: 'The payments could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

export type KeyedAction<A extends unknown[], R> = ActionState<A, R> & {
  /** Starts a new Idempotency-Key: call it when the dialog switches to another invoice or payment. */
  renewKey: () => void;
};

/**
 * True when the server answered and refused the request (4xx), so nothing was stored under its Idempotency-Key
 * and the next submit (usually with corrected input) is a new request. False while the outcome is unknown: no
 * response, a server error, or the same key still being processed. Those retries must repeat the key.
 */
function wasRefused(e: unknown): boolean {
  const failure = apiError(e);
  return failure.status >= 400 && failure.status < 500 && failure.code !== 'IDEMPOTENCY_IN_PROGRESS';
}

/** Runs a keyed POST and moves to a new key once its outcome is known (success or a refusal). */
async function withKey<R>(key: { current: () => string; renew: () => void }, send: (idempotencyKey: string) => Promise<R>): Promise<R> {
  try {
    const result = await send(key.current());
    key.renew();
    return result;
  } catch (e) {
    if (wasRefused(e)) key.renew();
    throw e;
  }
}

/**
 * Records a cash or bank-transfer payment: `run(invoiceId, body)`. Mount it with the dialog: the hook holds one
 * Idempotency-Key per mount and repeats it when a submit whose outcome is unknown (no response, server error)
 * is retried, so the money is never recorded twice. The key is replaced after a success or a refusal.
 */
export function useRecordManualPayment(): KeyedAction<[invoiceId: number, body: ManualPaymentRequest], AdminPayment> {
  const key = useIdempotencyKey();
  const action = useAction(
    (invoiceId: number, body: ManualPaymentRequest) => withKey(key, k => recordManualPayment(invoiceId, body, k)),
    { errorMessage: 'The payment could not be recorded. Please try again.' },
  );
  return { ...action, renewKey: key.renew };
}

/** Refunds a completed payment: `run(paymentId, body)`. One Idempotency-Key per mount, as above. */
export function useRefundPayment(): KeyedAction<[paymentId: number, body: RefundPaymentRequest], AdminPayment> {
  const key = useIdempotencyKey();
  const action = useAction(
    (paymentId: number, body: RefundPaymentRequest) => withKey(key, k => refundPayment(paymentId, body, k)),
    { errorMessage: 'The refund could not be made. Please try again.' },
  );
  return { ...action, renewKey: key.renew };
}

// ─── Insurance claims ─────────────────────────────────────────────────────────

/** Every claim, newest first, filtered by status (one or a comma-separated list) and patient. */
export function useAdminClaims(filters: AdminClaimFilters, reload?: number): PagedState<InsuranceClaim> {
  const state = usePaged<InsuranceClaim>(
    (page, size) => listAdminClaims(filters, page, size),
    [filters.status, filters.patientSystemId, filters.invoiceId],
    { keyOf: claim => claim.claimId, errorMessage: 'The claims could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

/** One claim by id; nothing is requested while `claimId` is null. */
export function useAdminClaim(claimId: number | null, reload?: number): ResourceState<InsuranceClaim> {
  const state = useResource<InsuranceClaim>(
    () => getClaim(claimId as number),
    [claimId],
    { enabled: claimId !== null, errorMessage: 'The claim could not be loaded.' },
  );
  useReloadSignal(reload, state.refetch);
  return state;
}

export interface OpenClaimsCount {
  /** Claims waiting for an administrator (submitted, under review or appealed); 0 until loaded. */
  count:   number;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
}

/** The number of open claims, for the Claims tab badge and the administrator home. */
export function useOpenClaimsCount(reload?: number): OpenClaimsCount {
  const state = useResource<number>(countOpenClaims, [], { errorMessage: 'The number of open claims could not be loaded.' });
  useReloadSignal(reload, state.refetch);
  return { count: state.data ?? 0, loading: state.loading, error: state.error, refetch: state.refetch };
}

/** One adjudication step on a claim: `run(claimId, body)`. A repeat for the same status is a server no-op. */
export function useAdjudicateClaim(): ActionState<[claimId: number, body: AdjudicateClaimRequest], InsuranceClaim> {
  return useAction(
    (claimId: number, body: AdjudicateClaimRequest) => adjudicateClaim(claimId, body),
    { errorMessage: 'The claim could not be updated. Please try again.' },
  );
}

export interface ClaimDocumentDownload {
  /** Fetches the file and hands it to the browser to save. Never rejects; a failure is in `error`. */
  download:      (documentId: number) => void;
  /** The document being fetched, or null. */
  downloadingId: number | null;
  error:         string | null;
  reset:         () => void;
}

/** Downloads the documents attached to a claim, one at a time. */
export function useClaimDocumentDownload(): ClaimDocumentDownload {
  const [activeId, setActiveId] = useState<number | null>(null);
  const action = useAction<[number], ClaimDocumentFile>(
    downloadClaimDocument,
    { errorMessage: 'The document could not be downloaded. Please try again.' },
  );
  const { run, pending } = action;
  const download = (documentId: number) => {
    if (pending) return;
    setActiveId(documentId);
    void run(documentId);
  };
  return { download, downloadingId: pending ? activeId : null, error: action.error, reset: action.reset };
}

// ─── Default fees ─────────────────────────────────────────────────────────────

/** The clinic's default fee per appointment type (three rows). */
export function useDefaultFees(): ResourceState<Fee[]> {
  return useResource<Fee[]>(getDefaultFees, [], { errorMessage: 'The default fees could not be loaded.' });
}

/** Saves the listed default fees: `run(fees)` resolves with the three default rows. */
export function useSaveDefaultFees(): ActionState<[fees: DefaultFeeInput[]], Fee[]> {
  return useAction(
    (fees: DefaultFeeInput[]) => updateDefaultFees(fees),
    { errorMessage: 'The default fees could not be saved. Please try again.' },
  );
}
