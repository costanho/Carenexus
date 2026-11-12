import { z } from 'zod';

// Types of the administrator billing back office: invoices, payments, insurance claims and the clinic's
// default fees. Field names are the API's (camelCase), straight from the billing contracts. This library is
// self-contained on purpose: it does not import lib/billing (the patient, caregiver and doctor billing library).
//
// Money is a JSON number with two decimals; currency is an ISO-4217 code. Date-times are clinic wall-clock
// strings ("2026-11-10T09:15:30"): format them with `@/lib/time/clinic`, never with the device zone.

// ─── Invoices ─────────────────────────────────────────────────────────────────

export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE' | 'CANCELLED' | 'REFUNDED';

export const INVOICE_STATUSES: readonly InvoiceStatus[] = [
  'DRAFT', 'SENT', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED', 'REFUNDED',
];

export const INVOICE_STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT:          'Draft',
  SENT:           'Sent',
  PARTIALLY_PAID: 'Partly paid',
  PAID:           'Paid',
  OVERDUE:        'Overdue',
  CANCELLED:      'Cancelled',
  REFUNDED:       'Refunded',
};

/** Statuses in which an invoice still accepts a payment or an insurance settlement. */
export const PAYABLE_INVOICE_STATUSES: readonly InvoiceStatus[] = ['SENT', 'PARTIALLY_PAID', 'OVERDUE'];

/** Statuses from which an administrator may void an invoice, provided nothing was paid. */
export const VOIDABLE_INVOICE_STATUSES: readonly InvoiceStatus[] = ['DRAFT', 'SENT', 'OVERDUE'];

export interface InvoiceLineItem {
  description: string;
  quantity:    number;
  unitPrice:   number;
  amount:      number;
}

/** `InvoiceResponse` of the invoice contract. */
export interface Invoice {
  invoiceId:         number;
  invoiceNo:         string;
  patientSystemId:   string;
  patientName:       string | null;
  doctorSystemId:    string | null;
  doctorName:        string | null;
  createdBySystemId: string | null;
  appointmentId:     number | null;
  consultationId:    number | null;
  lineItems:         InvoiceLineItem[];
  subtotal:          number;
  tax:               number;
  discount:          number;
  total:             number;
  amountPaid:        number;
  /** What can still be paid now: total minus amountPaid while payable, 0 otherwise. */
  outstanding:       number;
  currency:          string;
  status:            InvoiceStatus;
  /** Date only ("2026-11-24"). */
  dueDate:           string | null;
  issuedAt:          string | null;
  paidAt:            string | null;
  cancelReason:      string | null;
  notes:             string | null;
  createdAt:         string;
  updatedAt:         string | null;
}

/** Filters of GET /api/admin/billing/invoices. `status` is one status or a comma-separated list. */
export interface AdminInvoiceFilters {
  status?:          string;
  patientSystemId?: string;
}

export interface VoidInvoiceRequest { reason: string }

// ─── Payments ─────────────────────────────────────────────────────────────────

export type PaymentMethodCode = 'CARD' | 'INSURANCE' | 'CASH' | 'MPESA' | 'BANK_TRANSFER';
export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED' | 'CANCELLED';
/** Methods an administrator can record by hand. */
export type ManualPaymentMethod = 'CASH' | 'BANK_TRANSFER';

export const PAYMENT_METHODS: readonly PaymentMethodCode[] = ['CARD', 'MPESA', 'CASH', 'BANK_TRANSFER', 'INSURANCE'];
export const PAYMENT_STATUSES: readonly PaymentStatus[] = ['PENDING', 'COMPLETED', 'FAILED', 'REFUNDED', 'CANCELLED'];
export const MANUAL_PAYMENT_METHODS: readonly ManualPaymentMethod[] = ['CASH', 'BANK_TRANSFER'];

export const PAYMENT_METHOD_LABEL: Record<PaymentMethodCode, string> = {
  CARD:          'Card',
  MPESA:         'M-PESA',
  CASH:          'Cash',
  BANK_TRANSFER: 'Bank transfer',
  INSURANCE:     'Insurance',
};

