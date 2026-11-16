import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { CLINIC_TIME_ZONE } from '@/lib/calls/format';
import { describeCallJoin, type CallJoinInfo } from '@/lib/calls/join';
import type { CallSessionStatus } from '@/lib/calls/types';
import { useCurrentUser } from './use-current-user';
import { loadActiveDoctors, type Doctor } from './use-doctors';

export interface Appointment {
  appointment_id:          number;
  patient_id:              number;
  doctor_id:               number;
  facility_id:             number | null;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED';
  reason_for_visit:        string | null;
  /** Legacy field, always null from the API. Calls use call_join_route. */
  video_consultation_link: string | null;
  call_status:             CallSessionStatus | null;
  call_join_route:         string | null;
  join_window_opens_at:    string | null;
  join_window_closes_at:   string | null;
  reminder_sent:           number;
  notes:                   string | null;
  created_at:              string;
  updated_at:              string;
  /** DOC-… id of the appointment's doctor, used to open a private conversation with them. */
  doctor_system_id:        string | null;
  doctor_name:             string;
  doctor_specialization:   string | null;
  doctor_avatar_url:       string | null;
  facility_name:           string | null;
}

interface State {
  appointments: Appointment[];
  loading:      boolean;
  error:        string | null;
  refetch:      () => void;
}

/** Pulls the backend's `{ message }` out of an axios error, falling back to a generic sentence. */
export function apiErrorMessage(e: any, fallback: string): string {
  const msg = e?.response?.data?.message;
  return typeof msg === 'string' && msg.trim() ? msg : fallback;
}

/**
 * Builds the wall-clock LocalDateTime string the backend expects (no offset).
 * The backend interprets it in the clinic's time zone.
 */
export function toScheduledAt(date: string, time: string): string {
  return `${date}T${time}:00`;
}

const WALL_CLOCK = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2})(:\d{2})?/;

/**
 * The clinic's current wall-clock time as "YYYY-MM-DDTHH:mm:ss". Appointment times are clinic wall-clock values
 * (LocalDateTime, no offset), so they are compared with this rather than with the device clock: a device in
 * another time zone would otherwise treat a 10:00 appointment as starting at 10:00 its own time.
 */
