import { z } from 'zod';

// Quiet hours: a daily window in which email, SMS and push messages are held back. The rules mirror the server's
// (be-profile-settings D12): both times are "HH:mm", and while quiet hours are on they must be present and differ.

/** Times offered the first time quiet hours are switched on. */
export const QUIET_HOURS_DEFAULT_START = '22:00';
export const QUIET_HOURS_DEFAULT_END   = '07:00';

/** 24-hour "HH:mm", zero padded: the only form the server accepts. */
export const TIME_OF_DAY_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

export const QUIET_HOURS_MESSAGES = {
  startRequired: 'Enter a start time',
  endRequired:   'Enter an end time',
  format:        'Use 24-hour time as HH:mm',
  same:          'Quiet hours start and end must be different',
} as const;

function timeOfDay(required: string) {
  return z.string().trim().min(1, required).regex(TIME_OF_DAY_PATTERN, QUIET_HOURS_MESSAGES.format);
}

/** The two time fields of the quiet-hours form. */
export const quietHoursSchema = z
  .object({
    quietHoursStart: timeOfDay(QUIET_HOURS_MESSAGES.startRequired),
    quietHoursEnd:   timeOfDay(QUIET_HOURS_MESSAGES.endRequired),
  })
  .refine(v => v.quietHoursStart !== v.quietHoursEnd, { path: ['quietHoursEnd'], message: QUIET_HOURS_MESSAGES.same });

export type QuietHoursFormData = z.infer<typeof quietHoursSchema>;

export function isTimeOfDay(value: string | null | undefined): value is string {
  return typeof value === 'string' && TIME_OF_DAY_PATTERN.test(value);
}

/** True when the pair can be used as a quiet window: two valid times that differ. */
export function isQuietWindow(start: string | null | undefined, end: string | null | undefined): boolean {
  return isTimeOfDay(start) && isTimeOfDay(end) && start !== end;
}

/**
 * "22:00 to 07:00 the next day" or "13:00 to 15:00". The window starts at the first time and ends just before
 * the second; a start later than the end runs past midnight. '' when the pair is not a window.
 */
export function describeQuietWindow(start: string | null | undefined, end: string | null | undefined): string {
  if (!isQuietWindow(start, end)) return '';
  return start! > end! ? `${start} to ${end} the next day` : `${start} to ${end}`;
}