export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  PENDING:   'Pending',
  COMPLETED: 'Completed',
  FAILED:    'Failed',
  REFUNDED:  'Refunded',
  CANCELLED: 'Cancelled',
};

/**
 * One payment as the administrator endpoints return it (`AdminPaymentResponse`). The refund endpoint and the
 * payment history of an invoice answer with the wider `PaymentResponse`; the members only that shape carries
 * are optional here, so one type serves the list, the history and the refund flow.
 */
export interface AdminPayment {
  paymentId:           number;
  invoiceId:           number | null;
  invoiceNo:           string | null;
  patientSystemId:     string;
  patientName:         string | null;
  amount:              number;
  currency:            string;
  method:              PaymentMethodCode;
  status:              PaymentStatus;
  /** Opaque label of the channel that handled it ("MANUAL", "INSURANCE", a gateway name). Display only. */
  gateway:             string | null;
  /** Gateway reference; for a manual payment the receipt or bank reference the administrator typed. */
  providerReference:   string | null;
  transactionId:       string | null;
  paymentMethodId:     number | null;
  initiatedBySystemId: string | null;
  failureReason:       string | null;
  refundedAmount:      number;
  refundReason:        string | null;
  paidAt:              string | null;
  createdAt:           string;
  updatedAt:           string | null;
  // Only in `PaymentResponse`:
  initiatedByName?:    string | null;
  paymentMethodLabel?: string | null;
  /** Masked ("**** 4567"). */
  payerPhone?:         string | null;
  /** What an administrator can still refund, when the server computed it. See `refundableAmount`. */
  refundableAmount?:   number;
}

/** Filters of GET /api/admin/billing/payments. `status` and `method` accept comma-separated lists. */
export interface AdminPaymentFilters {
  status?:          string;
  method?:          string;
  patientSystemId?: string;
}

export interface ManualPaymentRequest {
  method:     ManualPaymentMethod;
  amount:     number;
  /** Required for BANK_TRANSFER, optional for CASH; at most 100 characters. */
  reference?: string | null;
}

export interface RefundPaymentRequest {
  /** Absent or null refunds everything that is still refundable. */
  amount?: number | null;
  reason:  string;
}

// ─── Insurance claims ─────────────────────────────────────────────────────────

export type ClaimStatus = 'SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'PARTIAL' | 'APPEALED';

export const CLAIM_STATUSES: readonly ClaimStatus[] = ['SUBMITTED', 'PENDING', 'APPEALED', 'APPROVED', 'PARTIAL', 'REJECTED'];

/** A claim waiting for an administrator (one open claim per invoice). */
export const OPEN_CLAIM_STATUSES: readonly ClaimStatus[] = ['SUBMITTED', 'PENDING', 'APPEALED'];

/** The `status` filter value of the review queue. */
export const OPEN_CLAIMS_FILTER = OPEN_CLAIM_STATUSES.join(',');

export const CLAIM_STATUS_LABEL: Record<ClaimStatus, string> = {
  SUBMITTED: 'Submitted',
  PENDING:   'Under review',
  APPROVED:  'Approved',
  PARTIAL:   'Partly approved',
  REJECTED:  'Rejected',
  APPEALED:  'Appealed',
};

export interface ClaimDocument { documentId: number; title: string }

/** `InsuranceClaimResponse` of the claims contract. */
export interface InsuranceClaim {
  claimId:             number;
  claimNo:             string;
  invoiceId:           number;
  invoiceNo:           string | null;
  patientSystemId:     string;
  patientName:         string | null;
  insuranceProvider:   string;
  policyNumber:        string;
  memberNumber:        string | null;
  claimAmount:         number;
  /** Cumulative amount approved so far; null until a decision approves something. */
  approvedAmount:      number | null;
  currency:            string;
  /** What can still be settled on the invoice now: the upper bound of a settlement. */
  invoiceOutstanding:  number;
  status:              ClaimStatus;
  /** Reason of a rejection, or the reviewer's note on a partial approval. */
  rejectionReason:     string | null;
  appealReason:        string | null;
  /** Ids of the attached documents in the order submitted. */
  documentIds:         number[];
  /** The attached documents that still exist (a document deleted later stays in `documentIds` only). */
  documents:           ClaimDocument[];
  paymentId:           number | null;
  providerReference:   string | null;
  submittedBySystemId: string;
  reviewedBySystemId:  string | null;
  submittedAt:         string;
  resolvedAt:          string | null;
  updatedAt:           string | null;
}

