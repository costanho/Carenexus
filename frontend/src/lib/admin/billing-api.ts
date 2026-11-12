import { withIdempotencyKey } from '@/lib/api/idempotency';
import { DEFAULT_PAGE_SIZE, fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import {
  OPEN_CLAIMS_FILTER,
  type AdjudicateClaimRequest, type AdminClaimFilters, type AdminInvoiceFilters, type AdminPayment,
  type AdminPaymentFilters, type ClaimDocumentFile, type DefaultFeeInput, type Fee, type InsuranceClaim,
  type Invoice, type ManualPaymentRequest, type RefundPaymentRequest, type UpdateDefaultFeesRequest,
  type VoidInvoiceRequest,
} from './billing-types';

// Client of the administrator billing endpoints (one function per endpoint). Everything goes through the shared
// axios instance, so the bearer token and the token refresh are handled there. Functions reject with the axios
// error: show it with `apiError` / `apiErrorMessage`.
//
// Administrator-only paths live under /api/admin/billing. One invoice and one claim are read through the shared
// reads (/api/billing/invoices/{id}, /api/billing/claims/{id}), which answer every invoice and claim to an
// administrator; there is no /api/admin variant of them.

// ─── Invoices ─────────────────────────────────────────────────────────────────

/** GET /api/admin/billing/invoices: every invoice, newest first. */
export function listAdminInvoices(
  filters: AdminInvoiceFilters = {}, page = 0, size = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<Invoice>> {
  return fetchPage<Invoice>('/api/admin/billing/invoices', page, size, {
    status: filters.status, patientSystemId: filters.patientSystemId,
  });
}

/** GET /api/billing/invoices/{id}: one invoice in any status. 404 "Invoice not found" for an unknown id. */
export async function getInvoice(invoiceId: number): Promise<Invoice> {
  const { data } = await api.get<Invoice>(`/api/billing/invoices/${invoiceId}`);
  return data;
}

/** GET /api/billing/invoices/{id}/payments: the payment history of one invoice, failed attempts included. */
export function listInvoicePayments(
  invoiceId: number, page = 0, size = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<AdminPayment>> {
  return fetchPage<AdminPayment>(`/api/billing/invoices/${invoiceId}/payments`, page, size);
}

/**
 * POST /api/admin/billing/invoices/{id}/void: cancels an invoice that has received no payment.
 * 409 "An invoice with payments cannot be cancelled" when something was paid. Repeating it is a no-op.
 */
export async function voidInvoice(invoiceId: number, body: VoidInvoiceRequest): Promise<Invoice> {
  const { data } = await api.post<Invoice>(`/api/admin/billing/invoices/${invoiceId}/void`, body);
  return data;
}

// ─── Payments ─────────────────────────────────────────────────────────────────

/** GET /api/admin/billing/payments: every payment of every patient, newest first. */
export function listAdminPayments(
  filters: AdminPaymentFilters = {}, page = 0, size = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<AdminPayment>> {
  return fetchPage<AdminPayment>('/api/admin/billing/payments', page, size, {
    status: filters.status, method: filters.method, patientSystemId: filters.patientSystemId,
  });
}

/**
 * POST /api/admin/billing/invoices/{id}/payments/manual: records cash or a bank transfer the clinic already
 * received. `idempotencyKey` is one key per open dialog (reused on retry), so a repeated submit never records
 * the money twice. The answer is the payment only: refetch the invoice afterwards.
 */
export async function recordManualPayment(
  invoiceId: number, body: ManualPaymentRequest, idempotencyKey: string,
): Promise<AdminPayment> {
  const { data } = await api.post<AdminPayment>(
    `/api/admin/billing/invoices/${invoiceId}/payments/manual`, body, withIdempotencyKey(idempotencyKey),
  );
  return data;
}

/**
 * POST /api/admin/billing/payments/{id}/refund: refunds a completed payment in full (no `amount`) or in part.
 * The answer has the wider payment shape of the payment slice: refetch the lists instead of merging it.
 */
export async function refundPayment(
  paymentId: number, body: RefundPaymentRequest, idempotencyKey: string,
): Promise<AdminPayment> {
  const { data } = await api.post<AdminPayment>(
    `/api/admin/billing/payments/${paymentId}/refund`, body, withIdempotencyKey(idempotencyKey),
  );
  return data;
}

// ─── Insurance claims ─────────────────────────────────────────────────────────

/** GET /api/admin/billing/claims: every claim, newest first. */
export function listAdminClaims(
  filters: AdminClaimFilters = {}, page = 0, size = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<InsuranceClaim>> {
  return fetchPage<InsuranceClaim>('/api/admin/billing/claims', page, size, {
    status: filters.status, patientSystemId: filters.patientSystemId, invoiceId: filters.invoiceId,
  });
}

/** Number of claims waiting for an administrator (SUBMITTED, PENDING, APPEALED), for badges and the admin home. */
export async function countOpenClaims(): Promise<number> {
  const page = await listAdminClaims({ status: OPEN_CLAIMS_FILTER }, 0, 1);
  return page.totalElements;
}

/** GET /api/billing/claims/{claimId}: one claim. 404 "Claim not found" for an unknown id. */
export async function getClaim(claimId: number): Promise<InsuranceClaim> {
  const { data } = await api.get<InsuranceClaim>(`/api/billing/claims/${claimId}`);
  return data;
}

/**
 * PATCH /api/admin/billing/claims/{claimId}: one adjudication step. SUBMITTED and APPEALED claims are first
 * moved to PENDING; a PENDING claim is then decided APPROVED (always the whole claimed amount), PARTIAL
 * (with `approvedAmount`) or REJECTED (with `rejectionReason`). A request for the current status is a no-op.
 */
export async function adjudicateClaim(claimId: number, body: AdjudicateClaimRequest): Promise<InsuranceClaim> {
  const { data } = await api.patch<InsuranceClaim>(`/api/admin/billing/claims/${claimId}`, body);
  return data;
}

// ─── Claim documents ──────────────────────────────────────────────────────────

/** An error response of a blob request arrives as a Blob: turn it back into the JSON error envelope. */
async function restoreErrorBody(e: unknown): Promise<unknown> {
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
 * Downloads a document attached to a claim (GET /api/documents/{id}, then /download) and hands the file to the
 * browser to save. The bytes are fetched with the bearer token; the API path is never opened in a tab.
 * Rejects with the API error (403, 404 "Document not found") or when the runtime cannot save files.
 */
export async function downloadClaimDocument(documentId: number): Promise<ClaimDocumentFile> {
  const { data: file } = await api.get<ClaimDocumentFile>(`/api/documents/${documentId}`);

  let blob: Blob;
  try {
    const response = await api.get<Blob>(`/api/documents/${documentId}/download`, { responseType: 'blob', timeout: 60_000 });
    blob = response.data;
  } catch (e) {
    throw await restoreErrorBody(e);
  }

  const doc = (globalThis as { document?: Document }).document;
  const urls = (globalThis as { URL?: typeof URL }).URL;
  if (!doc || !urls || typeof urls.createObjectURL !== 'function') {
    throw new Error('Documents can be downloaded in the web app.');
  }
  const href = urls.createObjectURL(blob);
  const link = doc.createElement('a');
  link.href = href;
  link.download = file.originalFilename || `document-${documentId}`;
  link.rel = 'noopener';
  link.style.display = 'none';
  doc.body.appendChild(link);
  link.click();
  link.remove();
  // Revoked a little later: some browsers start the download after the click handler returns.
  setTimeout(() => urls.revokeObjectURL(href), 10_000);
  return file;
}

// ─── Default fees ─────────────────────────────────────────────────────────────

/** GET /api/billing/fees without a doctor: the clinic defaults, always three rows (VIDEO, IN_PERSON, PHONE). */
export async function getDefaultFees(): Promise<Fee[]> {
  const { data } = await api.get<Fee[]>('/api/billing/fees');
  return Array.isArray(data) ? data : [];
}

/** PUT /api/admin/billing/fees: sets the listed default fees and answers with the three default rows. */
export async function updateDefaultFees(fees: DefaultFeeInput[]): Promise<Fee[]> {
  const body: UpdateDefaultFeesRequest = { fees };
  const { data } = await api.put<Fee[]>('/api/admin/billing/fees', body);
  return Array.isArray(data) ? data : [];
}
