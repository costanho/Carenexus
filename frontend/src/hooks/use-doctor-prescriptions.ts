import { useCallback, useMemo } from 'react';
import { api } from '@/lib/auth/auth.interceptor';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage, MAX_PAGE_SIZE } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import {
  type CreatePrescriptionBody, normaliseScheduleTimes, PRESCRIPTION_LIMITS, PRESCRIPTION_ROUTES, PRESCRIPTION_STATUSES,
  type PrescriptionRoute, type PrescriptionStatus, type UpdatePrescriptionBody,
} from '@/lib/validation/prescription';
import { useCurrentUser } from './use-current-user';
import { doctorDisplayName } from './use-doctors';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

// The doctor's prescriptions: the canonical paged list (GET /api/prescriptions with doctorSystemId = caller) and
// the write calls of the prescribing doctor. Shapes follow the be-prescriptions and be-medication API contracts.

export { PRESCRIPTION_ROUTES, PRESCRIPTION_STATUSES };
export type { CreatePrescriptionBody, PrescriptionRoute, PrescriptionStatus, UpdatePrescriptionBody };

/** `PrescriptionResponse` as the API sends it. */
export interface PrescriptionDto {
  prescriptionId:      number;
  patientSystemId:     string;
  doctorSystemId:      string;
  doctorName:          string | null;
  consultationId:      number | null;
  medicationName:      string;
  genericName:         string | null;
  dosage:              string;
  frequency:           string;
  route:               PrescriptionRoute;
  quantity:            string | null;
  refillsAllowed:      number;
  specialInstructions: string | null;
  /** Stored status. */
  status:              PrescriptionStatus;
  /** EXPIRED once an ACTIVE or ON_HOLD prescription is past its expiry date; otherwise equal to `status`. */
  effectiveStatus:     PrescriptionStatus;
  prescribedDate:      string;
  expiryDate:          string | null;
  createdAt:           string;
}

export interface DoctorPrescription {
  prescription_id:      number;
  patient_system_id:    string;
  /** @deprecated not exposed by the API; always 0. Use patient_system_id. */
  patient_id:           number;
  /** @deprecated not exposed by the API; always 0. */
  doctor_id:            number;
  doctor_system_id:     string;
  consultation_id:      number | null;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string;
  frequency:            string;
  route:                PrescriptionRoute;
  quantity:             string | null;
  refills_allowed:      number;
  special_instructions: string | null;
  /** Stored status; writes are governed by it. */
  status:               PrescriptionStatus;
  /** What badges, filters and the "can act" decision use: EXPIRED once the expiry date has passed. */
  effective_status:     PrescriptionStatus;
  prescribed_date:      string;
  expiry_date:          string | null;
  created_at:           string;
  // enriched client-side
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  /** From GET /api/doctors/patients/options; "Patient PAT-…" when the name is not known. */
  patient_name:          string;
  patient_avatar_url:    string | null;
}

/** Statuses a doctor can set from the web (EXPIRED is set by the server). */
export type SettablePrescriptionStatus = Exclude<PrescriptionStatus, 'EXPIRED'>;

/** True while the prescribing doctor can still edit or change the status (the server may answer 409 if this is stale). */
export function isPrescriptionOpen(effectiveStatus: PrescriptionStatus): boolean {
  return effectiveStatus === 'ACTIVE' || effectiveStatus === 'ON_HOLD';
}

// The server only stores the listed statuses and routes. A value outside them is kept as sent rather than
// replaced by a listed one, so a card never shows a status or route the prescription does not have; only a
// missing value takes the server's own default.
function asStatus(value: unknown, fallback: PrescriptionStatus): PrescriptionStatus {
  const s = String(value ?? '').trim().toUpperCase();
  return s ? (s as PrescriptionStatus) : fallback;
}

function asRoute(value: unknown): PrescriptionRoute {
  const r = String(value ?? '').trim().toUpperCase();
  return r ? (r as PrescriptionRoute) : 'ORAL';
}

