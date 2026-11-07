import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { api } from '@/lib/auth/auth.interceptor';

export interface Doctor {
  doctor_id:        number;
  doctor_system_id: string;
  user_id:          number;
  specialization:   string | null;
  license_no:       string | null;
  bio:              string | null;
  is_active:        number;
  full_name:        string;
  first_name:       string;
  last_name:        string;
  email:            string;
  phone:            string | null;
  avatar_url:       string | null;
}

/** A leading "Dr." / "Dr" title, any case. */
const DR_TITLE = /^dr(\.\s*|\s+|$)/i;

/**
 * "Dr. First Last" for a doctor, without doubling a title the stored name already carries
 * (some seed rows store first_name as "Dr. Michael"; "Dr Michael" and "dr." count too).
 */
export function doctorDisplayName(firstName: string | null | undefined, lastName: string | null | undefined): string {
  const name = `${firstName ?? ''} ${lastName ?? ''}`.replace(/\s+/g, ' ').trim();
  if (!name) return '';
  return DR_TITLE.test(name) ? name : `Dr. ${name}`;
}

/**
 * Collapses a repeated leading title in an already-built name ("Dr. Dr. Michael Kariuki" ->
 * "Dr. Michael Kariuki"), for names that come from hooks that prefix "Dr." unconditionally.
 */
export function tidyDoctorName(name: string | null | undefined): string {
  const n = (name ?? '').trim();
  return DR_TITLE.test(n) ? n.replace(/^(dr(\.\s*|\s+))+/i, 'Dr. ').trim() : n;
}

/** First letter of a doctor's name for an avatar, skipping a leading "Dr." title. */
export function doctorInitial(name: string | null | undefined): string {
  const bare = (name ?? '').trim().replace(DR_TITLE, '').trim();
  return bare[0]?.toUpperCase() ?? 'D';
}

function toDoctor(d: any): Doctor {
  const firstName = d.firstName ?? d.first_name ?? '';
  const lastName  = d.lastName  ?? d.last_name  ?? '';
  return {
    doctor_id:        d.userId          ?? d.user_id          ?? 0,
    doctor_system_id: d.doctorSystemId  ?? d.doctor_system_id ?? '',
    user_id:          d.userId          ?? d.user_id          ?? 0,
    specialization: d.specialization             ?? null,
    license_no:     d.licenseNo    ?? d.license_no ?? null,
    bio:            d.bio                        ?? null,
    is_active:      d.isActive     ? 1 : 0,
    full_name:      doctorDisplayName(firstName, lastName),
    first_name:     firstName,
    last_name:      lastName,
    email:          d.email                      ?? '',
    phone:          d.phone                      ?? null,
    avatar_url:     null,
  };
}

// ─── Shared active-doctor list ────────────────────────────────────────────────
// The booking form, the appointment list and the dashboard panels all need this list, so
// mounted consumers share one request and its result for STALE_MS.

const STALE_MS = 60_000;

interface State {
  doctors: Doctor[];
  loading: boolean;
  error:   string | null;
}

let snapshot: State = { doctors: [], loading: true, error: null };
let loadedAt = 0;
let inFlight: Promise<Doctor[]> | null = null;
const listeners = new Set<() => void>();

function setSnapshot(next: State) {
  snapshot = next;
  listeners.forEach(l => l());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function getSnapshot(): State {
  return snapshot;
}

/**
 * The active doctors (GET /api/doctors/profile/list/active), from the shared cache when it is
 * fresh. Concurrent callers share one request. Rejects with the axios error on failure.
 */
export function loadActiveDoctors(force = false): Promise<Doctor[]> {
  if (inFlight) return inFlight;
  if (!force && loadedAt && Date.now() - loadedAt < STALE_MS && !snapshot.error) {
    return Promise.resolve(snapshot.doctors);
  }
  if (!snapshot.doctors.length) setSnapshot({ ...snapshot, loading: true, error: null });
  inFlight = api.get<any[]>('/api/doctors/profile/list/active')
    .then(({ data }) => {
      const doctors = (data ?? []).map(toDoctor);
      loadedAt = Date.now();
      setSnapshot({ doctors, loading: false, error: null });
      return doctors;
    })
    .catch((err) => {
      console.error('useDoctors error:', err?.response?.status, err?.message);
      setSnapshot({ doctors: snapshot.doctors, loading: false, error: 'Failed to load doctors.' });
      throw err;
    })
    .finally(() => { inFlight = null; });
  return inFlight;
}

export function useDoctors(): State & { refetch: () => void } {
  const state = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    loadActiveDoctors().catch(() => { /* surfaced through state.error */ });
  }, []);

  const refetch = useCallback(() => {
    loadActiveDoctors(true).catch(() => { /* surfaced through state.error */ });
  }, []);

  return { ...state, refetch };
}
