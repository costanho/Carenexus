import { z } from 'zod';
import { formatMoney } from '@/lib/format/money';
import type {
  AppointmentType, CreatePaymentMethodRequest, FeeUpdate, InitiatePaymentRequest, InvoiceLineItemRequest,
  SubmitClaimRequest, UpdateFeesRequest, UpdateInvoiceRequest,
} from '@/lib/billing/types';

// Form schemas for the billing screens. Each mirrors the Bean Validation constraints of its request DTO
// (api/be-billing-*.md), so a form that passes here is not rejected by the server for its shape. Rules that
// depend on server state (the outstanding balance, an open claim) are still answered by the server.
//
// Amounts are edited as text and converted with `parseMoneyInput` when the request is built.

// ─── Money text ───────────────────────────────────────────────────────────────

const MONEY_TEXT = /^\d+(\.\d{1,2})?$/;
/** Commas used as thousands separators: groups of exactly three digits ("1,500", "12,345,678.50"). */
const THOUSANDS_GROUPED = /^\d{1,3}(,\d{3})+(\.\d{1,2})?$/;
/** One decimal comma, as typed on a phone set to a comma-decimal locale ("12,50", "1500,5"). */
const DECIMAL_COMMA = /^\d+,\d{1,2}$/;

/**
 * The typed amount in the form `MONEY_TEXT` checks, read the same way on every billing screen:
 *  - spaces are dropped ("1 500.00" is "1500.00");
 *  - commas are thousands separators only when every group after them has exactly three digits
 *    ("1,500.00" is "1500.00");
 *  - otherwise a single comma followed by one or two digits is a decimal comma, as in the vitals form
 *    (`lib/validation/health.ts`): "12,50" is "12.50", never 1250;
 *  - any other comma is left in, so the field shows "Enter an amount with at most 2 decimals" instead of
 *    guessing ("1.500,50", "1,50,000", "1,5,0").
 * "1,500" is always 1500: read as a decimal comma it would have three decimals, which no amount has.
 */
function moneyText(value: string | null | undefined): string {
  const text = (value ?? '').replace(/\s/g, '');
  if (THOUSANDS_GROUPED.test(text)) return text.replace(/,/g, '');
  if (DECIMAL_COMMA.test(text)) return text.replace(',', '.');
  return text;
}

/**
 * The number a money field holds, or null when it is blank or not an amount with at most two decimals.
 * `parseMoneyInput('1,500.5')` is 1500.5; `parseMoneyInput('12,50')` is 12.5; `parseMoneyInput('')` is null.
 */
export function parseMoneyInput(value: string | null | undefined): number | null {
  const text = moneyText(value);
  if (!text || !MONEY_TEXT.test(text)) return null;
  const amount = Number(text);
  return Number.isFinite(amount) ? amount : null;
}

/** The text to put into a money field for an amount from the API: "2500.00"; '' for null. */
export function moneyInputValue(amount: number | null | undefined): string {
  return typeof amount === 'number' && Number.isFinite(amount) ? amount.toFixed(2) : '';
}

interface MoneyRule {
  /** Blank is an error with this message; without it blank is accepted. */
  required?: string;
  /** Digits allowed before the decimal point (6 for fees and unit prices, 10 for payments, discounts, claims). */
  integerDigits: number;
  /** Smallest accepted amount, with the message shown below it. */
  min?: { value: number; message: string };
  /** Largest accepted amount, with the message shown above it. */
  max?: { value: number; message: string };
}

function moneyField(rule: MoneyRule) {
  return z.string().superRefine((raw, ctx) => {
    const text = moneyText(raw);
    if (!text) {
      if (rule.required) ctx.addIssue({ code: 'custom', message: rule.required });
      return;
    }
    if (!MONEY_TEXT.test(text)) {
      ctx.addIssue({ code: 'custom', message: 'Enter an amount with at most 2 decimals, for example 1500 or 1500.50' });
      return;
    }
    const whole = text.split('.')[0].replace(/^0+(?=\d)/, '');
    if (whole.length > rule.integerDigits) {
      ctx.addIssue({ code: 'custom', message: `Enter an amount with at most ${rule.integerDigits} digits before the decimal point` });
      return;
    }
    const amount = Number(text);
    if (rule.min && amount < rule.min.value) ctx.addIssue({ code: 'custom', message: rule.min.message });
    else if (rule.max && amount > rule.max.value) ctx.addIssue({ code: 'custom', message: rule.max.message });
  });
}