/** Reads a response body defensively, so a missing field never breaks a card. */
export function toPrescriptionDto(r: any): PrescriptionDto {
  const status = asStatus(r?.status, 'ACTIVE');
  return {
    prescriptionId:      Number(r?.prescriptionId ?? 0),
    patientSystemId:     r?.patientSystemId ?? '',
    doctorSystemId:      r?.doctorSystemId ?? '',
    doctorName:          r?.doctorName ?? null,
    consultationId:      r?.consultationId ?? null,
    medicationName:      r?.medicationName ?? '',
    genericName:         r?.genericName ?? null,
    dosage:              r?.dosage ?? '',
    frequency:           r?.frequency ?? '',
    route:               asRoute(r?.route),
    quantity:            r?.quantity ?? null,
    refillsAllowed:      Number(r?.refillsAllowed ?? 0),
    specialInstructions: r?.specialInstructions ?? null,
    status,
    effectiveStatus:     asStatus(r?.effectiveStatus, status),
    prescribedDate:      r?.prescribedDate ? String(r.prescribedDate) : '',
    expiryDate:          r?.expiryDate ? String(r.expiryDate) : null,
    createdAt:           r?.createdAt ? String(r.createdAt) : '',
  };
}

export function patientNameFallback(patientSystemId: string): string {
  return patientSystemId ? `Patient ${patientSystemId}` : 'Patient';
}

/** The view model of a row. Names are filled in by the hook once they are known. */
export function toDoctorPrescription(dto: PrescriptionDto): DoctorPrescription {
  return {
    prescription_id:      dto.prescriptionId,
    patient_system_id:    dto.patientSystemId,
    patient_id:           0,
    doctor_id:            0,
    doctor_system_id:     dto.doctorSystemId,
    consultation_id:      dto.consultationId,
    medication_name:      dto.medicationName,
    generic_name:         dto.genericName,
    dosage:               dto.dosage,
    frequency:            dto.frequency,
    route:                dto.route,
    quantity:             dto.quantity,
    refills_allowed:      dto.refillsAllowed,
    special_instructions: dto.specialInstructions,
    status:               dto.status,
    effective_status:     dto.effectiveStatus,
    prescribed_date:      dto.prescribedDate,
    expiry_date:          dto.expiryDate,
    created_at:           dto.createdAt,
    doctor_name:           dto.doctorName ?? '',
    doctor_specialization: null,
    doctor_avatar_url:     null,
    patient_name:          patientNameFallback(dto.patientSystemId),
    patient_avatar_url:    null,
  };
}

// ─── API calls ────────────────────────────────────────────────────────────────

const PRESCRIPTIONS = '/api/prescriptions';

export interface DoctorPrescriptionFilters {
  /** Matches the effective status. Null or undefined lists every status. */
  status?:          PrescriptionStatus | null;
  /** Only prescriptions written for this patient (PAT id). */
  patientSystemId?: string | null;
  /** Case-insensitive text found in the medication or generic name (at most 100 characters). */
  search?:          string | null;
  /** Only prescriptions issued from this consultation. */
  consultationId?:  number | null;
}

function searchParam(search: string | null | undefined): string | undefined {
  const q = (search ?? '').trim().slice(0, PRESCRIPTION_LIMITS.search);
  return q || undefined;
}

/**
 * One page of the prescriptions the doctor wrote, newest first. `doctorSystemId` must be the caller: without it
 * a patient filter would return that patient's prescriptions from every prescriber.
 */
export function listDoctorPrescriptions(
  doctorSystemId: string,
  filters: DoctorPrescriptionFilters,
  page: number,
  size?: number,
): Promise<PageResponse<PrescriptionDto>> {
  return fetchPage<unknown>(PRESCRIPTIONS, page, size, {
    doctorSystemId,
    status:          filters.status ?? undefined,
    patientSystemId: filters.patientSystemId?.trim() || undefined,
    q:               searchParam(filters.search),
    consultationId:  filters.consultationId ?? undefined,
  }).then(p => ({ ...p, content: p.content.map(toPrescriptionDto) }));
}

export interface CreatedPrescription {
  prescription: PrescriptionDto;
  /**
   * True when the server answered with the result of an earlier request that carried the same Idempotency-Key
   * (HTTP 200 instead of 201): nothing new was stored, and the stored prescription is the one of that first request.
   */
  replayed:     boolean;
}