export function clinicNowLocal(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: CLINIC_TIME_ZONE, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? '00';
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}:${get('second')}`;
}

/** True when the clinic wall-clock value is later than the clinic's current time (device clock as a fallback). */
export function isAfterClinicNow(local: string): boolean {
  const m = WALL_CLOCK.exec(local ?? '');
  if (m) return `${m[1]}${m[2] ?? ':00'}` > clinicNowLocal();
  const t = new Date(local).getTime();
  return !Number.isNaN(t) && t > Date.now();
}

/** True when the given clinic date (YYYY-MM-DD) and time (HH:mm) are later than the clinic's current time. */
export function isFutureSlot(date: string, time: string): boolean {
  if (!date || !time) return false;
  return isAfterClinicNow(toScheduledAt(date, time));
}

/** SCHEDULED/RESCHEDULED appointments that have not started yet (in clinic time). */
export function isUpcomingAppointment(a: Appointment): boolean {
  if (a.status !== 'SCHEDULED' && a.status !== 'RESCHEDULED') return false;
  return isAfterClinicNow(a.scheduled_at);
}

/** The patient's Join control for an appointment (internal call route), or null for none. */
export function appointmentCallJoin(a: Appointment, now = Date.now()): CallJoinInfo | null {
  return describeCallJoin({
    type:               a.type,
    status:             a.status,
    callStatus:         a.call_status,
    callJoinRoute:      a.call_join_route,
    joinWindowOpensAt:  a.join_window_opens_at,
    joinWindowClosesAt: a.join_window_closes_at,
  }, false, now);
}

/** Soft-cancels an appointment (backend sets status CANCELLED). Throws the axios error on failure. */
export async function cancelAppointment(appointmentId: number): Promise<void> {
  await api.delete(`/api/appointments/${appointmentId}`);
  await invalidateAppointments();
}

/** Moves an appointment to a new local date/time via PUT /api/appointments/{id}. Throws the axios error on failure. */
export async function rescheduleAppointment(appointmentId: number, date: string, time: string): Promise<void> {
  await api.put(`/api/appointments/${appointmentId}`, { scheduledAt: toScheduledAt(date, time) });
  await invalidateAppointments();
}

function toAppointment(a: any, doctorMap: Map<string, Doctor>): Appointment {
  const doctorSystemId = a.doctorSystemId ?? a.doctor_system_id ?? '';
  const doctor = doctorMap.get(doctorSystemId);
  return {
    appointment_id:          a.appointmentId          ?? 0,
    patient_id:              0,
    doctor_id:               0,
    facility_id:             a.facilityId             ?? null,
    scheduled_at:            a.scheduledAt            ? String(a.scheduledAt) : '',
    duration_minutes:        a.durationMinutes        ?? 30,
    type:                    (a.type                  ?? 'IN_PERSON') as Appointment['type'],
    status:                  (a.status                ?? 'SCHEDULED') as Appointment['status'],
    reason_for_visit:        a.reasonForVisit         ?? null,
    video_consultation_link: null,
    call_status:             a.callStatus             ?? null,
    call_join_route:         a.callJoinRoute          ?? null,
    join_window_opens_at:    a.joinWindowOpensAt      ? String(a.joinWindowOpensAt)  : null,
    join_window_closes_at:   a.joinWindowClosesAt     ? String(a.joinWindowClosesAt) : null,
    reminder_sent:           a.reminderSent           ? 1 : 0,
    notes:                   a.notes                  ?? null,
    created_at:              a.createdAt              ? String(a.createdAt) : '',
    updated_at:              a.updatedAt              ? String(a.updatedAt) : '',
    doctor_system_id:        doctorSystemId || null,
    doctor_name:             doctor?.full_name        ?? (doctorSystemId || 'Unknown Doctor'),
    doctor_specialization:   doctor?.specialization   ?? null,
    doctor_avatar_url:       null,
    facility_name:           null,
  };
}

// ─── Shared appointment list ──────────────────────────────────────────────────
// The dashboard, the right sidebar and My Appointments all show the signed-in patient's
// appointments. They share one cached list so a booking, reschedule or cancel made in one
// panel shows in every panel, and the endpoint is requested once per screen, not per panel.

/**
 * Panels that mount within this window of a load share it (one screen = one request). A screen
 * opened later refetches, so changes made elsewhere (e.g. by the doctor) show on navigation.
 * Kept short: with 3 s, a screen opened soon after the last load showed a list that predated a
 * change the doctor had just made, and nothing refreshed it.
 */
const DEDUPE_MS = 1_000;

interface Snapshot {
  /** Patient system id the list belongs to; null before the first load. */
  key:          string | null;
  appointments: Appointment[];
  /** True only until the first list for `key` arrives; refreshes keep showing the old list. */
  loading:      boolean;
  error:        string | null;
}

let snapshot: Snapshot = { key: null, appointments: [], loading: true, error: null };
let startedAt = 0;
let inFlight: { key: string; promise: Promise<void> } | null = null;
let generation = 0;
const listeners = new Set<() => void>();

function setSnapshot(next: Snapshot) {
  snapshot = next;
  listeners.forEach(l => l());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function getSnapshot(): Snapshot {
  return snapshot;
}

function loadAppointments(key: string, force: boolean): Promise<void> {
  if (!force && inFlight?.key === key) return inFlight.promise;
  const recent = snapshot.key === key && !snapshot.error && startedAt > 0 && Date.now() - startedAt < DEDUPE_MS;
  if (!force && recent) return Promise.resolve();

  if (snapshot.key !== key) {
    // Another account (or the first load): never show the previous patient's list.
    setSnapshot({ key, appointments: [], loading: true, error: null });
  }
  startedAt = Date.now();

  const gen = ++generation;
  const promise = Promise.all([
    api.get<any[]>(`/api/appointments/patient/${key}`),
    loadActiveDoctors().catch(() => [] as Doctor[]),
  ])
    .then(([apptRes, doctors]) => {
      if (gen !== generation) return;
      const doctorMap = new Map<string, Doctor>();
      doctors.forEach(d => { if (d.doctor_system_id) doctorMap.set(d.doctor_system_id, d); });
      const mapped = (apptRes.data ?? []).map((a: any) => toAppointment(a, doctorMap));
      mapped.sort((a, b) => new Date(b.scheduled_at).getTime() - new Date(a.scheduled_at).getTime());
      setSnapshot({ key, appointments: mapped, loading: false, error: null });
    })
    .catch(() => {
      if (gen !== generation) return;
      setSnapshot({ ...snapshot, loading: false, error: 'Failed to load appointments.' });
    })
    .finally(() => { if (inFlight?.promise === promise) inFlight = null; });
  inFlight = { key, promise };
  return promise;
}

/**
 * Re-fetches the shared appointment list now (call after booking, rescheduling or cancelling).
 * Every mounted useAppointments() consumer updates; resolves once the new list is in.
 */
export function invalidateAppointments(): Promise<void> {
  if (!snapshot.key) return Promise.resolve();
  return loadAppointments(snapshot.key, true);
}

export function useAppointments(): State {
  const { user, loading: userLoading, error: userError } = useCurrentUser();
  const snap = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const key  = user?.system_id ?? null;

  useEffect(() => {
    if (key) loadAppointments(key, false);
  }, [key]);

  const refetch = useCallback(() => {
    if (key) loadAppointments(key, true);
  }, [key]);

  if (!key) {
    const accountError = userLoading ? null : (userError ?? 'Could not load your account.');
    return { appointments: [], loading: !accountError, error: accountError, refetch };
  }
  // Until this patient's own list arrives, report loading rather than another account's data.
  if (snap.key !== key) return { appointments: [], loading: true, error: null, refetch };
  return { appointments: snap.appointments, loading: snap.loading, error: snap.error, refetch };
}