/** Filters of GET /api/admin/billing/claims. `status` is one status or a comma-separated list. */
export interface AdminClaimFilters {
  status?:          string;
  patientSystemId?: string;
  invoiceId?:       number;
}

export type AdjudicationStatus = 'PENDING' | 'APPROVED' | 'PARTIAL' | 'REJECTED';

export interface AdjudicateClaimRequest {
  status:           AdjudicationStatus;
  /** Used only for PARTIAL. */
  approvedAmount?:  number | null;
  /** Required for REJECTED; optional reviewer's note for PARTIAL. */
  rejectionReason?: string | null;
}

/** Metadata of an attached document, as far as the download needs it (`DocumentResponse` of be-documents). */
export interface ClaimDocumentFile {
  documentId:       number;
  title:            string;
  originalFilename: string;
  contentType:      string;
  sizeBytes:        number;
}

// ─── Default fees ─────────────────────────────────────────────────────────────

export type AppointmentType = 'VIDEO' | 'IN_PERSON' | 'PHONE';
export type FeeSource = 'DOCTOR' | 'DEFAULT' | 'NONE';

export const APPOINTMENT_TYPES: readonly AppointmentType[] = ['VIDEO', 'IN_PERSON', 'PHONE'];

export const APPOINTMENT_TYPE_LABEL: Record<AppointmentType, string> = {
  VIDEO:     'Video consultation',
  IN_PERSON: 'In-person consultation',
  PHONE:     'Phone consultation',
};

/** `FeeResponse`: the fee endpoints always answer with exactly three rows (VIDEO, IN_PERSON, PHONE). */
export interface Fee {
  appointmentType: AppointmentType;
  amount:          number;
  /** The clinic's default currency; not settable by the client. */
  currency:        string;
  /** For the clinic defaults: DEFAULT when a default row exists, NONE when none was ever set. */
  source:          FeeSource;
  defaultAmount:   number;
  doctorSystemId:  string | null;
}

export interface DefaultFeeInput { appointmentType: AppointmentType; amount: number }

/** Body of PUT /api/admin/billing/fees: 1 to 3 rows, each type at most once; types not listed stay unchanged. */
export interface UpdateDefaultFeesRequest { fees: DefaultFeeInput[] }

// ─── Money helpers ────────────────────────────────────────────────────────────

/** An amount in cents, for comparing and subtracting money without floating-point drift. */
export function toCents(amount: number | null | undefined): number {
  return typeof amount === 'number' && Number.isFinite(amount) ? Math.round(amount * 100) : 0;
}

export function fromCents(cents: number): number {
  return Math.round(cents) / 100;
}

/** "1500" for 1500, "1500.5" becomes "1500.50": the text an amount field starts with. */
export function amountText(amount: number | null | undefined): string {
  if (typeof amount !== 'number' || !Number.isFinite(amount)) return '';
  return (Math.round(amount * 100) / 100).toFixed(2);
}

/** Up to 10 integer digits and 2 decimals (payments, refunds, claim amounts). */
const MONEY_10_2 = /^\d{1,10}(\.\d{1,2})?$/;
/** Up to 6 integer digits and 2 decimals (fees). */
const MONEY_6_2 = /^\d{1,6}(\.\d{1,2})?$/;

/** The number typed into an amount field, or null when the text is not a plain amount with at most two decimals. */
export function parseMoney(text: string | null | undefined, integerDigits: 6 | 10 = 10): number | null {
  const value = (text ?? '').trim();
  if (!(integerDigits === 6 ? MONEY_6_2 : MONEY_10_2).test(value)) return null;
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : null;
}

/**
 * What an administrator can still refund on a payment: the server's `refundableAmount` when the row carries
 * it, otherwise the same rule computed here (a completed payment that is not an insurance settlement and is
 * linked to an invoice: amount minus what was refunded already).
 */
