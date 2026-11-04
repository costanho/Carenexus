import { useState } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { describeCallJoin, type CallJoinInfo } from '@/lib/calls/join';
import { confirmAction, showMessage } from '@/lib/ui/dialogs';
import type { ScheduleEntry } from './use-doctor-schedule';

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

/** Which status actions make sense for an appointment right now. */
export function availableActions(entry: ScheduleEntry, now = new Date()): DoctorAppointmentAction[] {
  if (entry.status !== 'SCHEDULED' && entry.status !== 'RESCHEDULED') return [];
  const started = new Date(entry.scheduled_at).getTime() <= now.getTime();
  return started ? ['complete', 'no-show', 'cancel'] : ['cancel'];
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
 */
export function useDoctorAppointmentActions(onDone?: () => void) {
  const [pendingId, setPendingId] = useState<number | null>(null);

  async function run(entry: ScheduleEntry, action: DoctorAppointmentAction) {
    if (pendingId !== null) return;
    const cfg = ACTIONS[action];
    const ok = await confirmAction(
      cfg.title,
      `${entry.patient_name} — ${new Date(entry.scheduled_at).toLocaleString()}`,
      cfg.confirm,
      cfg.destructive,
    );
    if (!ok) return;

    setPendingId(entry.appointment_id);
    try {
      await api.patch(`/api/doctors/appointments/${entry.appointment_id}/${cfg.path}`);
      showMessage('Done', cfg.done);
      onDone?.();
    } catch (err: any) {
      const msg = err?.response?.data?.message ?? 'Could not update the appointment. Please try again.';
      showMessage('Update failed', msg);
    } finally {
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
