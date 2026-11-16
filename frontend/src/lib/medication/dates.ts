import { clinicToday } from '@/lib/time/clinic';
import { dateOnlyParts, formatDateOnly, isValidDateOnly } from '@/lib/ui/date-only';

// Calendar-day arithmetic for the schedule's date switcher. Days are clinic days ("yyyy-MM-dd"); they are
// moved with their Y-M-D parts only, never through the device's time zone.

/** The day `days` after (or before, when negative) a "yyyy-MM-dd" date. Returns the input when it is not a date. */
export function addDays(date: string, days: number): string {
  const p = dateOnlyParts(date);
  if (!p) return date;
  const moved = new Date(Date.UTC(p.y, p.m - 1, p.d + days));
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${moved.getUTCFullYear()}-${pad(moved.getUTCMonth() + 1)}-${pad(moved.getUTCDate())}`;
}

/**
 * The latest day that can hold doses: the clinic's tomorrow. The server creates doses up to and including
 * tomorrow, so later days are always empty and are not offered.
 */
export function latestScheduleDate(today: string = clinicToday()): string {
  return addDays(today, 1);
}

/**
 * The day the schedule opens on for a `date` URL value: that day when it is a real date, capped at the
 * clinic's tomorrow; the clinic's today when the value is missing or not a date.
 */
export function normaliseScheduleDate(value: string | null | undefined, today: string = clinicToday()): string {
  const raw = (value ?? '').trim();
  if (!isValidDateOnly(raw)) return today;
  const latest = latestScheduleDate(today);
  return raw > latest ? latest : raw;
}

/** "Today", "Yesterday" or "Tomorrow" for the days around the clinic's today, otherwise null. */
export function relativeDayName(date: string, today: string = clinicToday()): 'Today' | 'Yesterday' | 'Tomorrow' | null {
  if (date === today) return 'Today';
  if (date === addDays(today, -1)) return 'Yesterday';
  if (date === addDays(today, 1)) return 'Tomorrow';
  return null;
}

/** "Tue 10 Nov 2026" for a "yyyy-MM-dd" date. */
export function formatScheduleDate(date: string): string {
  return formatDateOnly(date, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }, date)
    .replace(/,/g, '');
}

/** "Today, Tue 10 Nov 2026", or just the date for days that have no relative name. */
export function scheduleDayLabel(date: string, today: string = clinicToday()): string {
  const name = relativeDayName(date, today);
  const formatted = formatScheduleDate(date);
  return name ? `${name}, ${formatted}` : formatted;
}

/** Heading of the schedule for a day: "Today's schedule", "Yesterday's schedule", "Schedule for Tue 10 Nov 2026". */
export function scheduleHeading(date: string, today: string = clinicToday()): string {
  const name = relativeDayName(date, today);
  return name ? `${name}'s schedule` : `Schedule for ${formatScheduleDate(date)}`;
}