export function refundableAmount(payment: AdminPayment): number {
  if (typeof payment.refundableAmount === 'number' && Number.isFinite(payment.refundableAmount)) {
    return Math.max(0, payment.refundableAmount);
  }
  if (payment.status !== 'COMPLETED' || payment.method === 'INSURANCE' || payment.invoiceId == null) return 0;
  return Math.max(0, fromCents(toCents(payment.amount) - toCents(payment.refundedAmount)));
}

/** The receipt, bank or gateway reference to show for a payment. */
export function paymentReference(payment: AdminPayment): string | null {
  return payment.providerReference?.trim() || payment.transactionId?.trim() || null;
}

/** Void is offered for an invoice in DRAFT, SENT or OVERDUE that has received no payment. */
export function canVoidInvoice(invoice: Pick<Invoice, 'status' | 'amountPaid'>): boolean {
  return VOIDABLE_INVOICE_STATUSES.includes(invoice.status) && toCents(invoice.amountPaid) === 0;
}

/** A manual payment is offered while the invoice is payable and has a balance. */
export function canRecordPayment(invoice: Pick<Invoice, 'status' | 'outstanding'>): boolean {
  return PAYABLE_INVOICE_STATUSES.includes(invoice.status) && toCents(invoice.outstanding) > 0;
}

/**
 * The insurance payment a decision would put on the invoice: the new cumulative approved amount minus what
 * was approved before (0 when nothing was). Zero means no payment (an appeal dismissed with the same amount).
 */
export function claimSettlement(claim: Pick<InsuranceClaim, 'approvedAmount'>, newApprovedAmount: number): number {
  return Math.max(0, fromCents(toCents(newApprovedAmount) - toCents(claim.approvedAmount)));
}

/** Which adjudication steps the claim's status allows (the state machine of the claims contract). */
export function claimActions(claim: Pick<InsuranceClaim, 'status' | 'approvedAmount'>): {
  startReview: boolean; reopenReview: boolean; approve: boolean; approvePartly: boolean; reject: boolean;
} {
  const deciding = claim.status === 'PENDING';
  return {
    startReview:   claim.status === 'SUBMITTED',
    reopenReview:  claim.status === 'APPEALED',
    approve:       deciding,
    approvePartly: deciding,
    // Money that was approved before is already on the invoice, so such a claim can no longer be rejected.
    reject:        deciding && claim.approvedAmount == null,
  };
}

// ─── Form validation (mirrors the backend constraints) ────────────────────────

/** A patient's system id, as typed into the `patientSystemId` filters ("PAT-00001"). */
export const PATIENT_ID_PATTERN = /^PAT-\d{5,}$/;

export const VOID_REASON_MAX = 500;
export const REFUND_REASON_MAX = 500;
export const PAYMENT_REFERENCE_MAX = 100;
export const CLAIM_REASON_MAX = 1000;
export const FEE_MAX = 999999.99;

const AMOUNT_FORMAT_MESSAGE = 'Enter an amount in numbers with at most two decimals, for example 1500.00';

/** The message for an amount field, or null when the text is a valid amount above zero. */
function positiveAmountError(text: string): string | null {
  if (!text.trim()) return 'Enter an amount';
  const amount = parseMoney(text);
  if (amount === null) return AMOUNT_FORMAT_MESSAGE;
  if (toCents(amount) <= 0) return 'The amount must be greater than 0';
  return null;
}

function tooLong(max: number): string {
  return `Use ${max} characters or fewer`;
}

export const voidInvoiceSchema = z.object({
  reason: z.string().trim().min(1, 'Enter the reason for voiding this invoice').max(VOID_REASON_MAX, tooLong(VOID_REASON_MAX)),
});
export type VoidInvoiceValues = z.infer<typeof voidInvoiceSchema>;

export interface ManualPaymentValues { method: string; amount: string; reference: string }

