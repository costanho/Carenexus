import { CLINIC_ZONE_LABEL, formatClinicDateTime } from '@/lib/time/clinic';
import { dateOnlyParts, formatDateOnly } from '@/lib/ui/date-only';
import {
  REMINDER_FREQUENCIES, REMINDER_TYPES,
  type Reminder, type ReminderFrequency, type ReminderSourceType, type ReminderStatus, type ReminderType,
} from './types';

// Display text for reminders. Every time shown is clinic time: `reminderTime`, `nextDueAt` and `lastSentAt`
// are clinic wall-clock values and are never converted through the device time zone. `nextDueAt` comes from the
// server; nothing here computes when a reminder fires.

export const REMINDER_TYPE_LABELS: Record<ReminderType, string> = {
  MEDICATION:   'Medication',
  APPOINTMENT:  'Appointment',
  LAB_FOLLOWUP: 'Lab follow-up',
  CARE_PLAN:    'Care plan',
  CHECK_IN:     'Check-in',
  VITAL_SIGN:   'Vital signs',
};

export const REMINDER_FREQUENCY_LABELS: Record<ReminderFrequency, string> = {
  ONCE:    'Once',
  DAILY:   'Every day',
  WEEKLY:  'Every week',
  MONTHLY: 'Every month',
};

/**
 * Which notification setting of the patient decides whether a reminder of this type is delivered (contract
 * api/be-reminders.md 3.1). Shown under the type field so a saved reminder is never silently without effect.
 */
export const REMINDER_TYPE_DELIVERY_HINTS: Record<ReminderType, string> = {
  MEDICATION:   "Sent only while medication reminders are switched on in the patient's notification settings.",
  APPOINTMENT:  "Sent only while appointment reminders are switched on in the patient's notification settings.",
  LAB_FOLLOWUP: "Sent only while lab result alerts are switched on in the patient's notification settings.",
  CARE_PLAN:    "Sent only while care plan updates are switched on in the patient's notification settings. That setting is off until the patient switches it on.",
  CHECK_IN:     'Always sent: this type cannot be switched off in notification settings.',
  VITAL_SIGN:   'Always sent: this type cannot be switched off in notification settings.',
};

const SOURCE_LABELS: Record<ReminderSourceType, string> = {
  MANUAL:       'Manual',
  CONSULTATION: 'Follow-up',
  CARE_PLAN:    'Care plan',
};

const STATUS_LABELS: Record<ReminderStatus, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  ENDED:  'Ended',
};

/** "LAB_FOLLOWUP" becomes "Lab followup": only used for a value this build does not know. */
function humanise(value: string | null | undefined): string {
  const words = (value ?? '').trim().replace(/[\s_-]+/g, ' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : '';
}

export function isReminderType(value: unknown): value is ReminderType {
  return typeof value === 'string' && (REMINDER_TYPES as readonly string[]).includes(value);
}

export function isReminderFrequency(value: unknown): value is ReminderFrequency {
  return typeof value === 'string' && (REMINDER_FREQUENCIES as readonly string[]).includes(value);
}

export function reminderTypeLabel(type: string | null | undefined): string {
  return isReminderType(type) ? REMINDER_TYPE_LABELS[type] : humanise(type) || 'Reminder';
}

/** The server's `sourceLabel` ("Manual", "Follow-up", "Care plan"), with the same words as a fallback. */
export function reminderSourceLabel(reminder: Pick<Reminder, 'sourceLabel' | 'sourceType'>): string {
  return reminder.sourceLabel?.trim() || SOURCE_LABELS[reminder.sourceType] || humanise(reminder.sourceType) || 'Automatic';
}

export function reminderStatusLabel(status: string | null | undefined): string {
  return STATUS_LABELS[status as ReminderStatus] ?? (humanise(status) || 'Unknown');
}

/** "07:30 CAT" for a clinic time of day "HH:mm" (seconds, if any, are dropped). */
export function formatReminderTime(time: string | null | undefined): string {
  const match = /^(\d{2}):(\d{2})/.exec((time ?? '').trim());
  return match ? `${match[1]}:${match[2]} ${CLINIC_ZONE_LABEL}` : '';
}

/** "11 Nov 2026" for a date-only value. */
export function formatReminderDate(date: string | null | undefined, fallback = ''): string {
  return formatDateOnly(date, undefined, fallback);
}

/**
 * How often the reminder repeats, in words. Weekly and monthly reminders repeat from the start date, so its
 * weekday or day of the month is named: "Every week on Wednesday", "Every month on day 11".
 */
export function reminderFrequencyLabel(reminder: Pick<Reminder, 'frequency' | 'startDate'>): string {
  const base = isReminderFrequency(reminder.frequency)
    ? REMINDER_FREQUENCY_LABELS[reminder.frequency]
    : humanise(reminder.frequency) || 'Not set';
  if (reminder.frequency === 'WEEKLY') {
    const weekday = formatDateOnly(reminder.startDate, { weekday: 'long' }, '');
    return weekday ? `${base} on ${weekday}` : base;
  }
  if (reminder.frequency === 'MONTHLY') {
    const parts = dateOnlyParts(reminder.startDate);
    if (!parts) return base;
    // Counted from the start date: a day that a shorter month does not have falls on that month's last day.
    return parts.d > 28 ? `${base} on day ${parts.d} (or the last day of a shorter month)` : `${base} on day ${parts.d}`;
  }
  return base;
}

/** "Wed 11 Nov 2026, 07:30 CAT" for the next time the reminder fires, or what its state means when it will not. */
export function reminderNextDueLabel(reminder: Pick<Reminder, 'nextDueAt' | 'status'>): string {
  const due = formatClinicDateTime(reminder.nextDueAt);
  if (due) return due;
  if (reminder.status === 'PAUSED') return 'Not scheduled while paused';
  if (reminder.status === 'ENDED') return 'No upcoming time';
  return 'Not scheduled';
}
