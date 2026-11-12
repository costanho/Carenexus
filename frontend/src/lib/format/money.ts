// Money formatting (CONTRACTS 12.3). The API sends amounts as JSON numbers with two decimals and the currency
// as an ISO-4217 code; every web screen shows them through this one function.

export const DEFAULT_CURRENCY = 'KES';

/**
 * "KES 1,500.00": the currency code, then the amount with thousands separators and exactly two decimals.
 * A negative amount reads "-KES 50.00". Returns `fallback` (empty by default) when the amount is missing or
 * not a number, so a caller can show its own placeholder.
 */
export function formatMoney(
  amount: number | string | null | undefined,
  currency: string | null | undefined = DEFAULT_CURRENCY,
  fallback = '',
): string {
  if (amount === null || amount === undefined || amount === '') return fallback;
  const value = typeof amount === 'number' ? amount : Number(amount);
  if (!Number.isFinite(value)) return fallback;

  const code = (currency ?? '').trim().toUpperCase() || DEFAULT_CURRENCY;
  // Round half away from zero on the cents, as the API does (HALF_UP), before splitting the digits.
  const cents = Math.round(Math.abs(value) * 100 + 1e-7);
  const whole = Math.floor(cents / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const fraction = String(cents % 100).padStart(2, '0');
  const sign = value < 0 && cents !== 0 ? '-' : '';
  return `${sign}${code} ${whole}.${fraction}`;
}
