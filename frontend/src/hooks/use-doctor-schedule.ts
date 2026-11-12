import { useCallback } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type { CallSessionStatus } from '@/lib/calls/types';
import { usePaged } from './use-paged';

// The doctor's own appointments, read from the paged endpoints of the doctor schedule API:
//   GET /api/doctors/appointments/search         filterable list (status, patientSystemId, from, to, order)
//   GET /api/doctors/schedule/window             every appointment that starts inside a date-time window
//   GET /api/doctors/appointments/needs-outcome  active appointments whose end passed without an outcome
// All three answer PageResponse<ScheduleEntryResponse> with one APPOINTMENT row per appointment. The deprecated
// array roots (GET /api/doctors/schedule, GET /api/doctors/appointments) are not called from here.

// One row of the schedule API (camelCase from Spring).
export interface ApiScheduleEntry {
  entryId:                string;
  type:                   'APPOINTMENT' | 'CONSULTATION';
  patientSystemId:        string;
  patientName:            string;
  doctorSystemId:         string;
  scheduledAt:            string;
  durationMinutes:        number;
  createdAt:              string;
  updatedAt:              string;
  appointmentId?:         number;
  appointmentType?:       string | null;
  appointmentStatus?:     string | null;
  reasonForVisit?:        string | null;
  /** Legacy field, always null. */
  videoConsultationLink?: string | null;
  callStatus?:            CallSessionStatus | null;
  callJoinRoute?:         string | null;
  joinWindowOpensAt?:     string | null;
  joinWindowClosesAt?:    string | null;
  /** The first automatic reminder went out. */
  reminderSent?:          boolean | null;
  /** When the doctor last sent a manual reminder (clinic wall clock), null when never. */
  reminderSentAt?:        string | null;
  notes?:                 string | null;
  facilityName?:          string | null;
  /** The number to call for a PHONE appointment. */
  contactPhone?:          string | null;
  /** On APPOINTMENT rows: the linked (non-cancelled) consultation. On CONSULTATION rows: the row itself. */
  consultationId?:        number | null;
  consultationStatus?:    string | null;
  /** Still SCHEDULED or RESCHEDULED although the appointment ended a while ago (decided by the server). */
  needsOutcome?:          boolean;
  chiefComplaint?:        string | null;
  diagnosis?:             string | null;
  treatmentPlan?:         string | null;
  followUpRequired?:      boolean | null;
  followUpDate?:          string | null;
  startedAt?:             string | null;
  completedAt?:           string | null;
}

/** The statuses the API stores for an appointment. */
export type ApiAppointmentStatus = 'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export type AppointmentStatus  = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED' | 'IN_PROGRESS';
export type ConsultationStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface ScheduleConsultation {
  consultation_id: number;
  status:          ConsultationStatus;
}

// Public interface — matches the field names components already use
export interface ScheduleEntry {
  appointment_id:          number;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  AppointmentStatus;
  patient_name:            string;
  patient_system_id:       string;
  reason_for_visit:        string | null;
  /** Legacy field, always null. Calls use call_join_route. */
  video_consultation_link: string | null;
  call_status:             CallSessionStatus | null;
  call_join_route:         string | null;
  join_window_opens_at:    string | null;
  join_window_closes_at:   string | null;
  notes:                   string | null;
  facility_name:           string | null;
  /** The number the doctor calls for a PHONE appointment; null for other types. */
  contact_phone:           string | null;
  /** The first automatic reminder went out. */
  reminder_sent:           boolean;
  /** When the doctor last sent a manual reminder (clinic wall clock); null when never. */
  reminder_sent_at:        string | null;
  /** The appointment ended without an outcome: shown as "Awaiting outcome". Decided by the server, not the device clock. */
  needs_outcome:           boolean;
  created_at:              string;
  updated_at:              string;
  /** The consultation linked to this appointment, if one has been started (a cancelled one does not count). */
  consultation:            ScheduleConsultation | null;
}

