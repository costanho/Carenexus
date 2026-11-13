import { CLINIC_TIME_ZONE } from '@/lib/calls/format';

// Clinic time (CONTRACTS 12.4). Appointment, schedule and grant-expiry times are clinic wall-clock values
// (Java LocalDateTime, "YYYY-MM-DDTHH:mm:ss", no offset). They are compared with the clinic's current wall
// clock, never with the device clock: a device in another time zone would otherwise treat a 10:00 appointment
// as starting at 10:00 its own time.

export { CLINIC_TIME_ZONE };

/** Short label shown after clinic times. The clinic zone (Africa/Harare) has no daylight saving. */
export const CLINIC_ZONE_LABEL = 'CAT';

const WALL_CLOCK = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?/;
const HAS_OFFSET = /(Z|[+-]\d{2}:?\d{2})$/i;

/** The clinic's current wall-clock time as "YYYY-MM-DDTHH:mm:ss". */
export function clinicNowLocal(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: CLINIC_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}:${get('second')}`;
}

/** Today's date at the clinic as "YYYY-MM-DD". */
export function clinicToday(now: Date = new Date()): string {
  return clinicNowLocal(now).slice(0, 10);
}

/**
 * Converts a value to a clinic wall-clock string "YYYY-MM-DDTHH:mm:ss": an instant with an offset is moved into
 * the clinic zone, an offset-less value is already clinic time. Null when the value is not a date-time.
 */
export function toClinicLocal(value: string | null | undefined): string | null {
  const raw = (value ?? '').trim();
  if (!raw) return null;
  if (HAS_OFFSET.test(raw)) {
    const at = new Date(raw);
    return Number.isNaN(at.getTime()) ? null : clinicNowLocal(at);
  }
  const m = WALL_CLOCK.exec(raw);
  return m ? `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6] ?? '00'}` : null;
}

/** True when the value (clinic wall clock, or an instant with an offset) is later than the clinic's current time. */
export function isAfterClinicNow(local: string): boolean {
  const clinic = toClinicLocal(local);
  if (clinic) return clinic > clinicNowLocal();
  const t = new Date(local).getTime();
  return !Number.isNaN(t) && t > Date.now();
}

/** True when the value's clinic calendar day is the clinic's today. */
export function isClinicToday(value: string | null | undefined): boolean {
  const clinic = toClinicLocal(value);
  return !!clinic && clinic.slice(0, 10) === clinicToday();
}

function partsOf(value: string | null | undefined): { date: Date; hh: string; mm: string } | null {
  const clinic = toClinicLocal(value);
  const m = clinic ? WALL_CLOCK.exec(clinic) : null;
  if (!m) return null;
  // A UTC date with the same fields, used only to get weekday and month names without shifting zones.
  const date = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return Number.isNaN(date.getTime()) ? null : { date, hh: m[4], mm: m[5] };
}

/** "Fri 20 Nov 2026" in clinic time. `fallback` when the value is missing or not a date-time. */
export function formatClinicDate(value: string | null | undefined, fallback = ''): string {
  const p = partsOf(value);
  if (!p) return fallback;
  // Assembled from parts: the separators Intl puts between them differ between browsers.
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric',
  }).formatToParts(p.date);
  const get = (t: string) => parts.find(x => x.type === t)?.value ?? '';
  return `${get('weekday')} ${get('day')} ${get('month')} ${get('year')}`;
}

/** "10:15 CAT" in clinic time. `fallback` when the value is missing or not a date-time. */
export function formatClinicTime(value: string | null | undefined, fallback = ''): string {
  const p = partsOf(value);
  return p ? `${p.hh}:${p.mm} ${CLINIC_ZONE_LABEL}` : fallback;
}

/**
 * "Fri 20 Nov 2026, 10:15 CAT" in clinic time, for a clinic wall-clock value ("2026-11-20T10:15:00") or an
 * instant with an offset ("2026-11-20T08:15:00Z"). `fallback` when the value is missing or not a date-time.
 */
export function formatClinicDateTime(value: string | null | undefined, fallback = ''): string {
  const p = partsOf(value);
  return p ? `${formatClinicDate(value)}, ${p.hh}:${p.mm} ${CLINIC_ZONE_LABEL}` : fallback;
}
