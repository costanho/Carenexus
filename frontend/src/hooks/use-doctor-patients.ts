import { useCallback, useEffect, useMemo, useState } from 'react';
import { DEFAULT_PAGE_SIZE, pageParams, toPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

// The signed-in doctor's patients (api/be-doctor-directory-patients.md).
//
//   useDoctorPatients()        legacy array (GET /api/doctors/patients, at most 200 rows, snake_case view model).
//                              Kept for the screens that already use it (pickers, Messages, the native list).
//   useDoctorPatientSearch()   the paged list for the Patients screen (GET /api/doctors/patients/search):
//                              server search, filters, sort, "Load more" and the summary counts.
//   useDoctorPatientOptions()  id and name only (GET /api/doctors/patients/options), for pickers and name lookups.

// ─── API shapes (camelCase, straight from the server) ─────────────────────────

export type HealthStatus = 'STABLE' | 'MONITOR' | 'CRITICAL';
export type PatientGender = 'MALE' | 'FEMALE' | 'OTHER';
export type PatientSort = 'name' | 'nextAppointment' | 'healthStatus' | 'lastVisit';
export type PatientAppointmentType = 'IN_PERSON' | 'VIDEO' | 'PHONE';
export type PatientAppointmentStatus = 'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export const HEALTH_STATUSES: readonly HealthStatus[] = ['STABLE', 'MONITOR', 'CRITICAL'];
export const PATIENT_GENDERS: readonly PatientGender[] = ['MALE', 'FEMALE', 'OTHER'];
export const PATIENT_SORTS: readonly PatientSort[] = ['name', 'nextAppointment', 'healthStatus', 'lastVisit'];

/** Longest search text the server accepts (after trimming). */
export const PATIENT_QUERY_MAX = 100;

/**
 * One row of `recentAppointments`: an appointment between this doctor and the patient. The server leaves the
 * doctor, facility and contact-phone fields of the general appointment shape empty in this list, so they are
 * not part of this type.
 */
export interface DoctorPatientAppointment {
  appointmentId:   number;
  patientSystemId: string;
  patientName:     string | null;
  doctorSystemId:  string;
  facilityId:      number | null;
  /** Clinic wall-clock date-time, "YYYY-MM-DDTHH:mm:ss". */
  scheduledAt:     string;
  durationMinutes: number;
  type:            PatientAppointmentType;
  status:          PatientAppointmentStatus;
  reasonForVisit:  string | null;
  notes:           string | null;
}

/** `DoctorPatientResponse`: a row of the paged list, or the patient detail. */
export interface DoctorPatientDto {
  patientSystemId: string;
  userSystemId:    string;
  firstName:       string;
  lastName:        string;
  email:           string | null;
  phone:           string | null;
  /** Calendar date "YYYY-MM-DD". */
  dateOfBirth:     string | null;
  gender:          PatientGender | null;
  bloodType:       string | null;
  /** A string holding a JSON array; read it with `toStringList`. */
  allergies:         string | null;
  /** Same encoding as `allergies`. */
  chronicConditions: string | null;
  healthStatus:    HealthStatus;
  /** Detail only (null in list rows): the note, time and author of the latest health-status change. */
  healthStatusNote:              string | null;
  healthStatusUpdatedAt:         string | null;
  healthStatusUpdatedBySystemId: string | null;
  healthStatusUpdatedByName:     string | null;
  /** Appointments with this doctor in any status, cancelled included. */
  totalAppointments:         number;
  upcomingAppointmentCount:  number;
  completedAppointmentCount: number;
  cancelledAppointmentCount: number;
  noShowCount:               number;
  rescheduledCount:          number;
  completedVisits:           number;
  /** Start of the next scheduled appointment with this doctor (clinic wall clock), or null when there is none. */
  nextAppointmentAt:   string | null;
  nextAppointmentId:   number | null;
  nextAppointmentType: PatientAppointmentType | null;
  /** Latest completed visit with this doctor (clinic wall clock), or null. */
  lastVisitAt:         string | null;
  /** @deprecated alias of `lastVisitAt`. */
  lastAppointmentDate: string | null;
  /** The five most recent appointments with this doctor. Null in rows of the paged list. */
  recentAppointments:  DoctorPatientAppointment[] | null;
  patientCreatedAt:    string;
}

/** Patients per health status over the doctor's whole patient set (filters and paging do not change it). */
export interface DoctorPatientSummary {
  total:    number;
  stable:   number;
  monitor:  number;
  critical: number;
}

export interface DoctorPatientPage extends PageResponse<DoctorPatientDto> {
  summary: DoctorPatientSummary;
}

export interface DoctorPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}