/** A manual payment: CASH or BANK_TRANSFER, above zero, not above the invoice balance, reference for a transfer. */
export function manualPaymentSchema(outstanding: number, describe: (amount: number) => string) {
  return z.object({
    method:    z.string(),
    amount:    z.string(),
    reference: z.string().trim().max(PAYMENT_REFERENCE_MAX, tooLong(PAYMENT_REFERENCE_MAX)),
  }).superRefine((values, ctx) => {
    if (values.method !== 'CASH' && values.method !== 'BANK_TRANSFER') {
      ctx.addIssue({ code: 'custom', path: ['method'], message: 'Choose how the payment was received' });
    }
    const problem = positiveAmountError(values.amount);
    if (problem) {
      ctx.addIssue({ code: 'custom', path: ['amount'], message: problem });
    } else if (toCents(parseMoney(values.amount)) > toCents(outstanding)) {
      ctx.addIssue({ code: 'custom', path: ['amount'], message: `The amount cannot be more than the outstanding balance of ${describe(outstanding)}` });
    }
    if (values.method === 'BANK_TRANSFER' && !values.reference.trim()) {
      ctx.addIssue({ code: 'custom', path: ['reference'], message: 'Enter the bank reference of the transfer' });
    }
  });
}

export interface RefundValues { amount: string; reason: string }

/** A refund: a reason, and an amount (blank = everything refundable) above zero and not above what is refundable. */
export function refundSchema(refundable: number, describe: (amount: number) => string) {
  return z.object({
    amount: z.string(),
    reason: z.string().trim().min(1, 'Enter the reason for the refund').max(REFUND_REASON_MAX, tooLong(REFUND_REASON_MAX)),
  }).superRefine((values, ctx) => {
    if (!values.amount.trim()) return; // blank refunds the whole refundable amount
    const problem = positiveAmountError(values.amount);
    if (problem) {
      ctx.addIssue({ code: 'custom', path: ['amount'], message: problem });
    } else if (toCents(parseMoney(values.amount)) > toCents(refundable)) {
      ctx.addIssue({ code: 'custom', path: ['amount'], message: `The amount cannot be more than the refundable ${describe(refundable)}` });
    }
  });
}

export interface PartialApprovalValues { approvedAmount: string; note: string }

/** A partial approval: below the claimed amount and not below what was approved before; an optional note. */
export function partialApprovalSchema(
  claim: Pick<InsuranceClaim, 'claimAmount' | 'approvedAmount'>,
  describe: (amount: number) => string,
) {
  return z.object({
    approvedAmount: z.string(),
    note:           z.string().trim().max(CLAIM_REASON_MAX, tooLong(CLAIM_REASON_MAX)),
  }).superRefine((values, ctx) => {
    const problem = positiveAmountError(values.approvedAmount);
    if (problem) {
      ctx.addIssue({ code: 'custom', path: ['approvedAmount'], message: problem });
      return;
    }
    const cents = toCents(parseMoney(values.approvedAmount));
    if (cents >= toCents(claim.claimAmount)) {
      ctx.addIssue({
        code: 'custom', path: ['approvedAmount'],
        message: `A partial approval must be below the claimed ${describe(claim.claimAmount)}. Use Approve in full for the whole amount.`,
      });
    } else if (claim.approvedAmount != null && cents < toCents(claim.approvedAmount)) {
      ctx.addIssue({
        code: 'custom', path: ['approvedAmount'],
        message: `The amount cannot be lower than the ${describe(claim.approvedAmount)} already approved`,
      });
    }
  });
}

export const rejectClaimSchema = z.object({
  rejectionReason: z.string().trim().min(1, 'Enter the reason for rejecting this claim').max(CLAIM_REASON_MAX, tooLong(CLAIM_REASON_MAX)),
});
export type RejectClaimValues = z.infer<typeof rejectClaimSchema>;

/** One amount text per appointment type. */
export type DefaultFeesValues = Record<AppointmentType, string>;

/** The message for a default fee field, or null: required, 0.00 to 999999.99, at most two decimals. */
export function feeAmountError(text: string): string | null {
  if (!text.trim()) return 'Enter a fee. Use 0 when the clinic charges nothing.';
  if (parseMoney(text, 6) === null) {
    return 'Enter a fee between 0 and 999999.99 with at most two decimals';
  }
  return null;
}

const feeField = z.string().superRefine((text, ctx) => {
  const problem = feeAmountError(text);
  if (problem) ctx.addIssue({ code: 'custom', message: problem });
});

export const defaultFeesSchema = z.object({ VIDEO: feeField, IN_PERSON: feeField, PHONE: feeField });
