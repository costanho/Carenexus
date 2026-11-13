import { api } from '@/lib/auth/auth.interceptor';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type {
  AppealClaimRequest, BillingConfig, CancelInvoiceRequest, ClaimListFilters, CreateInvoiceRequest, CreateInvoiceResult,
  CreatePaymentMethodRequest, DoctorInvoiceListFilters, Fee, InitiatePaymentRequest, InsuranceClaim, Invoice,
  InvoiceListFilters, Payment, PaymentMethod, SimulatedPaymentOutcome, SubmitClaimRequest, UninvoicedConsultation,
  UpdateFeesRequest, UpdateInvoiceRequest,
} from './types';

// One function per billing endpoint a patient, a caregiver or a doctor calls (api/be-billing-invoices.md,
// be-billing-payments.md, be-billing-payment-methods.md, be-billing-claims.md, be-billing-notify-receipt.md).
// The administrator endpoints (/api/admin/billing/**) have their own client in lib/admin.
//
// Every function rejects with the axios error; show it with `apiError` / `apiErrorMessage` from '@/lib/api/errors'.
// Nothing here logs a request or a response.

const INVOICES = '/api/billing/invoices';
const PAYMENTS = '/api/billing/payments';
const METHODS  = '/api/billing/payment-methods';
const CLAIMS   = '/api/billing/claims';

/** A status filter as the API takes it: one value, or several joined with commas. Empty means "all". */
function statusParam(status: string | readonly string[] | null | undefined): string | undefined {
  if (status == null) return undefined;
  const list = (typeof status === 'string' ? [status] : status).map(s => s.trim()).filter(Boolean);
  return list.length > 0 ? list.join(',') : undefined;
}

function invoiceFilterParams(filters: InvoiceListFilters | undefined) {
  return {
    status:         statusParam(filters?.status),
    consultationId: filters?.consultationId ?? undefined,
    appointmentId:  filters?.appointmentId ?? undefined,
  };
}

// ─── Fees ─────────────────────────────────────────────────────────────────────

/**
 * `GET /api/billing/fees`: the three fee rows (VIDEO, IN_PERSON, PHONE) that apply to a doctor, or the clinic
 * defaults when no doctor is given. Any signed-in user may call it.
 */
export async function getFees(doctorSystemId?: string | null): Promise<Fee[]> {
  const { data } = await api.get<Fee[]>('/api/billing/fees', {
    params: doctorSystemId ? { doctorSystemId } : undefined,
  });
  return Array.isArray(data) ? data : [];
}

/**
 * `PUT /api/doctors/billing/fees`: sets or removes the calling doctor's own fee overrides (`amount: null` removes
 * one). Types that are not listed stay as they are. Answers the doctor's resolved three rows.
 */
export async function updateDoctorFees(request: UpdateFeesRequest): Promise<Fee[]> {
  const { data } = await api.put<Fee[]>('/api/doctors/billing/fees', request);
  return Array.isArray(data) ? data : [];
}

// ─── Invoices ─────────────────────────────────────────────────────────────────

/**
 * `POST /api/billing/invoices` (doctor of the visit): creates the DRAFT invoice of a consultation or an
 * appointment. `created` is false when an invoice already existed for the visit or the key was replayed; the
 * request body was not applied then, so open the returned invoice instead of treating it as the new draft.
 */
export async function createInvoice(request: CreateInvoiceRequest, idempotencyKey: string): Promise<CreateInvoiceResult> {
  const response = await api.post<Invoice>(INVOICES, request, withIdempotencyKey(idempotencyKey));
  return { invoice: response.data, created: response.status === 201 };
}

/** `GET /api/billing/invoices/{id}`. 404 "Invoice not found" also covers an invoice the caller may not see yet. */
export async function getInvoice(invoiceId: number): Promise<Invoice> {
  const { data } = await api.get<Invoice>(`${INVOICES}/${invoiceId}`);
  return data;
}

/**
 * `GET /api/billing/invoices?patientSystemId=`: the invoices of one patient, newest first. A patient passes
 * their own id, a caregiver the loved one's (needs the billing permission), a doctor a patient of theirs (only
 * invoices that doctor issued are returned).
 */
export function listPatientInvoices(
  patientSystemId: string,
  filters?: InvoiceListFilters,
  page = 0,
  size?: number,
): Promise<PageResponse<Invoice>> {
  return fetchPage<Invoice>(INVOICES, page, size, { patientSystemId, ...invoiceFilterParams(filters) });
}

/** `GET /api/billing/invoices/me`: the calling patient's own invoices (patients only). */
export function listMyInvoices(filters?: InvoiceListFilters, page = 0, size?: number): Promise<PageResponse<Invoice>> {
  return fetchPage<Invoice>(`${INVOICES}/me`, page, size, invoiceFilterParams(filters));
}