export interface DoctorPatientFilters {
  /** Matches name, email, phone and patient id. Blank = no filter. */
  query?:        string;
  healthStatus?: HealthStatus | 'ALL' | null;
  gender?:       PatientGender | 'ALL' | null;
  /** Default `name`. */
  sort?:         PatientSort;
}

/** "First Last" of a patient row or option; "Unknown patient" when the account has no name. */
export function patientFullName(patient: { firstName?: string | null; lastName?: string | null }): string {
  return `${patient.firstName ?? ''} ${patient.lastName ?? ''}`.trim() || 'Unknown patient';
}

/** Accepts a JSON array string, a plain array, or a comma-separated string. */
export function toStringList(raw: unknown): string[] {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw.map(String).filter(Boolean);
  if (typeof raw !== 'string') return [];
  const trimmed = raw.trim();
  if (!trimmed) return [];
  if (trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed);
      return Array.isArray(parsed) ? parsed.map(String).filter(Boolean) : [];
    } catch {
      return [];
    }
  }
  return trimmed.split(',').map(x => x.trim()).filter(Boolean);
}

// ─── Requests ─────────────────────────────────────────────────────────────────

function cleanQuery(query: string | null | undefined): string {
  return (query ?? '').trim().slice(0, PATIENT_QUERY_MAX);
}

function count(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0;
}

function toSummary(raw: unknown): DoctorPatientSummary {
  const s = (raw && typeof raw === 'object' ? raw : {}) as Partial<Record<keyof DoctorPatientSummary, unknown>>;
  return { total: count(s.total), stable: count(s.stable), monitor: count(s.monitor), critical: count(s.critical) };
}