/** POST /api/prescriptions. Send the same `idempotencyKey` when retrying the same submit. */
export async function createPrescription(body: CreatePrescriptionBody, idempotencyKey: string): Promise<CreatedPrescription> {
  const response = await api.post<unknown>(PRESCRIPTIONS, body, withIdempotencyKey(idempotencyKey));
  return { prescription: toPrescriptionDto(response.data), replayed: response.status === 200 };
}

/** PUT /api/prescriptions/{id}: replaces refills, expiry date and instructions (all three are applied). */
export async function updatePrescription(prescriptionId: number, body: UpdatePrescriptionBody): Promise<PrescriptionDto> {
  const { data } = await api.put<unknown>(`${PRESCRIPTIONS}/${prescriptionId}`, body);
  return toPrescriptionDto(data);
}

const MARK_ROUTE: Record<SettablePrescriptionStatus, string> = {
  ACTIVE:    'mark-active',
  ON_HOLD:   'mark-on-hold',
  COMPLETED: 'mark-completed',
  CANCELLED: 'mark-cancelled',
};

/** PATCH /api/doctors/prescriptions/{id}/mark-*: hold, resume, complete or cancel. Returns the updated row. */
export async function setPrescriptionStatus(prescriptionId: number, status: SettablePrescriptionStatus): Promise<PrescriptionDto> {
  const { data } = await api.patch<unknown>(`/api/doctors/prescriptions/${prescriptionId}/${MARK_ROUTE[status]}`);
  return toPrescriptionDto(data);
}

/** The daily dose times of a prescription (`MedicationSchedulePlanResponse` of be-medication). */
export interface PrescriptionSchedulePlan {
  prescriptionId:    number;
  patientSystemId:   string;
  /** Clinic wall-clock times "HH:mm", distinct and ascending. Empty when no doses are scheduled. */
  times:             string[];
  updatedBySystemId: string | null;
  updatedAt:         string | null;
}

function toSchedulePlan(data: any, prescriptionId: number): PrescriptionSchedulePlan {
  return {
    prescriptionId:    Number(data?.prescriptionId ?? prescriptionId),
    patientSystemId:   data?.patientSystemId ?? '',
    times:             normaliseScheduleTimes(Array.isArray(data?.times) ? data.times.map((t: unknown) => String(t).slice(0, 5)) : []),
    updatedBySystemId: data?.updatedBySystemId ?? null,
    updatedAt:         data?.updatedAt ? String(data.updatedAt) : null,
  };
}

/** GET /api/prescriptions/{id}/schedule. A prescription without a plan answers with an empty list, never 404. */
export async function getPrescriptionSchedule(prescriptionId: number): Promise<PrescriptionSchedulePlan> {
  const { data } = await api.get<unknown>(`${PRESCRIPTIONS}/${prescriptionId}/schedule`);
  return toSchedulePlan(data, prescriptionId);
}

/** PUT /api/prescriptions/{id}/schedule: replaces the daily times (0 to 8). Today's remaining and future doses follow. */
export async function updatePrescriptionSchedule(prescriptionId: number, times: readonly string[]): Promise<PrescriptionSchedulePlan> {
  const { data } = await api.put<unknown>(`${PRESCRIPTIONS}/${prescriptionId}/schedule`, { times: normaliseScheduleTimes(times) });
  return toSchedulePlan(data, prescriptionId);
}

/** One entry of GET /api/doctors/patients/options. */
export interface DoctorPatientOption {
  patientSystemId: string;
  firstName:       string;
  lastName:        string;
  fullName:        string;
}

/** GET /api/doctors/patients/options: id and name of the doctor's patients, ordered by name (at most 500). */
export async function listDoctorPatientOptions(): Promise<DoctorPatientOption[]> {
  const { data } = await api.get<unknown>('/api/doctors/patients/options');
  if (!Array.isArray(data)) return [];
  return data
    .filter((p: any) => typeof p?.patientSystemId === 'string' && p.patientSystemId)
    .map((p: any) => {
      const firstName = p.firstName ?? '';
      const lastName  = p.lastName ?? '';
      const fullName  = String(p.fullName ?? '').trim() || `${firstName} ${lastName}`.trim();
      return { patientSystemId: p.patientSystemId, firstName, lastName, fullName: fullName || patientNameFallback(p.patientSystemId) };
    });
}