/**
 * `GET /api/doctors/billing/invoices`: every invoice the calling doctor issued, drafts included.
 * Lookup by visit: `listDoctorInvoices({ consultationId }, 0, 1)`; an empty page means no invoice is linked to it.
 */
export function listDoctorInvoices(
  filters?: DoctorInvoiceListFilters,
  page = 0,
  size?: number,
): Promise<PageResponse<Invoice>> {
  return fetchPage<Invoice>('/api/doctors/billing/invoices', page, size, {
    patientSystemId: filters?.patientSystemId ?? undefined,
    ...invoiceFilterParams(filters),
  });
}

/** `GET /api/doctors/billing/uninvoiced`: the calling doctor's completed consultations without a live invoice. */
export function listUninvoicedConsultations(page = 0, size?: number): Promise<PageResponse<UninvoicedConsultation>> {
  return fetchPage<UninvoicedConsultation>('/api/doctors/billing/uninvoiced', page, size);
}

/** `PUT /api/billing/invoices/{id}` (issuing doctor): replaces the lines, discount and note of a DRAFT invoice. */
export async function updateInvoice(invoiceId: number, request: UpdateInvoiceRequest): Promise<Invoice> {
  const { data } = await api.put<Invoice>(`${INVOICES}/${invoiceId}`, request);
  return data;
}

/** `POST /api/billing/invoices/{id}/send` (issuing doctor): issues a DRAFT invoice to the patient. */
export async function sendInvoice(invoiceId: number): Promise<Invoice> {
  const { data } = await api.post<Invoice>(`${INVOICES}/${invoiceId}/send`);
  return data;
}

/** `POST /api/billing/invoices/{id}/cancel` (issuing doctor): cancels an invoice that has received no payment. */
export async function cancelInvoice(invoiceId: number, request?: CancelInvoiceRequest): Promise<Invoice> {
  const reason = request?.reason?.trim();
  const { data } = await api.post<Invoice>(`${INVOICES}/${invoiceId}/cancel`, reason ? { reason } : {});
  return data;
}

// ─── Receipt ──────────────────────────────────────────────────────────────────

/**
 * A request made with `responseType: 'blob'` delivers the JSON error envelope as a Blob too. This reads it back
 * into an object on the error, so `apiError` finds the server's message and status as for any other request.
 */
async function withJsonErrorBody(e: unknown): Promise<unknown> {
  const response = (e as { response?: { data?: unknown } } | null)?.response;
  const body = response?.data;
  if (response && typeof Blob !== 'undefined' && body instanceof Blob) {
    try {
      response.data = JSON.parse(await body.text());
    } catch {
      response.data = undefined;
    }
  }
  return e;
}

/**
 * `GET /api/billing/invoices/{id}/receipt`: the printable document of an invoice (its receipt once it is paid) as
 * an HTML Blob. It is fetched through the shared client so the bearer token is attached; the API path must never
 * be opened in a tab or a frame, and the HTML must never be injected into the app's own page. Fetch it again each
 * time it is printed: the document changes when a payment arrives.
 */
export async function fetchInvoiceReceipt(invoiceId: number): Promise<Blob> {
  try {
    const { data } = await api.get<Blob>(`${INVOICES}/${invoiceId}/receipt`, { responseType: 'blob' });
    return data;
  } catch (e) {
    throw await withJsonErrorBody(e);
  }
}

// ─── Payments ─────────────────────────────────────────────────────────────────

/**
 * `POST /api/billing/invoices/{invoiceId}/payments`: starts a payment (patient, or caregiver who may manage
 * billing; CARD and MPESA only). Send one key per Pay dialog open, reuse it on retry and never reuse it for
 * another invoice. A replayed key answers the stored payment, which may be out of date: read it again with
 * `getPayment`.
 */
export async function initiatePayment(
  invoiceId: number,
  request: InitiatePaymentRequest,
  idempotencyKey: string,
): Promise<Payment> {
  const { data } = await api.post<Payment>(`${INVOICES}/${invoiceId}/payments`, request, withIdempotencyKey(idempotencyKey));
  return data;
}

/** `GET /api/billing/payments/{id}`: one payment; the endpoint a pending payment is polled with. */
export async function getPayment(paymentId: number): Promise<Payment> {
  const { data } = await api.get<Payment>(`${PAYMENTS}/${paymentId}`);
  return data;
}

/** `GET /api/billing/payments?patientSystemId=`: every payment of one patient, newest first. Doctors get 403. */
export function listPatientPayments(patientSystemId: string, page = 0, size?: number): Promise<PageResponse<Payment>> {
  return fetchPage<Payment>(PAYMENTS, page, size, { patientSystemId });
}

