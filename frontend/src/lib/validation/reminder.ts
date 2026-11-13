import { z } from 'zod';
import { isReminderFrequency, isReminderType } from '@/lib/reminders/format';
import type {
  CreateReminderInput, Reminder, ReminderFrequency, ReminderType, UpdateReminderInput,
} from '@/lib/reminders/types';
import { clinicNowLocal, clinicToday } from '@/lib/time/clinic';
import { isValidDateOnly } from '@/lib/ui/date-only';

// The reminder form, mirroring the backend rules of POST and PATCH /api/reminders (contract api/be-reminders.md
// 2.2, 2.3 and 3.6): every field but the end date is required, title at most 80 and message at most 400
// characters, time as HH:mm (24-hour clinic time), end date not before the start date and unused for a
// reminder that fires once. The server decides in the end; this only saves a round trip.

export const REMINDER_TITLE_MAX = 80;
export const REMINDER_MESSAGE_MAX = 400;

/** Exactly "HH:mm", 24-hour: the pattern the backend applies to `reminderTime`. */
export const REMINDER_TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

// `type` and `frequency` stay plain strings in the form ('' until chosen); the explicit `boolean` return type
// keeps the refinements from narrowing them, so the form's input and output types are the same.
export const reminderSchema = z
  .object({
    type: z.string().refine((v): boolean => isReminderType(v), 'Choose a reminder type'),
    title: z.string().trim()
      .min(1, 'Title is required')
      .max(REMINDER_TITLE_MAX, `Title must be at most ${REMINDER_TITLE_MAX} characters`),
    message: z.string().trim()
      .min(1, 'Message is required')
      .max(REMINDER_MESSAGE_MAX, `Message must be at most ${REMINDER_MESSAGE_MAX} characters`),
    reminderTime: z.string()
      .min(1, 'Time is required')
      .regex(REMINDER_TIME_PATTERN, 'Enter a time in HH:mm format (24-hour)'),
    frequency: z.string().refine((v): boolean => isReminderFrequency(v), 'Choose how often the reminder repeats'),
    startDate: z.string()
      .min(1, 'Date is required')
      .refine(v => isValidDateOnly(v), 'Enter a valid date'),
    /** '' = no end date. Ignored when the reminder fires once. */
    endDate: z.string().refine(v => v === '' || isValidDateOnly(v), 'Enter a valid date'),
  })
  .superRefine((values, ctx) => {
    if (values.frequency === 'ONCE' || !values.endDate) return;
    // Date-only strings compare correctly as text.
    if (isValidDateOnly(values.startDate) && isValidDateOnly(values.endDate) && values.endDate < values.startDate) {
      ctx.addIssue({ code: 'custom', path: ['endDate'], message: 'End date must not be before the start date' });
    }
  });

export type ReminderFormValues = z.infer<typeof reminderSchema>;
export type ReminderFormField = keyof ReminderFormValues;

/** Field names shared by the form and the backend, for mapping server field errors onto the form. */
export const REMINDER_FORM_FIELDS: readonly ReminderFormField[] = [
  'type', 'title', 'message', 'reminderTime', 'frequency', 'startDate', 'endDate',
];

/** A new reminder: the type and time are left for the person to choose; it starts today (clinic calendar). */
export function emptyReminderForm(): ReminderFormValues {
  return {
    type: '', title: '', message: '', reminderTime: '', frequency: 'DAILY', startDate: clinicToday(), endDate: '',
  };
}

/** The form filled from a stored reminder, for editing. */
export function reminderFormOf(reminder: Reminder): ReminderFormValues {
  return {
    type:         reminder.type,
    title:        reminder.title ?? '',
    message:      reminder.message ?? '',
    reminderTime: (reminder.reminderTime ?? '').slice(0, 5),
    frequency:    reminder.frequency,
    startDate:    reminder.startDate ?? '',
    endDate:      reminder.endDate ?? '',
  };
}

interface Normalised {
  type:         ReminderType;
  title:        string;
  message:      string;
  reminderTime: string;
  frequency:    ReminderFrequency;
  startDate:    string;
  /** '' = none. Always '' for ONCE. */
  endDate:      string;
}

