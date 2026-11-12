import { useRef, useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { apiError, type ApiError } from '@/lib/api/errors';
import { describeCallJoin, type CallJoinInfo } from '@/lib/calls/join';
import { clinicNowLocal, formatClinicDateTime, toClinicLocal } from '@/lib/time/clinic';
import { confirmAction, showMessage } from '@/lib/ui/dialogs';
import { applyAppointmentUpdate, type ApiAppointment, type ScheduleEntry } from './use-doctor-schedule';

export type DoctorAppointmentAction = 'complete' | 'no-show' | 'cancel';

const ACTIONS: Record<DoctorAppointmentAction, {
  path:    string;
  title:   string;
  confirm: string;
  done:    string;
  destructive: boolean;
}> = {
  'complete': { path: 'mark-completed', title: 'Mark as completed', confirm: 'Mark completed', done: 'Appointment marked as completed.', destructive: false },
  'no-show':  { path: 'mark-no-show',   title: 'Mark as no-show',   confirm: 'Mark no-show',   done: 'Appointment marked as no-show.',  destructive: true  },
  'cancel':   { path: 'mark-cancelled', title: 'Cancel appointment', confirm: 'Cancel appointment', done: 'Appointment cancelled.',     destructive: true  },
};

// Appointment times are clinic wall-clock values without an offset. "Started", "upcoming" and "past" are decided
// against the clinic's current wall clock (the rule the server applies), never against the device clock: a device
// in another time zone would otherwise offer Complete and No-show hours early or late.

/** SCHEDULED or RESCHEDULED: the appointment can still change. */
export function isActiveAppointment(entry: ScheduleEntry): boolean {
  return entry.status === 'SCHEDULED' || entry.status === 'RESCHEDULED';
}

/** True once the appointment's start is not after the clinic's current time. False when the start cannot be read. */
export function hasStarted(entry: ScheduleEntry, now: Date = new Date()): boolean {
  const start = toClinicLocal(entry.scheduled_at);
  return !!start && start <= clinicNowLocal(now);
}

/** True while the appointment's start is still ahead of the clinic's current time. */
function isAhead(entry: ScheduleEntry, now: Date): boolean {
  const start = toClinicLocal(entry.scheduled_at);
  return !!start && start > clinicNowLocal(now);
}

/** Which status actions make sense for an appointment right now. */
export function availableActions(entry: ScheduleEntry, now: Date = new Date()): DoctorAppointmentAction[] {
  if (!isActiveAppointment(entry)) return [];
  return hasStarted(entry, now) ? ['complete', 'no-show', 'cancel'] : ['cancel'];
}

/**
 * Whether the doctor can move this appointment to a new time: still active (SCHEDULED/RESCHEDULED)
 * and not started yet. Kept apart from availableActions because it opens a picker instead of a
 * one-step status PATCH.
 */
export function canReschedule(entry: ScheduleEntry, now: Date = new Date()): boolean {
  return isActiveAppointment(entry) && isAhead(entry, now);
}

/** A manual reminder can be sent for an active appointment that has not started yet. */
export function canSendReminder(entry: ScheduleEntry, now: Date = new Date()): boolean {
  return isActiveAppointment(entry) && isAhead(entry, now);
}

export interface ConsultationEntryPoint {
  kind:  'start' | 'continue' | 'view';
  label: 'Start consultation' | 'Continue consultation' | 'View consultation';
}

/**
 * The consultation button of an appointment, or null when there is nothing to open: no linked consultation =
 * Start (once the appointment has started and is not cancelled or missed), a draft or one in progress =
 * Continue, a completed one = View. All three open the Consultations section with `appointmentId`.
 */
export function consultationEntryPoint(entry: ScheduleEntry, now: Date = new Date()): ConsultationEntryPoint | null {
  const linked = entry.consultation && entry.consultation.status !== 'CANCELLED' ? entry.consultation : null;
  if (linked) {
    return linked.status === 'COMPLETED'
      ? { kind: 'view', label: 'View consultation' }
      : { kind: 'continue', label: 'Continue consultation' };
  }
  if (entry.status === 'CANCELLED' || entry.status === 'NO_SHOW') return null;
  return hasStarted(entry, now) ? { kind: 'start', label: 'Start consultation' } : null;
}

/**
 * Moves an appointment to a new clinic wall-clock time (Africa/Harare, no offset) via
 * PATCH /api/appointments/{id}/reschedule. The duration is kept. The backend sets the status to
 * RESCHEDULED, re-checks availability and moves the video call session. Resolves with the updated
 * appointment; throws the axios error.
 */
export async function rescheduleDoctorAppointment(appointmentId: number, date: string, time: string): Promise<ApiAppointment> {
  const { data } = await api.patch<ApiAppointment>(`/api/appointments/${appointmentId}/reschedule`, { scheduledAt: `${date}T${time}:00` });
  return data;
}

/** The doctor's Join control for a schedule entry (internal call route), or null for none. */
export function doctorCallJoin(entry: ScheduleEntry, now = Date.now()): CallJoinInfo | null {
  return describeCallJoin({
    type:               entry.type,
    status:             entry.status,
    callStatus:         entry.call_status,
    callJoinRoute:      entry.call_join_route,
    joinWindowOpensAt:  entry.join_window_opens_at,
    joinWindowClosesAt: entry.join_window_closes_at,
  }, true, now);
}

/**
 * Status updates for a doctor's appointments via
 * PATCH /api/doctors/appointments/{id}/mark-completed | mark-no-show | mark-cancelled.
 * `onDone` receives the entry as the server now has it, so a paged list can update the row in place;
 * callers that simply reload can ignore the arguments.
 */
export function useDoctorAppointmentActions(
  onDone?: (updated: ScheduleEntry, action: DoctorAppointmentAction) => void,
) {
  const [pendingId, setPendingId] = useState<number | null>(null);
  // The ref is the double-submit guard: two clicks in one frame both see pendingId === null.
  const running = useRef(false);

  async function run(entry: ScheduleEntry, action: DoctorAppointmentAction) {
    if (running.current) return;
    const cfg = ACTIONS[action];
    running.current = true;
    try {
      const ok = await confirmAction(
        cfg.title,
        `${entry.patient_name} — ${formatClinicDateTime(entry.scheduled_at, entry.scheduled_at)}`,
        cfg.confirm,
        cfg.destructive,
      );
      if (!ok) return;

      setPendingId(entry.appointment_id);
      const { data } = await api.patch<ApiAppointment>(`/api/doctors/appointments/${entry.appointment_id}/${cfg.path}`);
      showMessage('Done', cfg.done);
      onDone?.(applyAppointmentUpdate(entry, data), action);
    } catch (err) {
      showMessage('Update failed', apiError(err, 'Could not update the appointment. Please try again.').message);
    } finally {
      running.current = false;
      setPendingId(null);
    }
  }

  return { run, pendingId };
}

export const ACTION_LABELS: Record<DoctorAppointmentAction, string> = {
  'complete': 'Complete',
  'no-show':  'No-show',
  'cancel':   'Cancel',
};

// ─── Manual reminder ──────────────────────────────────────────────────────────

/** POST /api/doctors/appointments/{id}/send-reminder. */
export interface ManualReminderResult {
  appointmentId:  number;
  /** When the last manual reminder was sent (clinic wall clock). */
  reminderSentAt: string;
  /** True when a reminder had already gone out in the current clock hour and this call sent nothing. */
  alreadySent:    boolean;
}

/**
 * Sends one in-app reminder about an upcoming appointment to the patient and the caregivers who may see their
 * appointments. At most one goes out per appointment and clock hour (`alreadySent`), and the doctor is limited
 * to ten calls an hour (429). Throws the axios error.
 */
export async function sendAppointmentReminder(appointmentId: number): Promise<ManualReminderResult> {
  const { data } = await api.post<ManualReminderResult>(`/api/doctors/appointments/${appointmentId}/send-reminder`);
  return data;
}

export type ReminderOutcome =
  | { ok: true;  result: ManualReminderResult; entry: ScheduleEntry }
  /** `error` is null when the call was ignored because another reminder is still being sent. */
  | { ok: false; error: ApiError | null };

/**
 * "Send reminder" for appointment rows. `send` never rejects: on success it returns the entry with the new
 * reminder time, on failure the error to show (for a 429 its message is the rate-limit sentence).
 */
export function useAppointmentReminder() {
  const [pendingId, setPendingId] = useState<number | null>(null);
  const running = useRef(false);

  async function send(entry: ScheduleEntry): Promise<ReminderOutcome> {
    if (running.current) return { ok: false, error: null };
    running.current = true;
    setPendingId(entry.appointment_id);
    try {
      const result = await sendAppointmentReminder(entry.appointment_id);
      return { ok: true, result, entry: { ...entry, reminder_sent_at: result.reminderSentAt ?? entry.reminder_sent_at } };
    } catch (err) {
      return { ok: false, error: apiError(err, 'Could not send the reminder. Please try again.') };
    } finally {
      running.current = false;
      setPendingId(null);
    }
  }

  return { send, pendingId };
}