/** AppointmentResponse, as returned by the status and reschedule endpoints (only the fields used here). */
export interface ApiAppointment {
  appointmentId:       number;
  patientSystemId:     string;
  patientName?:        string | null;
  facilityName?:       string | null;
  scheduledAt:         string;
  durationMinutes:     number;
  type:                string;
  status:              string;
  reasonForVisit?:     string | null;
  contactPhone?:       string | null;
  reminderSent?:       boolean | null;
  notes?:              string | null;
  updatedAt?:          string | null;
  callStatus?:         CallSessionStatus | null;
  callJoinRoute?:      string | null;
  joinWindowOpensAt?:  string | null;
  joinWindowClosesAt?: string | null;
}

/** GET /api/doctors/appointments/stats. */
export interface AppointmentStats {
  totalAppointments: number;
  /** Active appointments that have not started yet (the upcoming count). */
  scheduledCount:    number;
  completedCount:    number;
  cancelledCount:    number;
  noShowCount:       number;
  rescheduledCount:  number;
  completionRate:    number;
  noShowRate:        number;
  needsOutcomeCount: number;
}

/** One entry of GET /api/doctors/patients/options. */
export interface DoctorPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}

const TYPES = new Set(['IN_PERSON', 'VIDEO', 'PHONE']);
const CONSULTATION_STATUSES = new Set(['DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED']);

function toScheduleEntry(e: ApiScheduleEntry, consultation: ScheduleConsultation | null): ScheduleEntry {
  return {
    appointment_id:          e.appointmentId ?? 0,
    scheduled_at:            e.scheduledAt,
    duration_minutes:        e.durationMinutes,
    type:                    (e.appointmentType && TYPES.has(e.appointmentType) ? e.appointmentType : 'IN_PERSON') as ScheduleEntry['type'],
    status:                  (e.appointmentStatus ?? 'SCHEDULED') as AppointmentStatus,
    patient_name:            e.patientName,
    patient_system_id:       e.patientSystemId,
    reason_for_visit:        e.reasonForVisit ?? null,
    video_consultation_link: null,
    call_status:             e.callStatus ?? null,
    call_join_route:         e.callJoinRoute ?? null,
    join_window_opens_at:    e.joinWindowOpensAt ?? null,
    join_window_closes_at:   e.joinWindowClosesAt ?? null,
    notes:                   e.notes ?? null,
    facility_name:           e.facilityName ?? null,
    contact_phone:           e.contactPhone ?? null,
    reminder_sent:           e.reminderSent === true,
    reminder_sent_at:        e.reminderSentAt ?? null,
    needs_outcome:           e.needsOutcome === true,
    created_at:              e.createdAt,
    updated_at:              e.updatedAt,
    consultation,
  };
}

function consultationOf(id: number | null | undefined, status: string | null | undefined): ScheduleConsultation | null {
  if (id == null || !status || !CONSULTATION_STATUSES.has(status) || status === 'CANCELLED') return null;
  return { consultation_id: id, status: status as ConsultationStatus };
}

/**
 * One entry per appointment. The linked consultation is read from the APPOINTMENT row itself (`consultationId`,
 * `consultationStatus`), which is how the paged lists carry it. The dashboard's legacy arrays also send one
 * CONSULTATION row per consultation right after its appointment; those are only a fallback for an appointment
 * row without the link, and a cancelled consultation is never the linked one.
 */
export function toScheduleEntries(rows: ApiScheduleEntry[]): ScheduleEntry[] {
  const fromRows = new Map<number, ScheduleConsultation>();
  rows.forEach(e => {
    if (e.type !== 'CONSULTATION' || e.appointmentId == null) return;
    const linked = consultationOf(e.consultationId, e.consultationStatus ?? 'DRAFT');
    if (linked) fromRows.set(e.appointmentId, linked);
  });
  return rows
    .filter(e => e.type === 'APPOINTMENT')
    .map(e => toScheduleEntry(
      e,
      consultationOf(e.consultationId, e.consultationStatus)
        ?? (e.appointmentId != null ? fromRows.get(e.appointmentId) ?? null : null),
    ));
}

/**
 * The entry after a status change or a reschedule answered with the appointment. An appointment that was just
 * closed or moved no longer awaits an outcome, and an open consultation of a cancelled or missed appointment is
 * cancelled by the server.
 */