/** Optional text that, when filled in, is at most `max` characters after trimming. */
function optionalText(max: number, label: string) {
  return z.string().refine(v => v.trim().length <= max, `${label} must be at most ${max} characters`);
}

/** Required text: not blank, at most `max` characters after trimming. */
function requiredText(max: number, label: string, missing = `${label} is required`) {
  return z.string()
    .refine(v => v.trim().length > 0, missing)
    .refine(v => v.trim().length <= max, `${label} must be at most ${max} characters`);
}

/** Trimmed text, or null when blank (the API stores a blank optional field as null). */
function textOrNull(value: string | null | undefined): string | null {
  const text = (value ?? '').trim();
  return text ? text : null;
}

/** Backend `ValidationPatterns.PHONE`: optional +, 7 to 15 digits; spaces, dots, dashes and brackets allowed. */
export const BILLING_PHONE_PATTERN = /^\+?(?:[ .()-]*\d){7,15}[ .()-]*$/;
export const BILLING_PHONE_MAX_LENGTH = 20;

function isBillingPhone(value: string): boolean {
  const text = value.trim();
  return text.length <= BILLING_PHONE_MAX_LENGTH && BILLING_PHONE_PATTERN.test(text);
}

// ─── Fee schedule (PUT /api/doctors/billing/fees) ─────────────────────────────

export const FEE_TYPES: readonly AppointmentType[] = ['VIDEO', 'IN_PERSON', 'PHONE'];
export const FEE_MAX_AMOUNT = 999999.99;

/** A doctor's own fee for one appointment type. Blank means "no fee of my own: use the clinic default". */
const feeAmount = moneyField({
  integerDigits: 6,
  max: { value: FEE_MAX_AMOUNT, message: 'Enter an amount of at most 999,999.99' },
});

export const feeFormSchema = z.object({
  VIDEO:     feeAmount,
  IN_PERSON: feeAmount,
  PHONE:     feeAmount,
});

export type FeeFormValues = z.infer<typeof feeFormSchema>;

/** The request for the fee editor: every type is listed; a blank field removes the doctor's override. */
export function toUpdateFeesRequest(values: FeeFormValues): UpdateFeesRequest {
  const fees: FeeUpdate[] = FEE_TYPES.map(type => ({ appointmentType: type, amount: parseMoneyInput(values[type]) }));
  return { fees };
}

// ─── Invoice content (POST /api/billing/invoices, PUT /api/billing/invoices/{id}) ─────────────────────────────

export const INVOICE_MAX_LINE_ITEMS = 50;
export const INVOICE_DESCRIPTION_MAX_LENGTH = 200;
export const INVOICE_NOTES_MAX_LENGTH = 2000;
export const INVOICE_CANCEL_REASON_MAX_LENGTH = 500;

export const invoiceLineItemSchema = z.object({
  description: requiredText(INVOICE_DESCRIPTION_MAX_LENGTH, 'Description'),
  quantity:    z.string().refine(v => {
    const text = v.trim();
    if (!/^\d{1,3}$/.test(text)) return false;
    const quantity = Number(text);
    return quantity >= 1 && quantity <= 100;
  }, 'Enter a whole number from 1 to 100'),
  unitPrice:   moneyField({
    required: 'Enter the unit price',
    integerDigits: 6,
    max: { value: 999999.99, message: 'Enter a unit price of at most 999,999.99' },
  }),
});

export type InvoiceLineItemFormValues = z.infer<typeof invoiceLineItemSchema>;

/** Subtotal of the lines as typed, in whole cents; lines that are not valid yet count as zero. */
function subtotalCents(lines: readonly { quantity: string; unitPrice: string }[]): number {
  let total = 0;
  for (const line of lines) {
    const quantity = /^\d+$/.test(line.quantity.trim()) ? Number(line.quantity.trim()) : 0;
    const price = parseMoneyInput(line.unitPrice) ?? 0;
    total += Math.round(price * 100) * quantity;
  }
  return total;
}

/** The subtotal of an invoice form as it stands, for showing a running total next to the lines. */
export function invoiceFormSubtotal(lines: readonly { quantity: string; unitPrice: string }[]): number {
  return subtotalCents(lines) / 100;
}

