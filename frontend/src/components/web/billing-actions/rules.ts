import type { ApiError } from '@/lib/api/errors';
import { isPayableInvoice } from '@/lib/billing/format';
import type { Invoice, InsuranceClaim, Payment } from '@/lib/billing/types';

// Small rules shared by the billing actions (Pay, Submit claim, Appeal) of the patient and caregiver screens.

/** Whole cents of an amount, so sums do not pick up binary rounding noise. */
function cents(amount: number | null | undefined): number {
  return typeof amount === 'number' && Number.isFinite(amount) ? Math.round(amount * 100) : 0;
}

/** The sum of the invoice's payments that are still waiting for the gateway: that amount is reserved. */
export function reservedAmount(payments: readonly Payment[]): number {
  return payments.reduce((sum, p) => sum + (p.status === 'PENDING' ? cents(p.amount) : 0), 0) / 100;
}

/** True when a claim can be submitted for the invoice: it has a balance and no claim of it is still open. */
export function canSubmitClaimFor(
  invoice: Pick<Invoice, 'status' | 'outstanding'>,
  claimsKnown: boolean,
  openClaim: InsuranceClaim | null,
): boolean {
  return claimsKnown && openClaim === null && isPayableInvoice(invoice);
}

/** The sentence to show for a request that was refused because the same Idempotency-Key is still being processed. */
export function inProgressMessage(error: ApiError, what: string): string | null {
  return error.code === 'IDEMPOTENCY_IN_PROGRESS' ? `${what} Wait a moment, then try again.` : null;
}
