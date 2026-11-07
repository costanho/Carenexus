// Calendar dates without a time (date of birth, prescription expiry, follow-up date, access end date).
// The backend sends these as "YYYY-MM-DD" (Java LocalDate). `new Date('YYYY-MM-DD')` reads that as UTC
// midnight and `new Date('YYYY-MM-DDT00:00:00')` as local midnight, so formatting, validating or comparing
// through either shifts the day depending on the device's time zone. These helpers only use the Y-M-D parts.

type Parts = { y: number; m: number; d: number };

/** The Y-M-D parts of "YYYY-MM-DD" (a longer ISO string is cut to its date), or null when it is not a real date. */
export function dateOnlyParts(value: string | null | undefined): Parts | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec((value ?? '').trim());
  if (!match) return null;
  const y = Number(match[1]), m = Number(match[2]), d = Number(match[3]);
  const utc = new Date(Date.UTC(y, m - 1, d));
  if (utc.getUTCFullYear() !== y || utc.getUTCMonth() !== m - 1 || utc.getUTCDate() !== d) return null;
  return { y, m, d };
}

/** True for an exact, real "YYYY-MM-DD" date (rejects 2026-02-30 and anything with a time). */
export function isValidDateOnly(value: string | null | undefined): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test((value ?? '').trim()) && dateOnlyParts(value) !== null;
}

/** Formats a calendar date the same on every device, e.g. "14 May 1990". Returns `fallback` when missing or invalid. */
export function formatDateOnly(
  value: string | null | undefined,
  options: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short', year: 'numeric' },
  fallback = '—',
): string {
  const p = dateOnlyParts(value);
  if (!p) return fallback;
  return new Intl.DateTimeFormat('en-GB', { ...options, timeZone: 'UTC' }).format(new Date(Date.UTC(p.y, p.m - 1, p.d)));
}

/** Today's date on this device as "YYYY-MM-DD". */
export function todayDateOnly(now = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Whole days from today (device calendar) to the given date: 0 = today, -1 = yesterday. Null when invalid. */
export function daysUntilDateOnly(value: string | null | undefined, now = new Date()): number | null {
  const p = dateOnlyParts(value);
  const t = dateOnlyParts(todayDateOnly(now));
  if (!p || !t) return null;
  return Math.round((Date.UTC(p.y, p.m - 1, p.d) - Date.UTC(t.y, t.m - 1, t.d)) / 86_400_000);
}

/** Age in whole years on today's date, counting the birthday itself. Null when the date is missing or invalid. */
export function ageFromDateOnly(dob: string | null | undefined, now = new Date()): number | null {
  const p = dateOnlyParts(dob);
  if (!p) return null;
  const m = now.getMonth() + 1, d = now.getDate();
  let age = now.getFullYear() - p.y;
  if (m < p.m || (m === p.m && d < p.d)) age -= 1;
  return age >= 0 ? age : null;
}