export const invoiceFormSchema = z.object({
  lineItems: z.array(invoiceLineItemSchema)
    .min(1, 'Add at least one line')
    .max(INVOICE_MAX_LINE_ITEMS, `An invoice can have at most ${INVOICE_MAX_LINE_ITEMS} lines`),
  discount:  moneyField({ integerDigits: 10 }),
  notes:     optionalText(INVOICE_NOTES_MAX_LENGTH, 'The note'),
}).superRefine((values, ctx) => {
  const discount = parseMoneyInput(values.discount);
  if (discount !== null && Math.round(discount * 100) > subtotalCents(values.lineItems)) {
    ctx.addIssue({ code: 'custom', path: ['discount'], message: 'The discount cannot be more than the subtotal' });
  }
});

export type InvoiceFormValues = z.infer<typeof invoiceFormSchema>;

export function toInvoiceLineItems(lines: readonly InvoiceLineItemFormValues[]): InvoiceLineItemRequest[] {
  return lines.map(line => ({
    description: line.description.trim(),
    quantity:    Number(line.quantity.trim()),
    unitPrice:   parseMoneyInput(line.unitPrice) ?? 0,
  }));
}

/** The body for editing a draft (and, with a visit id added, for creating an invoice). */
export function toUpdateInvoiceRequest(values: InvoiceFormValues): UpdateInvoiceRequest {
  return {
    lineItems: toInvoiceLineItems(values.lineItems),
    discount:  parseMoneyInput(values.discount) ?? 0,
    notes:     textOrNull(values.notes),
  };
}

export const cancelInvoiceSchema = z.object({
  reason: optionalText(INVOICE_CANCEL_REASON_MAX_LENGTH, 'The reason'),
});

export type CancelInvoiceFormValues = z.infer<typeof cancelInvoiceSchema>;

// ─── Paying an invoice (POST /api/billing/invoices/{invoiceId}/payments) ──────

/** The methods a patient or caregiver may pay with; offer only those in `BillingConfig.onlineMethods`. */
export const ONLINE_PAYMENT_METHODS = ['CARD', 'MPESA'] as const;

/**
 * The Pay form. `outstanding` and `currency` come from the invoice on screen; an amount above the balance is
 * refused here with the same meaning as the server's 409. The saved method is kept as text ('' = none) so it can
 * sit in a select.
 */
export function paymentFormSchema(outstanding: number, currency?: string | null) {
  return z.object({
    method:          z.enum(ONLINE_PAYMENT_METHODS, { error: 'Choose how to pay' }),
    /** `methodId` of a saved payment method as text, or '' when the details are typed in. */
    paymentMethodId: z.string().refine(v => v === '' || /^[1-9]\d*$/.test(v), 'Choose a saved payment method'),
    /** Blank pays the whole outstanding balance. */
    amount:          moneyField({
      integerDigits: 10,
      min: { value: 0.01, message: 'Enter an amount greater than zero' },
      max: { value: outstanding, message: `You can pay at most ${formatMoney(outstanding, currency)}, the outstanding balance` },
    }),
    payerPhone:      z.string().refine(v => v.trim() === '' || isBillingPhone(v), 'Enter a valid phone number'),
  }).superRefine((values, ctx) => {
    if (values.method === 'MPESA' && values.paymentMethodId === '' && values.payerPhone.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['payerPhone'], message: 'Enter the M-PESA phone number to send the payment prompt to' });
    }
  });
}

export type PaymentFormValues = z.infer<ReturnType<typeof paymentFormSchema>>;

export function toInitiatePaymentRequest(values: PaymentFormValues): InitiatePaymentRequest {
  const request: InitiatePaymentRequest = { method: values.method };
  if (values.paymentMethodId !== '') request.paymentMethodId = Number(values.paymentMethodId);
  const amount = parseMoneyInput(values.amount);
  if (amount !== null) request.amount = amount;
  // The phone only means something for M-PESA; with a saved M-PESA method the server reads it from the method.
  const phone = textOrNull(values.payerPhone);
  if (values.method === 'MPESA' && phone) request.payerPhone = phone;
  return request;
}

// ─── Saved payment methods (POST /api/billing/payment-methods) ────────────────

export const SAVED_METHOD_TYPES = ['CARD', 'MPESA', 'INSURANCE'] as const;

