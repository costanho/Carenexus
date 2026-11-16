// Billing DTO types, camelCase exactly as the API sends them (api/be-billing-*.md).
//
// Conventions shared by every type here:
//   - Money is a JSON number with two decimals; show it with `formatMoney(amount, currency)` from '@/lib/format/money'.
//   - Date-times are clinic wall-clock strings ("2026-11-10T09:15:30", no offset); show them with '@/lib/time/clinic',
//     never with the device zone. `dueDate` is a date only ("2026-11-24").
//   - Every documented field is always present; a nullable field without a value is `null`.

// ─── Invoices ─────────────────────────────────────────────────────────────────

export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE' | 'CANCELLED' | 'REFUNDED';

export type AppointmentType = 'VIDEO' | 'IN_PERSON' | 'PHONE';

export interface InvoiceLineItem {
  description: string;
  quantity:    number;
  unitPrice:   number;
  /** unitPrice x quantity, computed by the server. */
  amount:      number;
}

export interface Invoice {
  invoiceId:         number;
  /** "INV-YYYY-NNNNNN". */
  invoiceNo:         string;
  patientSystemId:   string;
  patientName:       string | null;
  /** The issuing doctor: the doctor of the consultation or appointment. */
  doctorSystemId:    string | null;
  /** "First Last", without a title. */
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
  /** What can still be paid now: total - amountPaid while SENT, PARTIALLY_PAID or OVERDUE; 0.00 otherwise. */
  outstanding:       number;
  currency:          string;
  status:            InvoiceStatus;
  /** Date only, "YYYY-MM-DD". */
  dueDate:           string | null;
  issuedAt:          string | null;
  paidAt:            string | null;
  cancelReason:      string | null;
  notes:             string | null;
  createdAt:         string;
  updatedAt:         string | null;
}

/** Filters of the invoice lists. `status` is one status or several (sent as a comma-separated list). */
export interface InvoiceListFilters {
  status?:         InvoiceStatus | readonly InvoiceStatus[] | null;
  consultationId?: number | null;
  appointmentId?:  number | null;
}

/** Filters of the doctor's own list (`GET /api/doctors/billing/invoices`): `patientSystemId` is a plain filter there. */
export interface DoctorInvoiceListFilters extends InvoiceListFilters {
  patientSystemId?: string | null;
}

export interface InvoiceLineItemRequest {
  /** Not blank, at most 200 characters. */
  description: string;
  /** 1 to 100. */
  quantity:    number;
  /** 0.00 to 999999.99, at most 2 decimals. */
  unitPrice:   number;
}

/** `POST /api/billing/invoices`: one of the two ids is required; the patient and doctor come from the visit. */
export interface CreateInvoiceRequest {
  consultationId?: number | null;
  appointmentId?:  number | null;
  /** Absent or empty: one line is generated from the fee schedule. At most 50 items. */
  lineItems?:      InvoiceLineItemRequest[];
  discount?:       number | null;
  /** At most 2000 characters. */
  notes?:          string | null;
}

/** `PUT /api/billing/invoices/{id}`: full replacement of a DRAFT invoice's editable content. */
export interface UpdateInvoiceRequest {
  /** 1 to 50 items. */
  lineItems: InvoiceLineItemRequest[];
  discount?: number | null;
  notes?:    string | null;
}

export interface CancelInvoiceRequest {
  /** At most 500 characters. */
  reason?: string | null;
}

/**
 * Result of `POST /api/billing/invoices`. `created` is false when the server answered 200: an invoice already
 * existed for the visit (or the Idempotency-Key was replayed) and the request body was not applied.
 */
export interface CreateInvoiceResult {
  invoice: Invoice;
  created: boolean;
}

/** A completed consultation of the calling doctor that has no live invoice (`GET /api/doctors/billing/uninvoiced`). */
export interface UninvoicedConsultation {
  consultationId:  number;
  appointmentId:   number;
  appointmentType: AppointmentType;
  patientSystemId: string;
  patientName:     string | null;
  completedAt:     string | null;
  scheduledAt:     string;
  suggestedFee:    number;
  currency:        string;
}

