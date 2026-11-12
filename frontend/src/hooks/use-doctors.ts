import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { fetchPage, hasNextPage, mapPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { type PagedState, usePaged } from './use-paged';

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

// ─── Doctor directory ─────────────────────────────────────────────────────────
// GET /api/doctors/profile/directory lists the doctors who can be booked (active and approved), by name, as a
// paged envelope. `query` matches the name or the specialization on the server.

const DIRECTORY_URL = '/api/doctors/profile/directory';

/** One page of the directory, as `Doctor` rows. Rejects with the axios error on failure. */
export function fetchDoctorDirectory(page: number, size: number, query?: string): Promise<PageResponse<Doctor>> {
  return fetchPage<unknown>(DIRECTORY_URL, page, size, { query: query?.trim() || undefined })
    .then(result => mapPage(result, toDoctor));
}

/** Longest search text the directory accepts. */
export const DOCTOR_SEARCH_MAX_LENGTH = 100;

/**
 * The directory filtered by a search text (name or specialization), loaded page by page:
 * `const directory = useDoctorDirectory(query); directory.items; directory.loadMore()`.
 * A change of `query` starts again from the first page. Debounce the text before passing it.
 */
export function useDoctorDirectory(query: string, options: { pageSize?: number; enabled?: boolean } = {}): PagedState<Doctor> {
  const text = query.trim().slice(0, DOCTOR_SEARCH_MAX_LENGTH);
  return usePaged<Doctor>(
    (page, size) => fetchDoctorDirectory(page, size, text),
    [text],
    {
      pageSize:     options.pageSize,
      enabled:      options.enabled,
      keyOf:        d => d.doctor_system_id,
      errorMessage: 'Failed to load doctors.',
    },
  );
}

// ─── Shared doctor list ───────────────────────────────────────────────────────
// Pickers, the appointment list and the clinical lists need doctors by id, so mounted consumers share one
// load and its result for STALE_MS. The list is the directory without a filter, read page by page up to
// LIST_LIMIT rows (the size of the array endpoint it replaces); a clinic with more doctors is searched through
// `useDoctorDirectory` instead.

const STALE_MS = 60_000;
const LIST_LIMIT = 200;

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

async function loadDirectoryList(): Promise<Doctor[]> {
  const doctors: Doctor[] = [];
  const seen = new Set<string>();
  for (let page = 0; doctors.length < LIST_LIMIT; page++) {
    const result = await fetchDoctorDirectory(page, MAX_PAGE_SIZE);
    for (const d of result.content) {
      if (!d.doctor_system_id || seen.has(d.doctor_system_id)) continue;
      seen.add(d.doctor_system_id);
      doctors.push(d);
    }
    if (!hasNextPage(result) || result.content.length === 0) break;
  }
  return doctors.slice(0, LIST_LIMIT);
}

/**
 * The bookable doctors (GET /api/doctors/profile/directory, first 200 by name), from the shared cache when
 * it is fresh. Concurrent callers share one load. Rejects with the axios error on failure.
 */
export function loadActiveDoctors(force = false): Promise<Doctor[]> {
  if (inFlight) return inFlight;
  if (!force && loadedAt && Date.now() - loadedAt < STALE_MS && !snapshot.error) {
    return Promise.resolve(snapshot.doctors);
  }
  if (!snapshot.doctors.length) setSnapshot({ ...snapshot, loading: true, error: null });
  inFlight = loadDirectoryList()
    .then(doctors => {
      loadedAt = Date.now();
      setSnapshot({ doctors, loading: false, error: null });
      return doctors;
    })
    .catch((err) => {
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
