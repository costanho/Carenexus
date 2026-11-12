import { formatMoney } from '@/lib/format/money';
import { formatClinicDate, formatClinicDateTime } from '@/lib/time/clinic';
import { formatDateOnly } from '@/lib/ui/date-only';
import type {
  AppointmentType, ClaimStatus, FeeSource, InsuranceClaim, Invoice, InvoiceStatus, Payment, PaymentMethodCode,
  PaymentStatus, SavedPaymentMethodType,
} from './types';

// Words and small rules shared by every billing screen. Money always goes through `formatMoney`; the labels match
// the ones printed on the receipt document, so the screen and the printout read the same.

// ─── Invoices ─────────────────────────────────────────────────────────────────

export const INVOICE_STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT:          'Draft',
  SENT:           'Sent',
  PARTIALLY_PAID: 'Partially paid',
  PAID:           'Paid',
  OVERDUE:        'Overdue',
  CANCELLED:      'Cancelled',
  REFUNDED:       'Refunded',
};

/** Statuses of an invoice that can still be paid. Screens treat the three alike and read `outstanding`. */
export const OPEN_INVOICE_STATUSES: readonly InvoiceStatus[] = ['SENT', 'PARTIALLY_PAID', 'OVERDUE'];

/** Statuses a patient or caregiver can ever see (a DRAFT is invisible to them). */
export const PATIENT_VISIBLE_INVOICE_STATUSES: readonly InvoiceStatus[] =
  ['SENT', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED', 'REFUNDED'];

function titleCase(value: string | null | undefined): string {
  const words = (value ?? '').trim().replace(/[\s_-]+/g, ' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Unknown';
}

export function invoiceStatusLabel(status: string | null | undefined): string {
  return INVOICE_STATUS_LABEL[status as InvoiceStatus] ?? titleCase(status);
}

/** True while the invoice can still be paid (SENT, PARTIALLY_PAID or OVERDUE). */
export function isOpenInvoice(invoice: Pick<Invoice, 'status'>): boolean {
  return OPEN_INVOICE_STATUSES.includes(invoice.status);
}

/** True when the invoice has a balance a payment or a claim could settle now. */
export function isPayableInvoice(invoice: Pick<Invoice, 'status' | 'outstanding'>): boolean {
  return isOpenInvoice(invoice) && invoice.outstanding > 0;
}

/** "Dr. Farai Ncube", or the fallback when the invoice carries no doctor name. */
export function invoiceDoctorLabel(invoice: Pick<Invoice, 'doctorName'>, fallback = 'Your doctor'): string {
  const name = invoice.doctorName?.trim();
  return name ? `Dr. ${name}` : fallback;
}

/** A calendar date such as an invoice's due date: "24 Nov 2026". */
export function formatBillingDate(value: string | null | undefined, fallback = ''): string {
  return formatDateOnly(value, undefined, fallback);
}

/** The day of a clinic date-time: "Tue 10 Nov 2026". */
export function formatBillingDay(value: string | null | undefined, fallback = ''): string {
  return formatClinicDate(value, fallback);
}

/** A clinic date-time: "Tue 10 Nov 2026, 09:15 CAT". */
export function formatBillingDateTime(value: string | null | undefined, fallback = ''): string {
  return formatClinicDateTime(value, fallback);
}

// ─── Payments ─────────────────────────────────────────────────────────────────

export const PAYMENT_METHOD_LABEL: Record<PaymentMethodCode, string> = {
  CARD:          'Card',
  MPESA:         'M-PESA',
  CASH:          'Cash',
  BANK_TRANSFER: 'Bank transfer',
  INSURANCE:     'Insurance',
};

export function paymentMethodLabel(method: string | null | undefined): string {
  return PAYMENT_METHOD_LABEL[method as PaymentMethodCode] ?? titleCase(method);
}

export const PAYMENT_STATUS_LABEL: Record<PaymentStatus, string> = {
  PENDING:   'Processing',
  COMPLETED: 'Completed',
  FAILED:    'Failed',
  REFUNDED:  'Refunded',
  CANCELLED: 'Cancelled',
};

export function paymentStatusLabel(status: string | null | undefined): string {
  return PAYMENT_STATUS_LABEL[status as PaymentStatus] ?? titleCase(status);
}

/** True while the payment can still change: the gateway has not confirmed or refused it yet. */
export function isPendingPayment(payment: Pick<Payment, 'status'>): boolean {
  return payment.status === 'PENDING';
}

/** How the payment was made, in words: the saved method's masked label when one was used, else the method name. */
export function paymentMethodSummary(payment: Pick<Payment, 'method' | 'paymentMethodLabel'>): string {
  return payment.paymentMethodLabel?.trim() || paymentMethodLabel(payment.method);
}

/** The reference to quote for a payment: the confirmation code, else the gateway's reference, else null. */
export function paymentReference(payment: Pick<Payment, 'transactionId' | 'providerReference'>): string | null {
  return payment.transactionId?.trim() || payment.providerReference?.trim() || null;
}

export const SAVED_METHOD_TYPE_LABEL: Record<SavedPaymentMethodType, string> = {
  CARD:      'Card',
  MPESA:     'M-PESA',
  INSURANCE: 'Insurance',
};

// ─── Fees ─────────────────────────────────────────────────────────────────────

export const APPOINTMENT_TYPE_LABEL: Record<AppointmentType, string> = {
  VIDEO:     'Video consultation',
  IN_PERSON: 'In-person consultation',
  PHONE:     'Phone consultation',
};

export function appointmentTypeLabel(type: string | null | undefined): string {
  return APPOINTMENT_TYPE_LABEL[type as AppointmentType] ?? titleCase(type);
}

export const FEE_SOURCE_LABEL: Record<FeeSource, string> = {
  DOCTOR:  'Your fee',
  DEFAULT: 'Clinic default',
  NONE:    'No fee set',
};

// ─── Claims ───────────────────────────────────────────────────────────────────

export const CLAIM_STATUS_LABEL: Record<ClaimStatus, string> = {
  SUBMITTED: 'Submitted',
  PENDING:   'Under review',
  APPROVED:  'Approved',
  PARTIAL:   'Partly approved',
  REJECTED:  'Rejected',
  APPEALED:  'Appealed',
};

export function claimStatusLabel(status: string | null | undefined): string {
  return CLAIM_STATUS_LABEL[status as ClaimStatus] ?? titleCase(status);
}

/** A claim that is still being decided. An invoice has at most one. */
export const OPEN_CLAIM_STATUSES: readonly ClaimStatus[] = ['SUBMITTED', 'PENDING', 'APPEALED'];

export function isOpenClaim(claim: Pick<InsuranceClaim, 'status'>): boolean {
  return OPEN_CLAIM_STATUSES.includes(claim.status);
}

/** A decision that can be contested with an appeal. */
export function isAppealableClaim(claim: Pick<InsuranceClaim, 'status'>): boolean {
  return claim.status === 'REJECTED' || claim.status === 'PARTIAL';
}

/** One sentence that says where a claim stands, for the person who submitted it. */
export function claimStatusMeaning(claim: Pick<InsuranceClaim, 'status' | 'approvedAmount' | 'claimAmount' | 'currency'>): string {
  switch (claim.status) {
    case 'SUBMITTED': return 'The claim was received and is waiting for review.';
    case 'PENDING':   return 'The claim is being reviewed.';
    case 'APPROVED':  return 'The full amount was approved and paid onto the invoice.';
    case 'PARTIAL': {
      const approved = formatMoney(claim.approvedAmount, claim.currency);
      return approved
        ? `${approved} of ${formatMoney(claim.claimAmount, claim.currency)} was approved and paid onto the invoice. The rest is still owed.`
        : 'Part of the claim was approved and paid onto the invoice. The rest is still owed.';
    }
    case 'REJECTED':  return 'The claim was rejected. Nothing was paid onto the invoice.';
    case 'APPEALED':  return 'The decision was appealed and is waiting for review.';
    default:          return '';
  }
}
