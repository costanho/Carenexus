// Clinic-time formatting for call windows (CONTRACT.md 0.6: Africa/Harare + "CAT").

export const CLINIC_TIME_ZONE = 'Africa/Harare';

function valid(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "10:15 CAT" for an ISO instant. */
export function formatClinicTime(iso: string | null | undefined): string {
  const d = valid(iso);
  if (!d) return '';
  const t = new Intl.DateTimeFormat('en-GB', {
    timeZone: CLINIC_TIME_ZONE, hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(d);
  return `${t} CAT`;
}

/** "Fri 20 Nov, 10:15 CAT" for an ISO instant. */
export function formatClinicDateTime(iso: string | null | undefined): string {
  const d = valid(iso);
  if (!d) return '';
  const date = new Intl.DateTimeFormat('en-GB', {
    timeZone: CLINIC_TIME_ZONE, weekday: 'short', day: 'numeric', month: 'short',
  }).format(d);
  return `${date}, ${formatClinicTime(iso)}`;
}

/** Formats a stored clinic wall-clock value ("2026-11-20T10:30:00", no offset) without shifting zones. */
export function formatClinicWallClock(local: string | null | undefined): string {
  if (!local) return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local);
  if (!m) return local;
  const [, y, mo, d, h, mi] = m;
  // Build a UTC date with the same fields only to get localized month/weekday names.
  const asUtc = new Date(Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi)));
  const date = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  }).format(asUtc);
  return `${date}, ${h}:${mi} CAT`;
}