// ─── Fees ─────────────────────────────────────────────────────────────────────

/** DOCTOR = the doctor's own override, DEFAULT = the clinic default, NONE = no active row (amount 0.00). */
export type FeeSource = 'DOCTOR' | 'DEFAULT' | 'NONE';

/** One row of the fee schedule. Fee responses are always three rows in the order VIDEO, IN_PERSON, PHONE. */
export interface Fee {
  appointmentType: AppointmentType;
  /** The fee that applies: doctor override, else clinic default, else 0.00. */
  amount:          number;
  currency:        string;
  source:          FeeSource;
  /** The clinic default for this type (0.00 when there is none). */
  defaultAmount:   number;
  doctorSystemId:  string | null;
}

export interface FeeUpdate {
  appointmentType: AppointmentType;
  /** 0.00 to 999999.99; null removes the doctor's override so the clinic default applies again. */
  amount:          number | null;
}

/** `PUT /api/doctors/billing/fees`: 1 to 3 items, each appointment type at most once. */
export interface UpdateFeesRequest {
  fees: FeeUpdate[];
}

// ─── Payments ─────────────────────────────────────────────────────────────────

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED' | 'CANCELLED';

export type PaymentMethodCode = 'CARD' | 'INSURANCE' | 'CASH' | 'MPESA' | 'BANK_TRANSFER';

/** The methods a patient or caregiver may pay with. */
export type OnlinePaymentMethodCode = 'CARD' | 'MPESA';

/** What the payer must do while a payment is PENDING. NONE once it is terminal. */
export type PaymentNextAction = 'NONE' | 'STK_PUSH' | 'AWAIT_CONFIRMATION' | 'REDIRECT';

export interface Payment {
  paymentId:           number;
  invoiceId:           number | null;
  invoiceNo:           string | null;
  patientSystemId:     string;
  patientName:         string | null;
  initiatedBySystemId: string | null;
  initiatedByName:     string | null;
  amount:              number;
  currency:            string;
  method:              PaymentMethodCode;
  paymentMethodId:     number | null;
  /** Masked label of the saved method used, for example "M-PESA •••• 4567". */
  paymentMethodLabel:  string | null;
  status:              PaymentStatus;
  nextAction:          PaymentNextAction;
  /** Only with nextAction REDIRECT. */
  redirectUrl:         string | null;
  /** Opaque label of the gateway or channel ("local", "MANUAL", "INSURANCE"). */
  gateway:             string | null;
  providerReference:   string | null;
  /** Confirmation code reported when the payment settled. */
  transactionId:       string | null;
  /** Masked: "**** 4567". */
  payerPhone:          string | null;
  failureReason:       string | null;
  refundedAmount:      number;
  refundableAmount:    number;
  refundReason:        string | null;
  paidAt:              string | null;
  createdAt:           string;
  updatedAt:           string | null;
}

/** `POST /api/billing/invoices/{invoiceId}/payments`. Card details are never sent. */
export interface InitiatePaymentRequest {
  method:           PaymentMethodCode;
  /** A saved method of the invoice's patient whose type equals `method`. */
  paymentMethodId?: number | null;
  /** Absent or null = the whole outstanding balance. */
  amount?:          number | null;
  /** Required for MPESA unless a saved M-PESA method supplies the phone. At most 20 characters. */
  payerPhone?:      string | null;
}

export type SimulatedPaymentOutcome = 'COMPLETED' | 'FAILED';

/** `GET /api/billing/config`. */
export interface BillingConfig {
  /** Name of the active gateway, "local" by default. */
  provider:          string;
  /** False when the active gateway is the local implementation: no money moves. */
  liveGateway:       boolean;
  supportedMethods:  PaymentMethodCode[];
  /** The methods a patient or caregiver may pay with; empty means paying online is not available. */
  onlineMethods:     OnlinePaymentMethodCode[];
  /** The currency of new invoices; an existing invoice keeps its own. */
  currency:          string;
  /** True only when `POST /api/billing/payments/{id}/simulate` is available. */
  simulationEnabled: boolean;
}