/** A consultation the doctor can link a new prescription to. */
export interface PrescriptionConsultationOption {
  consultationId: number;
  status:         string;
  createdAt:      string;
  chiefComplaint: string | null;
}

/**
 * The doctor's own consultations with one patient, newest first (GET /api/consultations with both ids), without
 * the cancelled ones. Used by the "New prescription" form to offer the consultation to link.
 */
export async function listConsultationOptions(patientSystemId: string, doctorSystemId: string): Promise<PrescriptionConsultationOption[]> {
  const page = await fetchPage<any>('/api/consultations', 0, 50, { patientSystemId, doctorSystemId });
  return page.content
    .filter(c => Number.isInteger(c?.consultationId) && String(c?.status ?? '').toUpperCase() !== 'CANCELLED')
    .map(c => ({
      consultationId: c.consultationId as number,
      status:         String(c.status ?? ''),
      createdAt:      c.createdAt ? String(c.createdAt) : '',
      chiefComplaint: typeof c.chiefComplaint === 'string' && c.chiefComplaint.trim() ? c.chiefComplaint.trim() : null,
    }));
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

export interface DoctorPatientNames {
  /** The doctor's patients, ordered by name. Empty until loaded. */
  options: DoctorPatientOption[];
  /** The patient's full name, or "Patient PAT-…" when it is not known (still loading, failed, or not listed). */
  nameOf:  (patientSystemId: string | null | undefined) => string;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
}

/** Patient names for the doctor's lists and filters, from GET /api/doctors/patients/options. */
export function useDoctorPatientNames(enabled = true): DoctorPatientNames {
  const { data, loading, error, refetch } = useResource(listDoctorPatientOptions, [], {
    enabled, errorMessage: 'Patient names could not be loaded.',
  });
  const names = useMemo(() => new Map((data ?? []).map(o => [o.patientSystemId, o.fullName] as const)), [data]);
  const nameOf = useCallback(
    (id: string | null | undefined) => (id ? names.get(id) ?? patientNameFallback(id) : patientNameFallback('')),
    [names],
  );
  return { options: data ?? [], nameOf, loading, error, refetch };
}

export interface DoctorPrescriptionsOptions {
  /**
   * Rows per request. Default 100, the largest page: a screen that calls the hook without options (the native
   * screen) has no "Load more" control and so shows the newest 100. The web screen passes 20 and pages.
   */
  pageSize?: number;
}

interface State {
  prescriptions: DoctorPrescription[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
  /** True while "Load more" is fetching the next page. */
  loadingMore:   boolean;
  /** True while `refetch` reloads rows that are already on screen. */
  refreshing:    boolean;
  /** HTTP status of the failure (0 = no response), or null. */
  errorStatus:   number | null;
  /** True when the server has more rows for these filters. */
  hasMore:       boolean;
  /** Number of prescriptions on the server for these filters. */
  total:         number;
  loadMore:      () => void;
  /** The same list as a `PagedState`, for `PagedList`. */
  paged:         PagedState<DoctorPrescription>;
  /** Replaces a loaded row with the server's answer to a write, without another request. */
  applyUpdate:   (updated: PrescriptionDto) => void;
  /** Names of the doctor's patients (also used for the patient filter). */
  patients:      DoctorPatientNames;
}

/**
 * The prescriptions the signed-in doctor wrote, newest first, paged. Filters are applied by the server; changing
 * one starts again from the first page.
 */
export function useDoctorPrescriptions(
  filters: DoctorPrescriptionFilters = {},
  options: DoctorPrescriptionsOptions = {},
): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? '';

  const status          = filters.status ?? null;
  const patientSystemId = filters.patientSystemId?.trim() || null;
  const search          = searchParam(filters.search) ?? null;
  const consultationId  = filters.consultationId ?? null;

  const list = usePaged<DoctorPrescription>(
    (page, size) => listDoctorPrescriptions(doctorSystemId, { status, patientSystemId, search, consultationId }, page, size)
      .then(p => ({ ...p, content: p.content.map(toDoctorPrescription) })),
    [doctorSystemId, status, patientSystemId, search, consultationId],
    {
      enabled:      !!doctorSystemId,
      pageSize:     options.pageSize ?? MAX_PAGE_SIZE,
      errorMessage: 'Failed to load prescriptions.',
      keyOf:        r => r.prescription_id,
    },
  );

  const patients = useDoctorPatientNames(!!doctorSystemId);
  const ownName = user?.first_name ? doctorDisplayName(user.first_name, user.last_name) : '';

  const prescriptions = useMemo(
    () => list.items.map(r => ({
      ...r,
      patient_name: patients.nameOf(r.patient_system_id),
      doctor_name:  r.doctor_name || ownName || 'Unknown Doctor',
    })),
    [list.items, patients.nameOf, ownName],
  );

  const noUser = !userLoading && !doctorSystemId;
  // The first paint waits for the names (they arrive with the first page), so a card never flips from an id to a name.
  const loading = userLoading || (!noUser && (list.loading || patients.loading));
  const error = noUser ? userError ?? 'Failed to load prescriptions.' : list.error;

  const { refetch: refetchList, setItems } = list;
  const { error: namesError, refetch: refetchNames } = patients;
  const refetch = useCallback(() => {
    if (noUser) { refetchUser(); return; }
    refetchList();
    if (namesError) refetchNames();
  }, [noUser, refetchUser, refetchList, namesError, refetchNames]);

  const applyUpdate = useCallback((updated: PrescriptionDto) => {
    const row = toDoctorPrescription(updated);
    setItems(prev => prev.map(r => (r.prescription_id === row.prescription_id ? row : r)));
  }, [setItems]);

  const paged = useMemo<PagedState<DoctorPrescription>>(() => ({
    items:       prescriptions,
    loading,
    loadingMore: list.loadingMore,
    refreshing:  list.refreshing,
    error,
    errorStatus: noUser ? 0 : list.errorStatus,
    hasMore:     list.hasMore,
    total:       list.total,
    loadMore:    list.loadMore,
    refetch,
    setItems,
  }), [prescriptions, loading, list.loadingMore, list.refreshing, error, noUser, list.errorStatus, list.hasMore, list.total, list.loadMore, refetch, setItems]);

  return {
    prescriptions,
    loading,
    error,
    refetch,
    loadingMore: paged.loadingMore,
    refreshing:  paged.refreshing,
    errorStatus: paged.errorStatus,
    hasMore:     paged.hasMore,
    total:       paged.total,
    loadMore:    paged.loadMore,
    paged,
    applyUpdate,
    patients,
  };
}

export type PrescriptionStatusCounts = Record<PrescriptionStatus, number>;

interface CountsState {
  /** Prescriptions per effective status, or null until loaded (and when the request failed). */
  counts:  PrescriptionStatusCounts | null;
  /** Sum of the five statuses, or null with `counts`. */
  total:   number | null;
  loading: boolean;
  refetch: () => void;
}

/**
 * How many of the doctor's prescriptions are in each status for a patient and search filter. There is no
 * statistics endpoint: each count is `totalElements` of a one-row page of the canonical list.
 */
export function useDoctorPrescriptionCounts(filters: Pick<DoctorPrescriptionFilters, 'patientSystemId' | 'search'> = {}): CountsState {
  const { user } = useCurrentUser();
  const doctorSystemId = user?.system_id ?? '';
  const patientSystemId = filters.patientSystemId?.trim() || null;
  const search = searchParam(filters.search) ?? null;

  const { data, loading, refetch } = useResource<PrescriptionStatusCounts>(
    async () => {
      const totals = await Promise.all(PRESCRIPTION_STATUSES.map(status =>
        listDoctorPrescriptions(doctorSystemId, { status, patientSystemId, search }, 0, 1).then(p => p.totalElements)));
      return Object.fromEntries(PRESCRIPTION_STATUSES.map((s, i) => [s, totals[i]])) as PrescriptionStatusCounts;
    },
    [doctorSystemId, patientSystemId, search],
    { enabled: !!doctorSystemId },
  );

  const total = data ? PRESCRIPTION_STATUSES.reduce((sum, s) => sum + data[s], 0) : null;
  return { counts: data, total, loading, refetch };
}
