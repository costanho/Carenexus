import { api } from '@/lib/auth/auth.interceptor';
import type { DoseAction, DoseStatus, MedicationAdherence, MedicationDose } from './types';

// Client for /api/medication-schedules. Day lists are plain arrays (one day is a small list), sorted by the
// server by time and then id. Every function rejects with the axios error; show it with `apiError`.

const BASE = '/api/medication-schedules';

const STATUSES: readonly DoseStatus[] = ['PENDING', 'TAKEN', 'MISSED'];

function text(value: unknown): string {
  return value == null ? '' : String(value);
}

function count(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0;
}

/** Reads one dose of a response. A row without an id or with an unknown status is dropped, never guessed. */
function toDose(raw: unknown): MedicationDose | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const status = text(r.status).toUpperCase() as DoseStatus;
  if (typeof r.scheduleId !== 'number' || !STATUSES.includes(status)) return null;
  return {
    scheduleId:       r.scheduleId,
    prescriptionId:   typeof r.prescriptionId === 'number' ? r.prescriptionId : 0,
    patientSystemId:  text(r.patientSystemId),
    medicationName:   text(r.medicationName),
    dosage:           text(r.dosage),
    scheduleDate:     text(r.scheduleDate).slice(0, 10),
    // The contract sends "HH:mm"; a value with seconds is cut rather than shown as it is.
    scheduledTime:    text(r.scheduledTime).slice(0, 5),
    status,
    takenAt:          r.takenAt ? text(r.takenAt) : null,
    markedBySystemId: r.markedBySystemId ? text(r.markedBySystemId) : null,
    markable:         r.markable === true,
  };
}

function toDoses(data: unknown): MedicationDose[] {
  if (!Array.isArray(data)) return [];
  return data.map(toDose).filter((d): d is MedicationDose => d !== null);
}

/** The signed-in patient's doses of one clinic day (`date` is "yyyy-MM-dd"). */
export async function listMyDoses(date: string): Promise<MedicationDose[]> {
  const { data } = await api.get<unknown>(`${BASE}/me`, { params: { date } });
  return toDoses(data);
}

/** One patient's doses of one clinic day, for a caregiver with the medications permission (or the patient). */
export async function listPatientDoses(patientSystemId: string, date: string): Promise<MedicationDose[]> {
  const { data } = await api.get<unknown>(`${BASE}/patient/${encodeURIComponent(patientSystemId)}`, { params: { date } });
  return toDoses(data);
}

/** Taken, missed and pending counts over the last `days` clinic days (1 to 90), today included. */
export async function getAdherence(patientSystemId: string, days = 30): Promise<MedicationAdherence> {
  const { data } = await api.get<Partial<MedicationAdherence> | null>(
    `${BASE}/patient/${encodeURIComponent(patientSystemId)}/adherence`,
    { params: { days } },
  );
  const taken = count(data?.taken), missed = count(data?.missed), pending = count(data?.pending);
  const rate = data?.adherenceRate;
  return {
    patientSystemId: text(data?.patientSystemId) || patientSystemId,
    days:            count(data?.days) || days,
    from:            text(data?.from).slice(0, 10),
    to:              text(data?.to).slice(0, 10),
    taken,
    missed,
    pending,
    total:           typeof data?.total === 'number' ? count(data.total) : taken + missed + pending,
    adherenceRate:   typeof rate === 'number' && Number.isFinite(rate) ? rate : null,
  };
}

/**
 * Marks a dose taken or missed, or puts it back to pending (undo). No request body. Resolves with the updated
 * dose; the same status again is a no-op that also resolves. Rejects with 403 (not allowed), 404 (the dose is
 * no longer scheduled) or 409 (not a dose of today or yesterday).
 */
export async function markDose(scheduleId: number, action: DoseAction): Promise<MedicationDose> {
  const { data } = await api.patch<unknown>(`${BASE}/${scheduleId}/${action}`);
  const dose = toDose(data);
  if (!dose) throw new Error('Unexpected response for a dose update');
  return dose;
}

export const markDoseTaken  = (scheduleId: number) => markDose(scheduleId, 'taken');
export const markDoseMissed = (scheduleId: number) => markDose(scheduleId, 'missed');
export const undoDose       = (scheduleId: number) => markDose(scheduleId, 'undo');