/** GET /api/doctors/patients/search: one page of the doctor's patients plus the summary counts. */
export async function searchDoctorPatients(
  filters: DoctorPatientFilters,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<DoctorPatientPage> {
  const paging = pageParams(page, size);
  const params: Record<string, string | number> = { ...paging };
  const query = cleanQuery(filters.query);
  if (query) params.query = query;
  if (filters.healthStatus && filters.healthStatus !== 'ALL') params.healthStatus = filters.healthStatus;
  if (filters.gender && filters.gender !== 'ALL') params.gender = filters.gender;
  if (filters.sort) params.sort = filters.sort;
  const { data } = await api.get<unknown>('/api/doctors/patients/search', { params });
  const envelope = toPage<DoctorPatientDto>(data, paging.page, paging.size);
  return { ...envelope, summary: toSummary((data as { summary?: unknown } | null)?.summary) };
}

/** GET /api/doctors/patients/options: id and name of the doctor's patients, ordered by name (at most 500). */
export async function listDoctorPatientOptions(query?: string): Promise<DoctorPatientOption[]> {
  const q = cleanQuery(query);
  const { data } = await api.get<DoctorPatientOption[]>('/api/doctors/patients/options', {
    params: q ? { query: q } : undefined,
  });
  return Array.isArray(data) ? data : [];
}

// ─── Paged list (Patients screen) ─────────────────────────────────────────────

/** Follows `value` after it has stayed the same for `delayMs`; an emptied value is applied at once. */
function useDebouncedText(value: string, delayMs: number): string {
  const [debounced, setDebounced] = useState(value);
  const immediate = delayMs <= 0 || value === '';
  useEffect(() => {
    if (immediate) { setDebounced(value); return; }
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs, immediate]);
  return immediate ? value : debounced;
}

export interface DoctorPatientSearchOptions {
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?:   number;
  /** When false nothing is requested. Default true. */
  enabled?:    boolean;
  /** How long the search text must rest before it is sent. Default 300 ms. */
  debounceMs?: number;
}

export interface DoctorPatientSearchState extends PagedState<DoctorPatientDto> {
  /** Counts over all of the doctor's patients; null until the first page has arrived. */
  summary: DoctorPatientSummary | null;
  /** The search text the rows on screen were requested with (trimmed, after the debounce). */
  appliedQuery: string;
  /** True between typing and the request for the new text being sent. */
  debouncing: boolean;
}

/**
 * The doctor's patients, page by page, searched, filtered and sorted by the server.
 *
 * ```ts
 * const list = useDoctorPatientSearch({ query: text, healthStatus, gender, sort });
 * return <PagedList state={list} keyOf={p => p.patientSystemId} ... />;
 * ```
 * `query` may be bound straight to a text input: the hook waits until typing pauses before asking the server.
 * A change of any filter starts again from the first page. `summary` ignores the filters.
 */
export function useDoctorPatientSearch(
  filters: DoctorPatientFilters = {},
  options: DoctorPatientSearchOptions = {},
): DoctorPatientSearchState {
  const typed   = cleanQuery(filters.query);
  const query   = useDebouncedText(typed, options.debounceMs ?? 300);
  const health  = filters.healthStatus && filters.healthStatus !== 'ALL' ? filters.healthStatus : null;
  const gender  = filters.gender && filters.gender !== 'ALL' ? filters.gender : null;
  const sort    = filters.sort ?? 'name';
  const [summary, setSummary] = useState<DoctorPatientSummary | null>(null);

  const paged = usePaged<DoctorPatientDto>(
    async (page, size) => {
      const result = await searchDoctorPatients({ query, healthStatus: health, gender, sort }, page, size);
      // The summary covers the whole patient set whatever the filters are, so any response may update it.
      setSummary(result.summary);
      return result;
    },
    [query, health, gender, sort],
    {
      pageSize:     options.pageSize,
      enabled:      options.enabled,
      keyOf:        p => p.patientSystemId,
      errorMessage: 'Failed to load patients.',
    },
  );

  return { ...paged, summary, appliedQuery: query, debouncing: typed !== query };
}

// ─── Options (pickers and name lookups) ───────────────────────────────────────

export interface DoctorPatientOptionsState {
  /** The doctor's patients ordered by last name, first name. */
  options: DoctorPatientOption[];
  loading: boolean;
  error:   string | null;
  refetch: () => void;
  /** Full name of one of the listed patients, or null when the id is not in the list. */
  nameOf:  (patientSystemId: string | null | undefined) => string | null;
}

/**
 * Id and name of the doctor's patients, for pickers and for showing a patient's name next to an id.
 * Pass `query` to narrow the list on the server (a doctor with more than 500 patients needs it).
 */
export function useDoctorPatientOptions(query = '', options: { enabled?: boolean } = {}): DoctorPatientOptionsState {
  const applied = useDebouncedText(cleanQuery(query), 300);
  const { data, loading, error, refetch } = useResource(
    () => listDoctorPatientOptions(applied),
    [applied],
    { enabled: options.enabled, errorMessage: 'Failed to load patients.' },
  );

  const list = useMemo(() => data ?? [], [data]);
  const names = useMemo(() => {
    const map = new Map<string, string>();
    for (const o of list) map.set(o.patientSystemId, (o.fullName ?? '').trim() || patientFullName(o));
    return map;
  }, [list]);
  const nameOf = useCallback(
    (patientSystemId: string | null | undefined) => (patientSystemId ? names.get(patientSystemId) ?? null : null),
    [names],
  );

  return { options: list, loading, error, refetch, nameOf };
}

// ─── Legacy array (existing consumers) ────────────────────────────────────────

export interface DoctorPatient {
  // Patient record
  patient_system_id:  string;
  /** @deprecated numeric ids are not exposed by the API; always 0. Use patient_system_id. */
  patient_id:         number;
  /** @deprecated numeric ids are not exposed by the API; always 0. */
  user_id:            number;
  date_of_birth:      string | null;
  gender:             'MALE' | 'FEMALE' | 'OTHER' | null;
  blood_type:         string | null;
  allergies:          string[];
  chronic_conditions: string[];
  health_status:      'STABLE' | 'MONITOR' | 'CRITICAL' | null;
  patient_created_at: string;
  // Enriched from users table
  first_name:  string;
  last_name:   string;
  full_name:   string;
  email:       string | null;
  phone:       string | null;
  avatar_url:  string | null;
  // Computed from this doctor's appointments
  /** Appointments with this doctor in any status. */
  appointment_count:   number;
  /** Latest completed visit with this doctor (same value as `last_visit_at`). */
  last_appointment_at: string | null;
  /** Latest completed visit with this doctor, clinic wall clock; null when there has been none. */
  last_visit_at:       string | null;
  completed_visits:    number;
  /** Start of the next scheduled appointment with this doctor, clinic wall clock; null when there is none. */
  next_appointment_at:   string | null;
  next_appointment_id:   number | null;
  next_appointment_type: PatientAppointmentType | null;
}

interface State {
  patients: DoctorPatient[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
}

// GET /api/doctors/patients → DoctorPatientResponse[] (camelCase)
function toDoctorPatient(p: any): DoctorPatient {
  const first = p.firstName ?? '';
  const last  = p.lastName  ?? '';
  const lastVisit = p.lastVisitAt ?? p.lastAppointmentDate ?? null;
  return {
    patient_system_id:   p.patientSystemId ?? '',
    patient_id:          0,
    user_id:             0,
    date_of_birth:       p.dateOfBirth ? String(p.dateOfBirth) : null,
    gender:              p.gender       ?? null,
    blood_type:          p.bloodType    ?? null,
    allergies:           toStringList(p.allergies),
    chronic_conditions:  toStringList(p.chronicConditions),
    health_status:       p.healthStatus ?? null,
    patient_created_at:  p.patientCreatedAt ? String(p.patientCreatedAt) : '',
    first_name:          first,
    last_name:           last,
    full_name:           `${first} ${last}`.trim() || 'Unknown Patient',
    email:               p.email ?? null,
    phone:               p.phone ?? null,
    avatar_url:          null,
    appointment_count:   p.totalAppointments ?? 0,
    last_appointment_at: lastVisit ? String(lastVisit) : null,
    last_visit_at:       lastVisit ? String(lastVisit) : null,
    completed_visits:    p.completedVisits ?? p.completedAppointmentCount ?? 0,
    next_appointment_at:   p.nextAppointmentAt ? String(p.nextAppointmentAt) : null,
    next_appointment_id:   typeof p.nextAppointmentId === 'number' ? p.nextAppointmentId : null,
    next_appointment_type: p.nextAppointmentType ?? null,
  };
}

/**
 * The doctor's patients as one array (GET /api/doctors/patients, capped by the server at 200 rows).
 * New screens use `useDoctorPatientSearch` for a list and `useDoctorPatientOptions` for pickers and names.
 */
export function useDoctorPatients(): State {
  const [patients, setPatients] = useState<DoctorPatient[]>([]);
  const [loading,  setLoading]  = useState(true);
  const [error,    setError]    = useState<string | null>(null);
  const [tick,     setTick]     = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.get<any[]>('/api/doctors/patients')
      .then(({ data }) => {
        if (cancelled) return;
        const merged = (Array.isArray(data) ? data : []).map(toDoctorPatient);
        // Sort by most recently seen
        merged.sort((a, b) => {
          if (!a.last_appointment_at) return  1;
          if (!b.last_appointment_at) return -1;
          return new Date(b.last_appointment_at).getTime() - new Date(a.last_appointment_at).getTime();
        });
        setPatients(merged);
        setError(null);
      })
      .catch(() => { if (!cancelled) setError('Failed to load patients.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [tick]);

  return { patients, loading, error, refetch: () => setTick(t => t + 1) };
}
