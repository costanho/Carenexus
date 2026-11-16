import type { ApiError } from '@/lib/api/errors';
import { CLINIC_TIME_ZONE, clinicNowLocal, clinicToday, toClinicLocal } from '@/lib/time/clinic';
import type { AppointmentStatus, AppointmentType, BookingRules } from './types';

// Booking rules that the web applies before asking the server: which days a calendar offers, when an
// appointment counts as over, and how a refused booking is handled. Everything is clinic wall-clock time
// ("yyyy-MM-dd" and "yyyy-MM-ddTHH:mm:ss" strings compare in time order); the device clock is never used.

/**
 * Used until GET /api/appointments/booking-rules answers, and when it fails. They match the server defaults;
 * the server still checks every booking, so a clinic with other settings only gets its message a step later.
 */
export const DEFAULT_BOOKING_RULES: BookingRules = {
  timeZone:               CLINIC_TIME_ZONE,
  openHour:               8,
  closeHour:              18,
  allowWeekends:          true,
  slotGranularityMinutes: 5,
  minDurationMinutes:     5,
  maxDurationMinutes:     240,
  maxAdvanceDays:         365,
  videoCallsAvailable:    true,
};

export const APPOINTMENT_TYPE_LABEL: Record<AppointmentType, string> = {
  IN_PERSON: 'In Person',
  VIDEO:     'Video Call',
  PHONE:     'Phone Call',
};

/** Appointment lengths offered by the booking form, in minutes. */
const DURATION_CHOICES = [15, 30, 45, 60] as const;

/** The lengths the clinic's rules allow (range and step), never empty. */
export function durationOptions(rules: BookingRules): number[] {
  const step = rules.slotGranularityMinutes > 0 ? rules.slotGranularityMinutes : 5;
  const allowed = DURATION_CHOICES.filter(
    d => d >= rules.minDurationMinutes && d <= rules.maxDurationMinutes && d % step === 0,
  );
  return allowed.length > 0 ? allowed : [30];
}

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

function utcDate(date: string): Date | null {
  const m = DATE_ONLY.exec(date);
  if (!m) return null;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return Number.isNaN(d.getTime()) ? null : d;
}

/** "yyyy-MM-dd" plus a number of days. Returns the input when it is not a date. */
export function addDays(date: string, days: number): string {
  const d = utcDate(date);
  if (!d) return date;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** ISO weekday of "yyyy-MM-dd": 1 = Monday ... 7 = Sunday. 0 when the value is not a date. */
export function isoWeekday(date: string): number {
  const d = utcDate(date);
  if (!d) return 0;
  const day = d.getUTCDay();
  return day === 0 ? 7 : day;
}

/** "Friday, November 20, 2026" for "2026-11-20": the same on every device. */
export function formatLongDate(date: string): string {
  const d = utcDate(date);
  if (!d) return '';
  return d.toLocaleDateString('en-US', { timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
}

/** The first and last clinic day a patient-side booking may fall on. */
export function bookingWindow(rules: BookingRules, today: string = clinicToday()): { firstDay: string; lastDay: string } {
  const horizon = Number.isFinite(rules.maxAdvanceDays) && rules.maxAdvanceDays > 0 ? Math.floor(rules.maxAdvanceDays) : 0;
  return { firstDay: today, lastDay: addDays(today, horizon) };
}

/**
 * Why a clinic day cannot be chosen, or null when it can: already past, beyond the booking horizon, or a
 * weekend while the clinic is closed on weekends.
 */
export function dayClosedReason(date: string, rules: BookingRules, today: string = clinicToday()): string | null {
  const { firstDay, lastDay } = bookingWindow(rules, today);
  if (date < firstDay) return 'This day has passed';
  if (date > lastDay) return `Bookings open at most ${rules.maxAdvanceDays} days ahead`;
  if (!rules.allowWeekends && isoWeekday(date) >= 6) return 'The clinic is closed on weekends';
  return null;
}

/** "09:30" from a slot or appointment start ("2026-11-12T09:30:00"), with no time-zone conversion. */
export function slotTime(start: string): string {
  return start.slice(11, 16);
}

/** The clinic wall-clock end of an appointment, or null when the start is not a date-time. */
export function appointmentEnd(scheduledAt: string | null | undefined, durationMinutes: number): string | null {
  const start = toClinicLocal(scheduledAt);
  if (!start) return null;
  const [date, time] = start.split('T');
  const [h, m, s] = time.split(':').map(Number);
  const total = h * 60 + m + (Number.isFinite(durationMinutes) && durationMinutes > 0 ? durationMinutes : 0);
  const day = addDays(date, Math.floor(total / 1440));
  const rest = total % 1440;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${day}T${pad(Math.floor(rest / 60))}:${pad(rest % 60)}:${pad(s || 0)}`;
}

/** SCHEDULED and RESCHEDULED appointments still occupy the calendar; the other three statuses are final. */
export function isActiveStatus(status: AppointmentStatus | string | null | undefined): boolean {
  return status === 'SCHEDULED' || status === 'RESCHEDULED';
}

/**
 * True for an appointment that is still SCHEDULED or RESCHEDULED although its end has passed in clinic time:
 * the doctor has not recorded an outcome yet. Such an appointment is shown as "Awaiting outcome" and can no
 * longer be rescheduled or cancelled by the patient side.
 */
export function isAwaitingOutcome(
  status: AppointmentStatus | string | null | undefined,
  scheduledAt: string | null | undefined,
  durationMinutes: number,
  now: string = clinicNowLocal(),
): boolean {
  if (!isActiveStatus(status)) return false;
  const end = appointmentEnd(scheduledAt, durationMinutes);
  return !!end && end <= now;
}

/** True once the start time has been reached in clinic time. */
export function hasStarted(scheduledAt: string | null | undefined, now: string = clinicNowLocal()): boolean {
  const start = toClinicLocal(scheduledAt);
  return !!start && start <= now;
}

/** How a refused booking or reschedule is handled (the web branches on status and code, never on the text). */
export type BookingFailureKind =
  /** The time is taken or the doctor is not available then: show the message by the times and reload them. */
  | 'SLOT_UNAVAILABLE'
  /** Video is switched off at the clinic: reload the booking rules and hide Video. */
  | 'VIDEO_UNAVAILABLE'
  /** A phone appointment needs a contact number: show the message on that field. */
  | 'CONTACT_PHONE_REQUIRED'
  /** The same request is still being processed: wait and send it again with the same key. */
  | 'IN_PROGRESS'
  | 'OTHER';

export function bookingFailureKind(error: Pick<ApiError, 'status' | 'code'>): BookingFailureKind {
  if (error.code === 'VIDEO_UNAVAILABLE') return 'VIDEO_UNAVAILABLE';
  if (error.code === 'IDEMPOTENCY_IN_PROGRESS') return 'IN_PROGRESS';
  if (error.code === 'CONTACT_PHONE_REQUIRED') return 'CONTACT_PHONE_REQUIRED';
  if (error.code === 'DOCTOR_UNAVAILABLE') return 'SLOT_UNAVAILABLE';
  // A 409 without a code is a slot conflict (the doctor's or the patient's diary).
  if (error.status === 409 && !error.code) return 'SLOT_UNAVAILABLE';
  return 'OTHER';
}
