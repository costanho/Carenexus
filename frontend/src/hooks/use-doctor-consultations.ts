import { useCallback, useMemo } from 'react';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import { clinicToday, isAfterClinicNow } from '@/lib/time/clinic';
import { useCurrentUser } from './use-current-user';
import { doctorDisplayName } from './use-doctors';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// The doctor's consultations: the canonical paged list, the lookups an editor needs and the write calls
// (create, save notes, start, complete, cancel). Shapes follow the consultations API contract; the snake_case
// `DoctorConsultation` view model is kept for the screens that already render it (web and native).

// ─── API shapes (camelCase, straight from the API) ────────────────────────────

export type ConsultationStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
export type ConsultationAppointmentType = 'IN_PERSON' | 'VIDEO' | 'PHONE';
export type ConsultationAppointmentStatus = 'SCHEDULED' | 'RESCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';

export interface ConsultationDto {
  consultationId:          number;
  appointmentId:           number;
  appointmentType:         ConsultationAppointmentType | null;
  appointmentStatus:       ConsultationAppointmentStatus | null;
  patientSystemId:         string;
  /** Always null: names come from GET /api/doctors/patients/options. */
  patientName:             string | null;
  doctorSystemId:          string;
  chiefComplaint:          string | null;
  historyOfPresentIllness: string | null;
  physicalExamination:     string | null;
  clinicalNotes:           string | null;
  diagnosis:               string | null;
  treatmentPlan:           string | null;
  followUpInstructions:    string | null;
  followUpRequired:        boolean;
  /** "YYYY-MM-DD"; null while no follow-up is required. */
  followUpDate:            string | null;
  durationMinutes:         number | null;
  status:                  ConsultationStatus;
  /** Clinic wall-clock date-times (no offset). */
  startedAt:               string | null;
  completedAt:             string | null;
  createdAt:               string;
  /** The CONSULT medical record created for this consultation, when there is one. */
  medicalRecordId:         number | null;
}

export interface CreateConsultationBody {
  appointmentId:            number;
  patientSystemId?:         string;
  status?:                  'DRAFT' | 'IN_PROGRESS';
  chiefComplaint?:          string;
  historyOfPresentIllness?: string;
  physicalExamination?:     string;
  clinicalNotes?:           string;
  diagnosis?:               string;
  treatmentPlan?:           string;
  followUpInstructions?:    string;
  followUpRequired?:        boolean;
  followUpDate?:            string | null;
  durationMinutes?:         number;
}

export type UpdateConsultationBody = Omit<CreateConsultationBody, 'appointmentId' | 'patientSystemId' | 'status'>
  & { status?: ConsultationStatus };

export interface ConsultationStatsDto {
  totalConsultations: number;
  draftCount:         number;
  inProgressCount:    number;
  completedCount:     number;
  cancelledCount:     number;
  completionRate:     number;
}

/** One entry of GET /api/doctors/patients/options. */
export interface DoctorPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}

/** The appointment a consultation documents, as far as the consultation screens need it. */
export interface ConsultationAppointment {
  appointmentId:      number;
  patientSystemId:    string;
  patientName:        string | null;
  doctorSystemId:     string | null;
  /** Clinic wall-clock start, "YYYY-MM-DDTHH:mm:ss". */
  scheduledAt:        string;
  durationMinutes:    number | null;
  appointmentType:    ConsultationAppointmentType | null;
  appointmentStatus:  ConsultationAppointmentStatus | null;
  reasonForVisit:     string | null;
  /** The appointment's one non-cancelled consultation; null when it has none. Filled from schedule rows only. */
  consultationId:     number | null;
  consultationStatus: ConsultationStatus | null;
}

export interface ConsultationListFilters {
  /** The authoring doctor. Always the signed-in doctor on the doctor's own list. */
  doctorSystemId:   string;
  status?:          ConsultationStatus | null;
  patientSystemId?: string | null;
}

// ─── Small readers ────────────────────────────────────────────────────────────