export function applyAppointmentUpdate(entry: ScheduleEntry, updated: ApiAppointment | null | undefined): ScheduleEntry {
  if (!updated) return entry;
  const status = (updated.status ?? entry.status) as AppointmentStatus;
  const closedWithoutVisit = status === 'CANCELLED' || status === 'NO_SHOW';
  return {
    ...entry,
    scheduled_at:          updated.scheduledAt ?? entry.scheduled_at,
    duration_minutes:      updated.durationMinutes ?? entry.duration_minutes,
    type:                  (TYPES.has(updated.type) ? updated.type : entry.type) as ScheduleEntry['type'],
    status,
    reason_for_visit:      updated.reasonForVisit ?? null,
    notes:                 updated.notes ?? null,
    facility_name:         updated.facilityName !== undefined ? updated.facilityName : entry.facility_name,
    contact_phone:         updated.contactPhone !== undefined ? updated.contactPhone : entry.contact_phone,
    reminder_sent:         updated.reminderSent === true,
    call_status:           updated.callStatus ?? null,
    call_join_route:       updated.callJoinRoute ?? null,
    join_window_opens_at:  updated.joinWindowOpensAt ?? null,
    join_window_closes_at: updated.joinWindowClosesAt ?? null,
    updated_at:            updated.updatedAt ?? entry.updated_at,
    needs_outcome:         false,
    consultation:          closedWithoutVisit && entry.consultation?.status !== 'COMPLETED' ? null : entry.consultation,
  };
}

// ─── Clinic calendar helpers ("YYYY-MM-DD" dates, no zone conversion) ─────────

/** The date `days` after (or before) a "YYYY-MM-DD" date. */
export function addClinicDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** Monday of the week that contains the date. */
export function clinicWeekStart(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay(); // 0 = Sunday
  return addClinicDays(date, dow === 0 ? -6 : 1 - dow);
}

/** First day of the month that contains the date. */
export function clinicMonthStart(date: string): string {
  return `${date.slice(0, 7)}-01`;
}

/** First day of the month after the one that contains the date. */
export function clinicNextMonthStart(date: string): string {
  const [y, m] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m, 1)).toISOString().slice(0, 10);
}

/** Midnight at the start of a clinic day, as the wall-clock value the API takes for `from` and `to`. */
export function clinicDayStart(date: string): string {
  return `${date}T00:00:00`;
}

// ─── API ──────────────────────────────────────────────────────────────────────

export type ScheduleOrder = 'asc' | 'desc';

export interface DoctorAppointmentFilters {
  /** Statuses to include; empty or omitted = every status. */
  status?:          readonly ApiAppointmentStatus[];
  patientSystemId?: string | null;
  /** Inclusive lower bound on the start, clinic wall clock "YYYY-MM-DDTHH:mm:ss". */
  from?:            string | null;
  /** Exclusive upper bound on the start, clinic wall clock "YYYY-MM-DDTHH:mm:ss". */
  to?:              string | null;
  /** Default: newest first. */
  order?:           ScheduleOrder;
}

function entriesPage(page: PageResponse<ApiScheduleEntry>): PageResponse<ScheduleEntry> {
  return { ...page, content: toScheduleEntries(page.content) };
}

/** GET /api/doctors/appointments/search. Filters that are not set are not sent. */
export async function fetchDoctorAppointments(
  filters: DoctorAppointmentFilters, page: number, size: number,
): Promise<PageResponse<ScheduleEntry>> {
  return entriesPage(await fetchPage<ApiScheduleEntry>('/api/doctors/appointments/search', page, size, {
    status:          filters.status && filters.status.length > 0 ? filters.status.join(',') : undefined,
    patientSystemId: filters.patientSystemId || undefined,
    from:            filters.from || undefined,
    to:              filters.to || undefined,
    order:           filters.order,
  }));
}

/** GET /api/doctors/schedule/window: every status, soonest first unless `order` says otherwise. */
export async function fetchDoctorScheduleWindow(
  from: string, to: string, page: number, size: number, order?: ScheduleOrder,
): Promise<PageResponse<ScheduleEntry>> {
  return entriesPage(await fetchPage<ApiScheduleEntry>('/api/doctors/schedule/window', page, size, { from, to, order }));
}