export const paymentMethodFormSchema = z.object({
  type:         z.enum(SAVED_METHOD_TYPES, { error: 'Choose the type of payment method' }),
  /** CARD: the card brand, for example Visa. */
  provider:     optionalText(100, 'The card brand'),
  /** CARD: the last four digits. The full card number is never typed in or sent. */
  lastFour:     z.string(),
  mpesaPhone:   z.string(),
  insurerName:  optionalText(100, 'The insurer name'),
  policyNumber: z.string(),
  memberNumber: optionalText(100, 'The member number'),
  makeDefault:  z.boolean(),
}).superRefine((values, ctx) => {
  if (values.type === 'CARD') {
    if (!/^\d{4}$/.test(values.lastFour.trim())) {
      ctx.addIssue({ code: 'custom', path: ['lastFour'], message: 'Enter the last 4 digits of the card' });
    }
  } else if (values.type === 'MPESA') {
    if (values.mpesaPhone.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['mpesaPhone'], message: 'Enter the M-PESA phone number' });
    } else if (!isBillingPhone(values.mpesaPhone)) {
      ctx.addIssue({ code: 'custom', path: ['mpesaPhone'], message: 'Enter a valid phone number' });
    }
  } else {
    if (values.insurerName.trim() === '') {
      ctx.addIssue({ code: 'custom', path: ['insurerName'], message: 'Enter the name of the insurer' });
    }
    const policy = values.policyNumber.trim();
    if (policy.length < 4 || policy.length > 100) {
      ctx.addIssue({ code: 'custom', path: ['policyNumber'], message: 'Enter the policy number (4 to 100 characters)' });
    }
  }
});

export type PaymentMethodFormValues = z.infer<typeof paymentMethodFormSchema>;

/** Only the fields that belong to the chosen type are sent; the server ignores the others anyway. */
export function toCreatePaymentMethodRequest(values: PaymentMethodFormValues): CreatePaymentMethodRequest {
  switch (values.type) {
    case 'CARD':
      return { type: 'CARD', provider: textOrNull(values.provider), lastFour: values.lastFour.trim(), makeDefault: values.makeDefault };
    case 'MPESA':
      return { type: 'MPESA', mpesaPhone: values.mpesaPhone.trim(), makeDefault: values.makeDefault };
    default:
      return {
        type:         'INSURANCE',
        insurerName:  values.insurerName.trim(),
        policyNumber: values.policyNumber.trim(),
        memberNumber: textOrNull(values.memberNumber),
        makeDefault:  values.makeDefault,
      };
  }
}

// ─── Insurance claims (POST /api/billing/invoices/{invoiceId}/claims, POST /api/billing/claims/{id}/appeal) ────

export const CLAIM_MAX_DOCUMENTS = 10;
export const CLAIM_APPEAL_REASON_MAX_LENGTH = 1000;

/**
 * The claim form. `outstanding` and `currency` come from the invoice on screen; a claim above the balance is
 * refused here with the same meaning as the server's 409.
 */
export function claimFormSchema(outstanding: number, currency?: string | null) {
  return z.object({
    insuranceProvider: requiredText(150, 'The insurer name', 'Enter the name of the insurer'),
    policyNumber:      requiredText(100, 'The policy number', 'Enter the policy number'),
    memberNumber:      optionalText(100, 'The member number'),
    /** Blank claims the whole outstanding balance. */
    claimAmount:       moneyField({
      integerDigits: 10,
      min: { value: 0.01, message: 'Enter an amount greater than zero' },
      max: { value: outstanding, message: `You can claim at most ${formatMoney(outstanding, currency)}, the outstanding balance` },
    }),
    /** Ids of documents already uploaded for this patient. */
    documentIds:       z.array(z.number().int().positive())
      .max(CLAIM_MAX_DOCUMENTS, `You can attach at most ${CLAIM_MAX_DOCUMENTS} documents`),
  });
}

export type ClaimFormValues = z.infer<ReturnType<typeof claimFormSchema>>;

export function toSubmitClaimRequest(values: ClaimFormValues): SubmitClaimRequest {
  const request: SubmitClaimRequest = {
    insuranceProvider: values.insuranceProvider.trim(),
    policyNumber:      values.policyNumber.trim(),
    memberNumber:      textOrNull(values.memberNumber),
    documentIds:       Array.from(new Set(values.documentIds)),
  };
  const amount = parseMoneyInput(values.claimAmount);
  if (amount !== null) request.claimAmount = amount;
  return request;
}

export const claimAppealSchema = z.object({
  reason: requiredText(CLAIM_APPEAL_REASON_MAX_LENGTH, 'The reason', 'Explain why the decision should be reviewed'),
});

export type ClaimAppealFormValues = z.infer<typeof claimAppealSchema>;
