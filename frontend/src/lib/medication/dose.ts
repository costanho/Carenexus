import type { StatusTone } from '@/components/web/ui';
import { clinicNowLocal, formatClinicDateTime, formatClinicTime } from '@/lib/time/clinic';
import type { DoseAction, DoseStatus, MedicationDose } from './types';

// Display rules for a dose (api/be-medication 1.1 and 2.6). Nothing here decides permissions: whether the
// viewer may mark doses at all comes from the caller (`canMark`).

/** Chip text per status. The label is always shown; colour never carries the meaning alone. */
export const DOSE_STATUS_LABEL: Record<DoseStatus, string> = {
  PENDING: 'Due',
  TAKEN:   'Taken',
  MISSED:  'Missed',
};

export const DOSE_ACTION_LABEL: Record<DoseAction, string> = {
  taken:  'Mark taken',
  missed: 'Mark missed',
  undo:   'Undo',
};

/** True for a pending dose whose clinic date and time have arrived. Compared with the clinic clock, never the device's. */
export function isDoseOverdue(dose: MedicationDose, now: string = clinicNowLocal()): boolean {
  return dose.status === 'PENDING' && `${dose.scheduleDate}T${dose.scheduledTime}:00` <= now;
}

/** True for a dose the server marked missed after the grace period (no person set the status). */
export function isAutoMissed(dose: MedicationDose): boolean {
  return dose.status === 'MISSED' && dose.markedBySystemId === null;
}

export function doseTone(dose: MedicationDose, now: string = clinicNowLocal()): StatusTone {
  if (dose.status === 'TAKEN') return 'success';
  if (dose.status === 'MISSED') return 'danger';
  return isDoseOverdue(dose, now) ? 'warning' : 'info';
}

/**
 * The controls of a dose, in display order. Empty unless the dose is of today or yesterday (`markable`) and the
 * viewer may mark doses. Undo is offered only for a status a person set: a pending dose that is past its grace
 * period is marked missed again by the server within minutes, so undoing an automatic miss would not hold.
 */
export function doseControls(dose: MedicationDose, canMark: boolean): DoseAction[] {
  if (!canMark || !dose.markable) return [];
  if (dose.status === 'PENDING') return ['taken', 'missed'];
  if (dose.status === 'TAKEN')   return ['undo'];
  return dose.markedBySystemId ? ['undo', 'taken'] : ['taken'];
}

export interface DoseSlot {
  /** "HH:mm". */
  time:  string;
  doses: MedicationDose[];
}

/** Groups a day's doses into time slots, keeping the server's order (time, then id). */
export function groupDosesByTime(doses: readonly MedicationDose[]): DoseSlot[] {
  const slots: DoseSlot[] = [];
  for (const dose of doses) {
    const last = slots[slots.length - 1];
    if (last && last.time === dose.scheduledTime) last.doses.push(dose);
    else slots.push({ time: dose.scheduledTime, doses: [dose] });
  }
  return slots;
}

export interface DoseCounts { taken: number; missed: number; due: number; total: number }

export function countDoses(doses: readonly MedicationDose[]): DoseCounts {
  const counts: DoseCounts = { taken: 0, missed: 0, due: 0, total: doses.length };
  for (const dose of doses) {
    if (dose.status === 'TAKEN') counts.taken++;
    else if (dose.status === 'MISSED') counts.missed++;
    else counts.due++;
  }
  return counts;
}

/** "2 of 3 taken, 1 due" for the line above a day's doses. */
export function doseCountsSentence(counts: DoseCounts): string {
  const parts = [`${counts.taken} of ${counts.total} taken`];
  if (counts.due > 0) parts.push(`${counts.due} due`);
  if (counts.missed > 0) parts.push(`${counts.missed} missed`);
  return parts.join(', ');
}

/** "Metformin 850mg" (the name alone when there is no dosage). */
export function doseName(dose: MedicationDose): string {
  return [dose.medicationName.trim(), dose.dosage.trim()].filter(Boolean).join(' ') || 'Medication';
}

export interface DoseViewer {
  /** System id of the signed-in person, to say "by you". */
  systemId?:    string | null;
  /** Name of the patient the schedule belongs to, when the viewer is someone else. */
  patientName?: string | null;
}

/**
 * The line under a dose that says what happened to it: when it was taken and who recorded the status.
 * Null for a pending dose that is not overdue.
 */
export function doseDetail(dose: MedicationDose, viewer: DoseViewer = {}, now: string = clinicNowLocal()): string | null {
  if (dose.status === 'PENDING') return isDoseOverdue(dose, now) ? 'Overdue' : null;
  if (isAutoMissed(dose)) return 'Not marked in time, so it was recorded as missed automatically';

  const by = dose.markedBySystemId ?? '';
  let who = '';
  if (by && viewer.systemId && by === viewer.systemId) who = 'by you';
  else if (by.startsWith('CGV-')) who = 'by a caregiver';
  else if (by.startsWith('PAT-')) who = viewer.patientName?.trim() ? `by ${viewer.patientName.trim()}` : 'by the patient';

  if (dose.status === 'TAKEN') {
    // Same clinic day: the time is enough. Another day (yesterday's dose marked today): the full date and time.
    const sameDay = (dose.takenAt ?? '').slice(0, 10) === dose.scheduleDate;
    const at = sameDay ? formatClinicTime(dose.takenAt) : formatClinicDateTime(dose.takenAt);
    return ['Marked taken', at ? `at ${at}` : '', who].filter(Boolean).join(' ');
  }
  return ['Marked missed', who].filter(Boolean).join(' ');
}