/**
 * `GET /api/billing/invoices/{id}/payments`: the payment history of one invoice, failed attempts included.
 * Readable by everyone who may read the invoice, the issuing doctor included.
 */
export function listInvoicePayments(invoiceId: number, page = 0, size?: number): Promise<PageResponse<Payment>> {
  return fetchPage<Payment>(`${INVOICES}/${invoiceId}/payments`, page, size);
}

/** `GET /api/billing/config`: what the active payment gateway accepts. The same answer for every user. */
export async function getBillingConfig(): Promise<BillingConfig> {
  const { data } = await api.get<BillingConfig>('/api/billing/config');
  return data;
}

/**
 * `POST /api/billing/payments/{id}/simulate`: settles a pending payment of the local gateway. Development only:
 * call it only when `BillingConfig.simulationEnabled` is true (it answers 404 otherwise).
 */
export async function simulatePayment(paymentId: number, outcome: SimulatedPaymentOutcome): Promise<Payment> {
  const { data } = await api.post<Payment>(`${PAYMENTS}/${paymentId}/simulate`, { outcome });
  return data;
}

// ─── Saved payment methods ────────────────────────────────────────────────────

/** `GET /api/billing/payment-methods`: the calling patient's own saved methods (patients only), default first. */
export async function listMyPaymentMethods(): Promise<PaymentMethod[]> {
  const { data } = await api.get<PaymentMethod[]>(METHODS);
  return Array.isArray(data) ? data : [];
}

/**
 * `GET /api/billing/payment-methods/patient/{patientSystemId}`: the saved methods of one patient, read-only.
 * The patient passes their own id; a caregiver needs the permission to manage billing.
 */
export async function listPatientPaymentMethods(patientSystemId: string): Promise<PaymentMethod[]> {
  const { data } = await api.get<PaymentMethod[]>(`${METHODS}/patient/${encodeURIComponent(patientSystemId)}`);
  return Array.isArray(data) ? data : [];
}

/**
 * `POST /api/billing/payment-methods` (patients only). Send one key per "Add payment method" form open. After a
 * success refetch the list: the default flags of the other methods may have changed.
 */
export async function addPaymentMethod(request: CreatePaymentMethodRequest, idempotencyKey: string): Promise<PaymentMethod> {
  const { data } = await api.post<PaymentMethod>(METHODS, request, withIdempotencyKey(idempotencyKey));
  return data;
}

/** `PATCH /api/billing/payment-methods/{id}/default` (patients only): makes this method the default. */
export async function setDefaultPaymentMethod(methodId: number): Promise<PaymentMethod> {
  const { data } = await api.patch<PaymentMethod>(`${METHODS}/${methodId}/default`);
  return data;
}

/**
 * `DELETE /api/billing/payment-methods/{id}` (patients only). A 404 means the method was already removed: treat
 * it as done and refetch the list.
 */
export async function deletePaymentMethod(methodId: number): Promise<void> {
  await api.delete(`${METHODS}/${methodId}`);
}

// ─── Insurance claims (never called for a doctor: every claim endpoint answers 403 to that role) ───────────────

/**
 * `POST /api/billing/invoices/{invoiceId}/claims`: submits a claim for an issued invoice (patient, or caregiver
 * who may manage billing). Send one key per claim form open and never reuse it for another invoice.
 */
export async function submitClaim(
  invoiceId: number,
  request: SubmitClaimRequest,
  idempotencyKey: string,
): Promise<InsuranceClaim> {
  const { data } = await api.post<InsuranceClaim>(`${INVOICES}/${invoiceId}/claims`, request, withIdempotencyKey(idempotencyKey));
  return data;
}

/** `GET /api/billing/claims/{claimId}`. */
export async function getClaim(claimId: number): Promise<InsuranceClaim> {
  const { data } = await api.get<InsuranceClaim>(`${CLAIMS}/${claimId}`);
  return data;
}

/**
 * `GET /api/billing/claims?patientSystemId=`: the claims of one patient, newest first. Pass `invoiceId` for the
 * claims of one invoice (there is no nested /invoices/{id}/claims path).
 */
export function listClaims(
  patientSystemId: string,
  filters?: ClaimListFilters,
  page = 0,
  size?: number,
): Promise<PageResponse<InsuranceClaim>> {
  return fetchPage<InsuranceClaim>(CLAIMS, page, size, {
    patientSystemId,
    status:    statusParam(filters?.status),
    invoiceId: filters?.invoiceId ?? undefined,
  });
}

/** `POST /api/billing/claims/{claimId}/appeal`: contests a REJECTED or PARTIAL decision. */
export async function appealClaim(claimId: number, request: AppealClaimRequest): Promise<InsuranceClaim> {
  const { data } = await api.post<InsuranceClaim>(`${CLAIMS}/${claimId}/appeal`, request);
  return data;
}