/** GET /api/doctors/appointments/needs-outcome: most recently ended first. `totalElements` is the badge count. */
export async function fetchNeedsOutcome(
  patientSystemId: string | null | undefined, page: number, size: number,
): Promise<PageResponse<ScheduleEntry>> {
  return entriesPage(await fetchPage<ApiScheduleEntry>('/api/doctors/appointments/needs-outcome', page, size, {
    patientSystemId: patientSystemId || undefined,
  }));
}

/** GET /api/doctors/appointments/stats: counts per status, computed by the server. */
export async function fetchAppointmentStats(): Promise<AppointmentStats> {
  const { data } = await api.get<Partial<AppointmentStats>>('/api/doctors/appointments/stats');
  const n = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0);
  return {
    totalAppointments: n(data?.totalAppointments),
    scheduledCount:    n(data?.scheduledCount),
    completedCount:    n(data?.completedCount),
    cancelledCount:    n(data?.cancelledCount),
    noShowCount:       n(data?.noShowCount),
    rescheduledCount:  n(data?.rescheduledCount),
    completionRate:    n(data?.completionRate),
    noShowRate:        n(data?.noShowRate),
    needsOutcomeCount: n(data?.needsOutcomeCount),
  };
}

/**
 * One of the doctor's appointments as a schedule entry, or null when it is not theirs (or does not exist).
 * The appointment is looked up for its start time and patient, then read through the search endpoint so the
 * row carries the linked consultation, the reminder time and the needs-outcome flag like every list row.
 */
export async function fetchDoctorAppointmentEntry(appointmentId: number): Promise<ScheduleEntry | null> {
  const { data } = await api.get<ApiAppointment>(`/api/appointments/${appointmentId}`);
  const start = String(data?.scheduledAt ?? '');
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})/.exec(start);
  if (!m) return null;
  const from = `${m[1]}T${m[2]}:${m[3]}:00`;
  // The window is one minute wide: [from, from + 1 minute).
  const minutes = Number(m[2]) * 60 + Number(m[3]) + 1;
  const day = minutes >= 1440 ? addClinicDays(m[1], 1) : m[1];
  const rest = minutes % 1440;
  const to = `${day}T${String(Math.floor(rest / 60)).padStart(2, '0')}:${String(rest % 60).padStart(2, '0')}:00`;
  const page = await fetchDoctorAppointments({ from, to, patientSystemId: data.patientSystemId, order: 'asc' }, 0, 20);
  return page.content.find(e => e.appointment_id === appointmentId) ?? null;
}

/** GET /api/doctors/patients/options?query=: the doctor's patients whose name or id contains the text (id and name only). */
export async function searchDoctorPatients(query: string): Promise<DoctorPatientOption[]> {
  const { data } = await api.get<DoctorPatientOption[]>('/api/doctors/patients/options', {
    params: { query: query.trim().slice(0, 100) },
  });
  return Array.isArray(data) ? data : [];
}