/** Validated form values in the shape the API uses. Call only with values that passed `reminderSchema`. */
function normalise(values: ReminderFormValues): Normalised {
  const frequency = values.frequency as ReminderFrequency;
  return {
    type:         values.type as ReminderType,
    title:        values.title.trim(),
    message:      values.message.trim(),
    reminderTime: values.reminderTime.slice(0, 5),
    frequency,
    startDate:    values.startDate,
    endDate:      frequency === 'ONCE' ? '' : values.endDate,
  };
}

/** The POST body for validated form values. The end date is left out when empty or when the reminder fires once. */
export function toCreateReminderInput(patientSystemId: string, values: ReminderFormValues): CreateReminderInput {
  const v = normalise(values);
  const input: CreateReminderInput = {
    patientSystemId,
    type:         v.type,
    title:        v.title,
    message:      v.message,
    reminderTime: v.reminderTime,
    frequency:    v.frequency,
    startDate:    v.startDate,
  };
  if (v.endDate) input.endDate = v.endDate;
  return input;
}

/**
 * The PATCH body for an edited manual reminder: only the fields that differ from the stored reminder. An emptied
 * end date becomes `clearEndDate`; switching to "once" sends no end date (the server clears it). An empty object
 * means nothing changed, and no request should be sent.
 */
export function toUpdateReminderInput(current: Reminder, values: ReminderFormValues): UpdateReminderInput {
  const v = normalise(values);
  const patch: UpdateReminderInput = {};
  if (v.type !== current.type) patch.type = v.type;
  if (v.title !== current.title) patch.title = v.title;
  if (v.message !== current.message) patch.message = v.message;
  if (v.reminderTime !== (current.reminderTime ?? '').slice(0, 5)) patch.reminderTime = v.reminderTime;
  if (v.frequency !== current.frequency) patch.frequency = v.frequency;
  if (v.startDate !== current.startDate) patch.startDate = v.startDate;
  if (v.frequency !== 'ONCE') {
    const storedEnd = current.endDate ?? '';
    if (v.endDate && v.endDate !== storedEnd) patch.endDate = v.endDate;
    else if (!v.endDate && storedEnd) patch.clearEndDate = true;
  }
  return patch;
}

/** True when the patch changes when the reminder fires (the server then recomputes the next due time). */
export function changesReminderSchedule(patch: UpdateReminderInput): boolean {
  return patch.reminderTime !== undefined || patch.frequency !== undefined || patch.startDate !== undefined
    || patch.endDate !== undefined || patch.clearEndDate === true;
}

export function isEmptyReminderPatch(patch: UpdateReminderInput): boolean {
  return Object.keys(patch).length === 0;
}

export interface ReminderFieldIssue {
  field:   ReminderFormField;
  message: string;
}

/**
 * Pre-check of the server rule "the reminder has no upcoming time": a reminder that fires once must not lie in
 * the past, and a repeating one must not end before now. Times are compared with the clinic's clock, never the
 * device's. It only catches the certain cases; the server decides for the rest (for example a weekly reminder
 * whose last occurrence falls before its end date).
 */
export function reminderUpcomingIssue(
  values: ReminderFormValues,
  nowLocal: string = clinicNowLocal(),
): ReminderFieldIssue | null {
  const v = normalise(values);
  if (!REMINDER_TIME_PATTERN.test(v.reminderTime)) return null;
  if (v.frequency === 'ONCE') {
    if (!isValidDateOnly(v.startDate) || `${v.startDate}T${v.reminderTime}:00` >= nowLocal) return null;
    return { field: 'startDate', message: 'This date and time have already passed at the clinic. Choose a later date or time.' };
  }
  if (v.endDate && isValidDateOnly(v.endDate) && `${v.endDate}T${v.reminderTime}:00` < nowLocal) {
    return { field: 'endDate', message: 'The reminder would already be over. Choose a later end date or leave it empty.' };
  }
  return null;
}