// ─── Saved payment methods ────────────────────────────────────────────────────

export type SavedPaymentMethodType = 'CARD' | 'MPESA' | 'INSURANCE';

/** A saved payment method. Masked for everyone: only `lastFour` and `displayLabel` describe the instrument. */
export interface PaymentMethod {
  methodId:     number;
  /** The owning patient's system id. */
  systemId:     string;
  type:         SavedPaymentMethodType;
  provider:     string | null;
  lastFour:     string;
  insurerName:  string | null;
  /** Server-built masked label, for example "Visa •••• 4242". */
  displayLabel: string;
  isDefault:    boolean;
  createdAt:    string;
}

/** `POST /api/billing/payment-methods` (patients only). Fields that do not belong to `type` are ignored. */
export interface CreatePaymentMethodRequest {
  type:          SavedPaymentMethodType;
  /** CARD only: the card brand, at most 100 characters. */
  provider?:     string | null;
  /** CARD: required, exactly 4 digits. */
  lastFour?:     string | null;
  /** MPESA: required, at most 20 characters. */
  mpesaPhone?:   string | null;
  /** INSURANCE: required, at most 100 characters. */
  insurerName?:  string | null;
  /** INSURANCE: required, 4 to 100 characters. */
  policyNumber?: string | null;
  /** INSURANCE only, at most 100 characters. */
  memberNumber?: string | null;
  makeDefault?:  boolean;
}

// ─── Insurance claims ─────────────────────────────────────────────────────────

export type ClaimStatus = 'SUBMITTED' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'PARTIAL' | 'APPEALED';

export interface ClaimDocument {
  /** Pass it to `GET /api/documents/{id}/download`. */
  documentId: number;
  title:      string;
}

export interface InsuranceClaim {
  claimId:             number;
  /** "CLM-YYYY-NNNNNN". */
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
  /** The invoice's currency. */
  currency:            string;
  /** What can still be settled on the invoice now. */
  invoiceOutstanding:  number;
  status:              ClaimStatus;
  /** Reason of a REJECTED decision, or the reviewer's note on a PARTIAL decision. */
  rejectionReason:     string | null;
  appealReason:        string | null;
  documentIds:         number[];
  documents:           ClaimDocument[];
  paymentId:           number | null;
  providerReference:   string | null;
  submittedBySystemId: string;
  reviewedBySystemId:  string | null;
  submittedAt:         string;
  resolvedAt:          string | null;
  updatedAt:           string | null;
}

/** `POST /api/billing/invoices/{invoiceId}/claims`. */
export interface SubmitClaimRequest {
  /** Not blank, at most 150 characters. */
  insuranceProvider: string;
  /** Not blank, at most 100 characters. */
  policyNumber:      string;
  /** At most 100 characters. */
  memberNumber?:     string | null;
  /** Absent or null = the invoice's whole outstanding balance. */
  claimAmount?:      number | null;
  /** Ids of documents uploaded for the same patient; at most 10. */
  documentIds?:      number[];
}

/** `POST /api/billing/claims/{claimId}/appeal`. */
export interface AppealClaimRequest {
  /** Not blank, at most 1000 characters. */
  reason: string;
}

export interface ClaimListFilters {
  status?:    ClaimStatus | readonly ClaimStatus[] | null;
  invoiceId?: number | null;
}

// ─── Screens ──────────────────────────────────────────────────────────────────

/**
 * Who is looking at a billing component. `patient` and `proxy` read one patient's issued invoices; `doctor` reads
 * the invoices they issued (drafts included) and has no access to claims; `admin` reads everything.
 */
export type BillingViewer = 'patient' | 'proxy' | 'doctor' | 'admin';
