// Reminder DTOs, camelCase straight from the API (contract api/be-reminders.md, section 1).
//
// Only reminders addressed to the patient have an HTTP surface: manual ones (created through the API) and the
// automatic follow-up and care-plan review reminders. Appointment and medication-dose reminders are sent by
// separate jobs and never appear in this list.

export const REMINDER_TYPES = ['MEDICATION', 'APPOINTMENT', 'LAB_FOLLOWUP', 'CARE_PLAN', 'CHECK_IN', 'VITAL_SIGN'] as const;
export type ReminderType = (typeof REMINDER_TYPES)[number];

/** `CUSTOM` exists in the table but the API never accepts or returns it. */
export const REMINDER_FREQUENCIES = ['ONCE', 'DAILY', 'WEEKLY', 'MONTHLY'] as const;
export type ReminderFrequency = (typeof REMINDER_FREQUENCIES)[number];

/** Derived by the server at read time: ACTIVE, PAUSED (can be resumed) or ENDED (no occurrence left). */
export type ReminderStatus = 'ACTIVE' | 'PAUSED' | 'ENDED';

export type ReminderSourceType = 'MANUAL' | 'CONSULTATION' | 'CARE_PLAN';

export interface Reminder {
  reminderId:        number;
  /** The patient the reminder is for and who receives it. */
  patientSystemId:   string;
  type:              ReminderType;
  /** 1 to 80 characters; becomes the notification title. */
  title:             string;
  /** 1 to 400 characters; becomes the notification text. */
  message:           string;
  /** Clinic wall-clock time of day, "HH:mm" (24-hour). */
  reminderTime:      string;
  frequency:         ReminderFrequency;
  /** Date-only "yyyy-MM-dd" (clinic calendar): first day the reminder can fire. */
  startDate:         string;
  /** Last day it can fire, inclusive; null = no end. Always null for ONCE. */
  endDate:           string | null;
  /** Stored flag: false = paused or ended. */
  isActive:          boolean;
  status:            ReminderStatus;
  /** Clinic wall-clock date-time without offset; non-null exactly when `isActive`. Computed by the server only. */
  nextDueAt:         string | null;
  /** Clinic wall-clock date-time of the last dispatch; null = never. */
  lastSentAt:        string | null;
  sourceType:        ReminderSourceType;
  /** null for MANUAL; consultation id for CONSULTATION; care plan id for CARE_PLAN. */
  sourceId:          number | null;
  /** Display text for the source: "Manual", "Follow-up", "Care plan". */
  sourceLabel:       string;
  /** Who created a manual reminder; null for automatic reminders. */
  createdBySystemId: string | null;
  /** True exactly when DELETE would succeed for the caller right now. */
  canDelete:         boolean;
  /** Clinic wall-clock date-time. */
  createdAt:         string;
}

/** Body of POST /api/reminders. `endDate` is ignored by the server when `frequency` is ONCE. */
export interface CreateReminderInput {
  patientSystemId: string;
  type:            ReminderType;
  title:           string;
  message:         string;
  reminderTime:    string;
  frequency:       ReminderFrequency;
  startDate:       string;
  endDate?:        string | null;
}

/**
 * Body of PATCH /api/reminders/{id}. Absent fields are unchanged; at least one must be present.
 * Automatic reminders accept only `isActive`. `clearEndDate` removes the end date and cannot be combined
 * with `endDate`.
 */
export interface UpdateReminderInput {
  type?:         ReminderType;
  title?:        string;
  message?:      string;
  reminderTime?: string;
  frequency?:    ReminderFrequency;
  startDate?:    string;
  endDate?:      string;
  clearEndDate?: boolean;
  isActive?:     boolean;
}

/** The `active` query filter of the list: true = active only, false = paused and ended only, null = all. */
export interface ReminderListFilter {
  active?: boolean | null;
}