const STATUSES: readonly ConsultationStatus[] = ['DRAFT', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
const APPOINTMENT_TYPES: readonly ConsultationAppointmentType[] = ['IN_PERSON', 'VIDEO', 'PHONE'];
const APPOINTMENT_STATUSES: readonly ConsultationAppointmentStatus[] = ['SCHEDULED', 'RESCHEDULED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

function oneOf<T extends string>(values: readonly T[], raw: unknown): T | null {
  return typeof raw === 'string' && (values as readonly string[]).includes(raw) ? (raw as T) : null;
}

function text(raw: unknown): string | null {
  return typeof raw === 'string' && raw.trim() !== '' ? raw : null;
}

function numeric(raw: unknown): number | null {
  return typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
}

type Raw = Record<string, unknown>;

function asRaw(data: unknown): Raw {
  return data && typeof data === 'object' ? (data as Raw) : {};
}

function toConsultationDto(data: unknown): ConsultationDto {
  const r = asRaw(data);
  const followUpRequired = r.followUpRequired === true;
  return {
    consultationId:          numeric(r.consultationId) ?? 0,
    appointmentId:           numeric(r.appointmentId) ?? 0,
    appointmentType:         oneOf(APPOINTMENT_TYPES, r.appointmentType),
    appointmentStatus:       oneOf(APPOINTMENT_STATUSES, r.appointmentStatus),
    patientSystemId:         text(r.patientSystemId) ?? '',
    patientName:             text(r.patientName),
    doctorSystemId:          text(r.doctorSystemId) ?? '',
    chiefComplaint:          text(r.chiefComplaint),
    historyOfPresentIllness: text(r.historyOfPresentIllness),
    physicalExamination:     text(r.physicalExamination),
    clinicalNotes:           text(r.clinicalNotes),
    diagnosis:               text(r.diagnosis),
    treatmentPlan:           text(r.treatmentPlan),
    followUpInstructions:    text(r.followUpInstructions),
    followUpRequired,
    followUpDate:            followUpRequired && text(r.followUpDate) ? String(r.followUpDate).slice(0, 10) : null,
    durationMinutes:         numeric(r.durationMinutes),
    status:                  oneOf(STATUSES, r.status) ?? 'DRAFT',
    startedAt:               text(r.startedAt),
    completedAt:             text(r.completedAt),
    createdAt:               text(r.createdAt) ?? '',
    medicalRecordId:         numeric(r.medicalRecordId),
  };
}

// ─── Consultation endpoints ───────────────────────────────────────────────────

/** GET /api/consultations: one page, newest first. Only filters that are set are sent. */
export async function listConsultations(
  filters: ConsultationListFilters,
  page: number,
  size: number,
): Promise<PageResponse<ConsultationDto>> {
  const result = await fetchPage<unknown>('/api/consultations', page, size, {
    doctorSystemId:  filters.doctorSystemId,
    status:          filters.status ?? undefined,
    patientSystemId: filters.patientSystemId ?? undefined,
  });
  return { ...result, content: result.content.map(toConsultationDto) };
}

/** GET /api/consultations/{id}. */
export async function getConsultation(consultationId: number): Promise<ConsultationDto> {
  const { data } = await api.get<unknown>(`/api/consultations/${consultationId}`);
  return toConsultationDto(data);
}

/** GET /api/consultations/appointment/{appointmentId}: every consultation of the appointment, newest first. */
export async function getConsultationsForAppointment(appointmentId: number): Promise<ConsultationDto[]> {
  const { data } = await api.get<unknown>(`/api/consultations/appointment/${appointmentId}`);
  return Array.isArray(data) ? data.map(toConsultationDto) : [];
}

/**
 * POST /api/consultations. `idempotencyKey` is one key per editor open (`newIdempotencyKey()`): reuse it when
 * the same submit is retried and renew it after a success, so a retry cannot open a second consultation.
 */
export async function createConsultation(body: CreateConsultationBody, idempotencyKey: string): Promise<ConsultationDto> {
  const { data } = await api.post<unknown>('/api/consultations', body, withIdempotencyKey(idempotencyKey));
  return toConsultationDto(data);
}

/**
 * PUT /api/consultations/{id}: saves the notes. A partial update: an absent field is left unchanged, a blank
 * text clears it, `followUpRequired: false` clears the follow-up date. Refused (409) once the consultation is
 * completed or cancelled.
 */
export async function saveConsultation(consultationId: number, body: UpdateConsultationBody): Promise<ConsultationDto> {
  const { data } = await api.put<unknown>(`/api/consultations/${consultationId}`, body);
  return toConsultationDto(data);
}

async function mark(consultationId: number, transition: 'in-progress' | 'completed' | 'cancelled'): Promise<ConsultationDto> {
  const { data } = await api.patch<unknown>(`/api/doctors/consultations/${consultationId}/mark-${transition}`);
  return toConsultationDto(data);
}

/** DRAFT to IN_PROGRESS. */
export function startConsultation(consultationId: number): Promise<ConsultationDto> {
  return mark(consultationId, 'in-progress');
}

/**
 * To COMPLETED. The server requires a chief complaint and a diagnosis (400 otherwise) and answers with the
 * appointment status and the linked medical record as they are after completion.
 */
export function completeConsultation(consultationId: number): Promise<ConsultationDto> {
  return mark(consultationId, 'completed');
}

/** To CANCELLED. The notes are kept but can no longer be edited. */
export function cancelConsultation(consultationId: number): Promise<ConsultationDto> {
  return mark(consultationId, 'cancelled');
}

/** GET /api/doctors/consultations/stats: counts per status of the signed-in doctor's consultations. */
export async function getConsultationStats(): Promise<ConsultationStatsDto> {
  const { data } = await api.get<unknown>('/api/doctors/consultations/stats');
  const r = asRaw(data);
  return {
    totalConsultations: numeric(r.totalConsultations) ?? 0,
    draftCount:         numeric(r.draftCount) ?? 0,
    inProgressCount:    numeric(r.inProgressCount) ?? 0,
    completedCount:     numeric(r.completedCount) ?? 0,
    cancelledCount:     numeric(r.cancelledCount) ?? 0,
    completionRate:     numeric(r.completionRate) ?? 0,
  };
}

// ─── Patient names (GET /api/doctors/patients/options) ────────────────────────

const OPTIONS_TTL_MS = 60_000;
let optionsCache: { owner: string; at: number; request: Promise<DoctorPatientOption[]> } | null = null;

function toPatientOption(data: unknown): DoctorPatientOption | null {
  const r = asRaw(data);
  const patientSystemId = text(r.patientSystemId);
  if (!patientSystemId) return null;
  const firstName = text(r.firstName) ?? '';
  const lastName  = text(r.lastName) ?? '';
  const fullName  = text(r.fullName) ?? `${firstName} ${lastName}`.trim();
  return { patientSystemId, firstName, lastName, fullName };
}

/**
 * The signed-in doctor's patients as id and name pairs, for name lookups and the patient filter. Shared between
 * the screens that ask within a minute; `owner` is the doctor the list belongs to, so one doctor's list is never
 * handed to another account in the same tab.
 */
export function fetchPatientOptions(owner: string, force = false): Promise<DoctorPatientOption[]> {
  const cached = optionsCache;
  if (!force && cached && cached.owner === owner && Date.now() - cached.at < OPTIONS_TTL_MS) return cached.request;
  const request = api.get<unknown>('/api/doctors/patients/options').then(({ data }) => (
    (Array.isArray(data) ? data : [])
      .map(toPatientOption)
      .filter((o): o is DoctorPatientOption => o !== null)
  ));
  const entry = { owner, at: Date.now(), request };
  optionsCache = entry;
  // A failed request is not kept, so the next caller asks again.
  request.catch(() => { if (optionsCache === entry) optionsCache = null; });
  return request;
}

function patientNameMap(options: readonly DoctorPatientOption[]): Map<string, string> {
  const map = new Map<string, string>();
  for (const o of options) if (o.fullName) map.set(o.patientSystemId, o.fullName);
  return map;
}

/** What to call a patient whose name is not in the doctor's patient list: the id, which the doctor can search for. */
export function patientNameFallback(patientSystemId: string | null | undefined): string {
  return patientSystemId ? `Patient ${patientSystemId}` : 'Unknown patient';
}

export interface DoctorPatientOptionsState {
  options: DoctorPatientOption[];
  /** The patient's full name, or null when the patient is not in the list (or the list is not loaded). */
  nameOf:  (patientSystemId: string | null | undefined) => string | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
}

/** The signed-in doctor's patients (id and name) for filters and name lookups. */
export function useDoctorPatientOptions(): DoctorPatientOptionsState {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const owner = user?.system_id ?? null;
  const resource = useResource<DoctorPatientOption[]>(
    () => fetchPatientOptions(owner ?? ''),
    [owner],
    { enabled: !!owner, errorMessage: 'Failed to load your patients.' },
  );
  const options = useMemo(() => resource.data ?? [], [resource.data]);
  const names = useMemo(() => patientNameMap(options), [options]);
  const nameOf = useCallback(
    (patientSystemId: string | null | undefined) => (patientSystemId ? names.get(patientSystemId) ?? null : null),
    [names],
  );
  const refetchOptions = resource.refetch;
  const refetch = useCallback(() => {
    if (!owner) { refetchUser(); return; }
    optionsCache = null;
    refetchOptions();
  }, [owner, refetchUser, refetchOptions]);
  return {
    options,
    nameOf,
    loading: owner ? resource.loading : userLoading,
    error:   owner ? resource.error : userError,
    refetch,
  };
}

// ─── Appointments (GET /api/doctors/schedule/window, GET /api/appointments/{id}) ──

/** "YYYY-MM-DD" moved by whole days, using only the calendar parts (no time zone involved). */
function shiftDate(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  const shifted = new Date(Date.UTC(y, m - 1, d + days));
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`;
}

function toScheduleAppointment(data: unknown): ConsultationAppointment | null {
  const r = asRaw(data);
  const appointmentId = numeric(r.appointmentId);
  const scheduledAt = text(r.scheduledAt);
  // The paged window holds APPOINTMENT rows only; anything else is skipped rather than guessed at.
  if (appointmentId === null || !scheduledAt || (r.type != null && r.type !== 'APPOINTMENT')) return null;
  return {
    appointmentId,
    patientSystemId:    text(r.patientSystemId) ?? '',
    // Schedule rows say "Unknown" when the patient's account row is missing; that is not a name.
    patientName:        text(r.patientName) === 'Unknown' ? null : text(r.patientName),
    doctorSystemId:     text(r.doctorSystemId),
    scheduledAt,
    durationMinutes:    numeric(r.durationMinutes),
    appointmentType:    oneOf(APPOINTMENT_TYPES, r.appointmentType),
    appointmentStatus:  oneOf(APPOINTMENT_STATUSES, r.appointmentStatus),
    reasonForVisit:     text(r.reasonForVisit),
    consultationId:     numeric(r.consultationId),
    consultationStatus: oneOf(STATUSES, r.consultationStatus),
  };
}

export interface ScheduleWindowResult {
  appointments: ConsultationAppointment[];
  /** True when the window holds more appointments than were loaded. */
  truncated:    boolean;
}

/**
 * The doctor's appointments that start inside `[from, to)` (clinic wall-clock, "YYYY-MM-DDTHH:mm:ss"), loaded
 * page by page from GET /api/doctors/schedule/window up to `maxPages` pages of 100.
 */
export async function fetchScheduleWindow(
  from: string,
  to: string,
  options: { order?: 'asc' | 'desc'; maxPages?: number } = {},
): Promise<ScheduleWindowResult> {
  const maxPages = Math.max(1, options.maxPages ?? 3);
  const appointments: ConsultationAppointment[] = [];
  let truncated = false;
  for (let page = 0; page < maxPages; page++) {
    const result = await fetchPage<unknown>('/api/doctors/schedule/window', page, MAX_PAGE_SIZE, {
      from, to, order: options.order ?? 'asc',
    });
    for (const row of result.content) {
      const appointment = toScheduleAppointment(row);
      if (appointment) appointments.push(appointment);
    }
    const more = result.page + 1 < result.totalPages;
    if (!more) break;
    if (page === maxPages - 1) truncated = true;
  }
  return { appointments, truncated };
}

/** How far back the "ready to document" list and the appointment fallback look. */
export const RECENT_APPOINTMENT_DAYS = 7;

/** The doctor's appointments of the last `days` clinic days up to the end of today, newest first. */
export function fetchRecentAppointments(days: number = RECENT_APPOINTMENT_DAYS): Promise<ScheduleWindowResult> {
  const today = clinicToday();
  return fetchScheduleWindow(`${shiftDate(today, -days)}T00:00:00`, `${shiftDate(today, 1)}T00:00:00`, { order: 'desc' });
}

/**
 * True when a consultation can be opened for the appointment: it has started (clinic time), it was not
 * cancelled or missed, and it has no consultation yet (a cancelled consultation does not count).
 */
export function canOpenConsultation(appointment: ConsultationAppointment): boolean {
  const status = appointment.appointmentStatus;
  return (status === 'SCHEDULED' || status === 'RESCHEDULED' || status === 'COMPLETED')
    && appointment.consultationId === null
    && !isAfterClinicNow(appointment.scheduledAt);
}

/** GET /api/appointments/{id}: one appointment by id (the schedule window needs a date range). */
export async function getConsultationAppointment(appointmentId: number): Promise<ConsultationAppointment> {
  const { data } = await api.get<unknown>(`/api/appointments/${appointmentId}`);
  const r = asRaw(data);
  return {
    appointmentId:      numeric(r.appointmentId) ?? appointmentId,
    patientSystemId:    text(r.patientSystemId) ?? '',
    patientName:        text(r.patientName),
    doctorSystemId:     text(r.doctorSystemId),
    scheduledAt:        text(r.scheduledAt) ?? '',
    durationMinutes:    numeric(r.durationMinutes),
    appointmentType:    oneOf(APPOINTMENT_TYPES, r.type),
    appointmentStatus:  oneOf(APPOINTMENT_STATUSES, r.status),
    reasonForVisit:     text(r.reasonForVisit),
    consultationId:     null,
    consultationStatus: null,
  };
}

export interface AwaitingConsultationState {
  /** Started appointments of the last week without a consultation, newest first. */
  appointments: ConsultationAppointment[];
  truncated:    boolean;
  loading:      boolean;
  error:        string | null;
  errorStatus:  number | null;
  refetch:      () => void;
}

/**
 * The doctor's recent appointments that are ready to be documented: started within the last
 * `RECENT_APPOINTMENT_DAYS` clinic days, not cancelled or missed, and without a consultation.
 */
export function useAppointmentsAwaitingConsultation(
  options: { enabled?: boolean; patientSystemId?: string | null } = {},
): AwaitingConsultationState {
  const enabled = options.enabled ?? true;
  const patient = options.patientSystemId ?? null;
  const resource = useResource<ScheduleWindowResult>(
    () => fetchRecentAppointments(),
    [],
    { enabled, errorMessage: 'Failed to load your recent appointments.' },
  );
  const appointments = useMemo(
    () => (resource.data?.appointments ?? [])
      .filter(a => canOpenConsultation(a) && (!patient || a.patientSystemId === patient)),
    [resource.data, patient],
  );
  return {
    appointments,
    truncated:   resource.data?.truncated ?? false,
    loading:     resource.loading,
    error:       resource.error,
    errorStatus: resource.errorStatus,
    refetch:     resource.refetch,
  };
}

// ─── Editor context ───────────────────────────────────────────────────────────

/** What the editor is opened on: a consultation id, or an appointment (which may not have a consultation yet). */
export type ConsultationTarget =
  | { consultationId: number; appointmentId?: undefined }
  | { appointmentId: number; consultationId?: undefined };

export interface ConsultationContext {
  /** The consultation to show; null when the appointment has none that is not cancelled. */
  consultation: ConsultationDto | null;
  /** The appointment it documents; null only when the appointment could not be loaded for an existing consultation. */
  appointment:  ConsultationAppointment | null;
  /** Earlier, cancelled consultations of the appointment (newest first); filled when opened by appointment. */
  cancelled:    ConsultationDto[];
}

/**
 * Loads what the editor needs.
 * - By consultation id: the consultation, then its appointment (optional: the editor still works without it).
 * - By appointment id: the appointment and its consultations; the one that is not cancelled is opened.
 * Rejects with the request error (403 no access, 404 not found).
 */
export async function loadConsultationContext(target: ConsultationTarget): Promise<ConsultationContext> {
  if (target.consultationId !== undefined) {
    const consultation = await getConsultation(target.consultationId);
    const appointment = await getConsultationAppointment(consultation.appointmentId).catch(() => null);
    return { consultation, appointment, cancelled: [] };
  }
  const [appointment, consultations] = await Promise.all([
    getConsultationAppointment(target.appointmentId),
    getConsultationsForAppointment(target.appointmentId),
  ]);
  return {
    consultation: consultations.find(c => c.status !== 'CANCELLED') ?? null,
    appointment,
    cancelled:    consultations.filter(c => c.status === 'CANCELLED'),
  };
}

// ─── List view model and hook ─────────────────────────────────────────────────

export interface DoctorConsultation {
  consultation_id:            number;
  appointment_id:             number;
  patient_id:                 number;
  doctor_id:                  number;
  chief_complaint:            string | null;
  history_of_present_illness: string | null;
  physical_examination:       string | null;
  clinical_notes:             string | null;
  diagnosis:                  string | null;
  treatment_plan:             string | null;
  follow_up_instructions:     string | null;
  follow_up_required:         number;
  follow_up_date:             string | null;
  duration_minutes:           number | null;
  status:                     'DRAFT' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';
  started_at:                 string | null;
  completed_at:               string | null;
  created_at:                 string;
  doctor_name:                string;
  doctor_specialization:      string | null;
  doctor_avatar_url:          string | null;
  patient_name:               string;
  appointment_type:           'IN_PERSON' | 'VIDEO' | 'PHONE' | null;
  appointment_status:         string | null;
  patient_system_id:          string;
  doctor_system_id:           string;
  /** The CONSULT medical record linked to the consultation, when there is one. */
  medical_record_id:          number | null;
}

function toDoctorConsultation(
  r: ConsultationDto,
  names: Map<string, string>,
  appointments: Map<number, ConsultationAppointment>,
  doctorName: string,
): DoctorConsultation {
  const appointment = appointments.get(r.appointmentId);
  return {
    consultation_id:            r.consultationId,
    appointment_id:             r.appointmentId,
    patient_id:                 0,
    doctor_id:                  0,
    chief_complaint:            r.chiefComplaint,
    history_of_present_illness: r.historyOfPresentIllness,
    physical_examination:       r.physicalExamination,
    clinical_notes:             r.clinicalNotes,
    diagnosis:                  r.diagnosis,
    treatment_plan:             r.treatmentPlan,
    follow_up_instructions:     r.followUpInstructions,
    follow_up_required:         r.followUpRequired ? 1 : 0,
    follow_up_date:             r.followUpDate,
    duration_minutes:           r.durationMinutes,
    status:                     r.status,
    started_at:                 r.startedAt,
    completed_at:               r.completedAt,
    created_at:                 r.createdAt,
    doctor_name:                doctorName,
    doctor_specialization:      null,
    doctor_avatar_url:          null,
    patient_name:               names.get(r.patientSystemId) ?? appointment?.patientName ?? patientNameFallback(r.patientSystemId),
    appointment_type:           r.appointmentType ?? appointment?.appointmentType ?? null,
    appointment_status:         r.appointmentStatus ?? appointment?.appointmentStatus ?? null,
    patient_system_id:          r.patientSystemId,
    doctor_system_id:           r.doctorSystemId,
    medical_record_id:          r.medicalRecordId,
  };
}

export interface DoctorConsultationsOptions {
  /** Only consultations with this status (filtered by the server). */
  status?:          ConsultationStatus | null;
  /** Only this patient's consultations with the signed-in doctor (filtered by the server). */
  patientSystemId?: string | null;
  /** Rows per request, 1 to 100. Default 100, so callers that do not page see the newest hundred. */
  pageSize?:        number;
}

interface State {
  consultations: DoctorConsultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
  /** True when the server has more consultations than are loaded. */
  hasMore:       boolean;
  loadMore:      () => void;
  loadingMore:   boolean;
  /** Consultations on the server for the current filters. */
  total:         number;
  /** The same list as a `PagedState`, for `PagedList`. */
  paged:         PagedState<DoctorConsultation>;
}

/**
 * The signed-in doctor's consultations, newest first, from GET /api/consultations with `doctorSystemId` = the
 * doctor (so other doctors' notes on a shared patient are never listed). Patient names come from
 * GET /api/doctors/patients/options; an appointment type or status missing on a row is filled from
 * GET /api/doctors/schedule/window.
 */
export function useDoctorConsultations(options: DoctorConsultationsOptions = {}): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  const status  = options.status ?? null;
  const patient = options.patientSystemId ?? null;
  const doctorName = user?.first_name ? doctorDisplayName(user.first_name, user.last_name) : 'Unknown Doctor';

  const list = usePaged<DoctorConsultation>(
    async (page, size) => {
      const owner = doctorSystemId ?? '';
      const [result, names] = await Promise.all([
        listConsultations({ doctorSystemId: owner, status, patientSystemId: patient }, page, size),
        // Names are a convenience: without them a row shows the patient id instead of failing the list.
        fetchPatientOptions(owner).then(patientNameMap).catch(() => new Map<string, string>()),
      ]);
      const appointments = new Map<number, ConsultationAppointment>();
      if (result.content.some(r => r.appointmentType === null || r.appointmentStatus === null)) {
        const recent = await fetchRecentAppointments().catch(() => null);
        for (const a of recent?.appointments ?? []) appointments.set(a.appointmentId, a);
      }
      return { ...result, content: result.content.map(r => toDoctorConsultation(r, names, appointments, doctorName)) };
    },
    [doctorSystemId, status, patient],
    {
      enabled:      !!doctorSystemId,
      pageSize:     options.pageSize ?? MAX_PAGE_SIZE,
      keyOf:        c => c.consultation_id,
      errorMessage: 'Failed to load consultations.',
    },
  );

  // Until the signed-in doctor is known nothing can be requested: report the user's loading or failure, and
  // let Retry load the user again.
  const paged: PagedState<DoctorConsultation> = doctorSystemId
    ? list
    : { ...list, loading: userLoading, error: userError, errorStatus: null, refetch: refetchUser };

  return {
    consultations: paged.items,
    loading:       paged.loading,
    error:         paged.error,
    refetch:       paged.refetch,
    hasMore:       paged.hasMore,
    loadMore:      paged.loadMore,
    loadingMore:   paged.loadingMore,
    total:         paged.total,
    paged,
  };
}

/** Counts per status of the signed-in doctor's consultations (GET /api/doctors/consultations/stats). */
export function useConsultationStats(): ResourceState<ConsultationStatsDto> {
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? null;
  return useResource<ConsultationStatsDto>(
    () => getConsultationStats(),
    [doctorSystemId],
    { enabled: !!doctorSystemId, errorMessage: 'Failed to load consultation counts.' },
  );
}