/** Display name of one of the doctor's patients, or null when the id is not in the doctor's patient list. */
export async function fetchDoctorPatientName(patientSystemId: string): Promise<string | null> {
  const options = await searchDoctorPatients(patientSystemId);
  const match = options.find(o => o.patientSystemId === patientSystemId);
  if (!match) return null;
  return match.fullName || `${match.firstName ?? ''} ${match.lastName ?? ''}`.trim() || null;
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

export interface DoctorScheduleQuery extends DoctorAppointmentFilters {
  /**
   * Which list to read. `search` (default) is the filterable list, `window` needs `from` and `to` and returns
   * every status inside them, `needs-outcome` only takes the patient filter.
   */
  source?:       'search' | 'window' | 'needs-outcome';
  /** Rows per request, 1 to 100. */
  pageSize?:     number;
  /** How many requests one load (the first one and each "load more") makes, so a view can show more than 100 rows at once. */
  pagesPerLoad?: number;
  /** When false nothing is requested. Default true. */
  enabled?:      boolean;
}

interface State {
  entries:     ScheduleEntry[];
  /** True until the first rows for the current query arrive. */
  loading:     boolean;
  error:       string | null;
  /** Reloads from the start, keeping the rows on screen until the answer arrives. */
  refetch:     () => void;
  /** HTTP status of the failure (0 = no response), or null. */
  errorStatus: number | null;
  refreshing:  boolean;
  loadingMore: boolean;
  /** True when the server has more appointments for this query than are loaded. */
  hasMore:     boolean;
  /** Number of appointments on the server for this query. */
  total:       number;
  loadMore:    () => void;
  /** Applies the result of an action to a loaded row without another request; `null` removes the row. */
  updateEntry: (appointmentId: number, next: ScheduleEntry | null) => void;
}

// What the legacy array returned: the 200 most recent appointments. Screens that pass no query (the native
// schedule and appointment screens) get the same first load, and can ask for the next 200 with `loadMore`.
const DEFAULT_PAGE_SIZE = 100;
const DEFAULT_PAGES_PER_LOAD = 2;

function clampInt(value: number | undefined, fallback: number, min: number, max: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.min(max, Math.max(min, Math.floor(value))) : fallback;
}

/**
 * The signed-in doctor's appointments.
 *
 * - `useDoctorSchedule()`: every status, newest first (the shape and first load the native screens rely on).
 * - `useDoctorSchedule({ status, patientSystemId, from, to, order })`: the server-side filtered list.
 * - `useDoctorSchedule({ source: 'window', from, to })`: a day, week or month.
 * - `useDoctorSchedule({ source: 'needs-outcome' })`: appointments that ended without an outcome.
 *
 * A change of the query drops the rows and loads again; `refetch` keeps them on screen while it reloads.
 */
export function useDoctorSchedule(query: DoctorScheduleQuery = {}): State {
  const source       = query.source ?? 'search';
  const size         = clampInt(query.pageSize, DEFAULT_PAGE_SIZE, 1, 100);
  const pagesPerLoad = clampInt(query.pagesPerLoad, query.pageSize === undefined ? DEFAULT_PAGES_PER_LOAD : 1, 1, 10);
  const from         = query.from || null;
  const to           = query.to || null;
  const patient      = query.patientSystemId || null;
  const order        = query.order ?? null;
  const statusKey    = (query.status ?? []).join(',');
  // A window needs both bounds; until they are known nothing is requested.
  const enabled      = (query.enabled ?? true) && (source !== 'window' || (!!from && !!to));

  const paged = usePaged<ScheduleEntry>(
    async chunk => {
      const fetchOne = (page: number): Promise<PageResponse<ScheduleEntry>> => {
        if (source === 'window') return fetchDoctorScheduleWindow(from ?? '', to ?? '', page, size, order ?? undefined);
        if (source === 'needs-outcome') return fetchNeedsOutcome(patient, page, size);
        return fetchDoctorAppointments({
          status: statusKey ? (statusKey.split(',') as ApiAppointmentStatus[]) : undefined,
          patientSystemId: patient, from, to, order: order ?? undefined,
        }, page, size);
      };
      const first = chunk * pagesPerLoad;
      let content: ScheduleEntry[] = [];
      let totalElements = 0;
      let totalPages = 0;
      for (let i = 0; i < pagesPerLoad; i++) {
        const page = await fetchOne(first + i);
        content = content.concat(page.content);
        totalElements = page.totalElements;
        totalPages = page.totalPages;
        if (first + i + 1 >= totalPages) break;
      }
      return { content, page: chunk, size: size * pagesPerLoad, totalElements, totalPages: Math.ceil(totalPages / pagesPerLoad) };
    },
    [source, from, to, patient, order, statusKey, size, pagesPerLoad],
    { enabled, errorMessage: 'Failed to load schedule.', keyOf: e => e.appointment_id },
  );

  const { setItems } = paged;
  const updateEntry = useCallback((appointmentId: number, next: ScheduleEntry | null) => {
    setItems(prev => (next
      ? prev.map(e => (e.appointment_id === appointmentId ? next : e))
      : prev.filter(e => e.appointment_id !== appointmentId)));
  }, [setItems]);

  return {
    entries:     paged.items,
    loading:     paged.loading,
    error:       paged.error,
    refetch:     paged.refetch,
    errorStatus: paged.errorStatus,
    refreshing:  paged.refreshing,
    loadingMore: paged.loadingMore,
    hasMore:     paged.hasMore,
    total:       paged.total,
    loadMore:    paged.loadMore,
    updateEntry,
  };
}
