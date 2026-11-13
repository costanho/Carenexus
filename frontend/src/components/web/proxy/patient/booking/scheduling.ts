import { appointmentEnd, hasStarted, isActiveStatus } from '@/lib/appointments/rules';
import type { CaregiverAppointment, CaregiverDependent } from '@/lib/caregiver/types';
import { effectivePermission, isGrantActive } from '@/lib/caregiver/permissions';
import { clinicNowLocal, formatClinicDate, toClinicLocal } from '@/lib/time/clinic';

// What a caregiver may do with a loved one's appointments, and where an appointment stands in clinic time.
// The rules come from @/lib/caregiver/permissions (the one web implementation of the grant rule); the server
// enforces the same rule on every request, so this only decides which controls are shown.

/** Whether the caregiver may book, reschedule and cancel for this loved one, and if not, why. */
export type SchedulingAccess =
  | { allowed: true }
  | { allowed: false; reason: string };

/**
 * Scheduling rights for one grant: the grant must be active and unexpired, and the effective permission
 * `canScheduleAppointments` must hold (FULL_ACCESS grants it, VIEW_ONLY blocks it, otherwise the flag decides).
 */
export function schedulingAccess(dependent: CaregiverDependent, now: Date = new Date()): SchedulingAccess {
  if (!isGrantActive(dependent, now)) {
    if (dependent.accessStatus === 'REVOKED' || dependent.revokedAt) {
      return { allowed: false, reason: 'Your access was revoked.' };
    }
    const expires = toClinicLocal(dependent.expiresAt);
    if (dependent.accessStatus === 'EXPIRED' || (expires && expires <= clinicNowLocal(now))) {
      const ended = formatClinicDate(dependent.expiresAt);
      return { allowed: false, reason: ended ? `Your access ended on ${ended}.` : 'Your access has ended.' };
    }
    return { allowed: false, reason: 'Your access is not active.' };
  }
  if (effectivePermission(dependent, 'canScheduleAppointments', now)) return { allowed: true };
  if (dependent.accessLevel === 'VIEW_ONLY') {
    return { allowed: false, reason: 'Your access is view only, so you cannot book appointments.' };
  }
  return { allowed: false, reason: 'Your access does not include scheduling appointments.' };
}

/** True when the caregiver may book, reschedule and cancel for this loved one right now. */
export function canSchedule(dependent: CaregiverDependent | null | undefined, now: Date = new Date()): boolean {
  return !!dependent && schedulingAccess(dependent, now).allowed;
}

/** The loved one's display name, never the raw system id. */
export function dependentName(dependent: Pick<CaregiverDependent, 'patientName'> | null | undefined): string {
  return dependent?.patientName?.trim() || 'your loved one';
}

/** The appointment length the database uses when none was recorded (appointments.duration_minutes DEFAULT 30). */
export const DEFAULT_DURATION_MINUTES = 30;

/**
 * Where an appointment stands, in clinic time:
 * - `upcoming`: SCHEDULED or RESCHEDULED and not started yet (the only phase that can be changed);
 * - `in-progress`: started, its end not reached yet;
 * - `awaiting-outcome`: still SCHEDULED or RESCHEDULED although its end has passed (the doctor has not closed it);
 * - `completed`, `cancelled`, `no-show`: final.
 */
export type AppointmentPhase =
  | 'upcoming' | 'in-progress' | 'awaiting-outcome' | 'completed' | 'cancelled' | 'no-show' | 'other';

export function appointmentPhase(
  appt: Pick<CaregiverAppointment, 'status' | 'scheduledAt' | 'durationMinutes'>,
  now: string = clinicNowLocal(),
): AppointmentPhase {
  if (isActiveStatus(appt.status)) {
    if (!hasStarted(appt.scheduledAt, now)) return 'upcoming';
    const end = appointmentEnd(appt.scheduledAt, appt.durationMinutes ?? DEFAULT_DURATION_MINUTES);
    return end && end <= now ? 'awaiting-outcome' : 'in-progress';
  }
  switch (appt.status) {
    case 'COMPLETED': return 'completed';
    case 'CANCELLED': return 'cancelled';
    case 'NO_SHOW':   return 'no-show';
    default:          return 'other';
  }
}

/** Upcoming and in-progress appointments first (soonest first), everything else after them (most recent first). */
export function compareForList(
  a: CaregiverAppointment, b: CaregiverAppointment, phaseOf: (appt: CaregiverAppointment) => AppointmentPhase,
): number {
  const current = (p: AppointmentPhase) => p === 'upcoming' || p === 'in-progress';
  const aCurrent = current(phaseOf(a));
  const bCurrent = current(phaseOf(b));
  if (aCurrent !== bCurrent) return aCurrent ? -1 : 1;
  const byTime = (toClinicLocal(a.scheduledAt) ?? a.scheduledAt).localeCompare(toClinicLocal(b.scheduledAt) ?? b.scheduledAt);
  return aCurrent ? byTime : -byTime;
}
