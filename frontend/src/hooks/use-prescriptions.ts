import { useCallback } from 'react';
import { fetchPage, mapPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import { cachedDoctorName, DOCTOR_NAME_FALLBACK, primeDoctorNames, resolveDoctorNames } from '@/lib/doctors/names';
import { clinicToday } from '@/lib/time/clinic';
import { useCurrentUser } from './use-current-user';
import { loadActiveDoctors, type Doctor } from './use-doctors';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

export type PrescriptionStatus = 'ACTIVE' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED' | 'ON_HOLD';
export type PrescriptionRoute  = 'ORAL' | 'TOPICAL' | 'INJECTION' | 'INHALED' | 'SUBLINGUAL' | 'RECTAL' | 'IV';

export const PRESCRIPTION_STATUSES: readonly PrescriptionStatus[] = ['ACTIVE', 'ON_HOLD', 'COMPLETED', 'EXPIRED', 'CANCELLED'];

export interface Prescription {
  prescription_id:      number;
  patient_id:           number;
  doctor_id:            number;
  consultation_id:      number | null;
  medication_name:      string;
  generic_name:         string | null;
  dosage:               string;
  frequency:            string;
  route:                PrescriptionRoute;
  quantity:             string | null;
  refills_allowed:      number;
  special_instructions: string | null;
  /** The stored status. Badges and filters use `effective_status`. */
  status:               PrescriptionStatus;
  /**
   * What the patient should read as the status: EXPIRED for a stored ACTIVE or ON_HOLD prescription whose expiry
   * date has passed (the server stores that at its next hourly sweep), otherwise the stored status.
   */
  effective_status:     PrescriptionStatus;
  prescribed_date:      string;
  expiry_date:          string | null;
  created_at:           string;
  patient_system_id:    string;
  doctor_system_id:     string;
  doctor_name:          string;
  doctor_specialization: string | null;
  doctor_avatar_url:    string | null;
  patient_name:         string;
}

const STATUS_SET = new Set<string>(PRESCRIPTION_STATUSES);

function asStatus(value: unknown): PrescriptionStatus | null {
  const s = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return STATUS_SET.has(s) ? (s as PrescriptionStatus) : null;
}

/**
 * The status to show for a prescription. The server sends `effectiveStatus`; when it is missing the same rule is
 * applied here: ACTIVE or ON_HOLD with an expiry date before the clinic's today reads as EXPIRED (a prescription
 * is valid through its expiry date).
 */
export function effectivePrescriptionStatus(
  status: PrescriptionStatus,
  expiryDate: string | null | undefined,
  fromServer?: unknown,
  today: string = clinicToday(),
): PrescriptionStatus {
  const given = asStatus(fromServer);
  if (given) return given;
  const expiry = (expiryDate ?? '').slice(0, 10);
  if ((status === 'ACTIVE' || status === 'ON_HOLD') && expiry && expiry < today) return 'EXPIRED';
  return status;
}

interface MapContext {
  patientName: string;
  doctors:     Map<string, Doctor>;
  /** Names looked up by id for doctors the row and the directory do not name. */
  resolved:    Map<string, string>;
}

function toPrescription(r: any, ctx: MapContext): Prescription {
  const doctorSystemId = String(r.doctorSystemId ?? '');
  const doctor = ctx.doctors.get(doctorSystemId);
  const status = asStatus(r.status) ?? 'ACTIVE';
  const expiry = r.expiryDate ? String(r.expiryDate) : null;
  const given  = typeof r.doctorName === 'string' ? r.doctorName.trim() : '';
  return {
    prescription_id:      r.prescriptionId      ?? 0,
    patient_id:           0,
    doctor_id:            0,
    consultation_id:      r.consultationId      ?? null,
    medication_name:      r.medicationName      ?? '',
    generic_name:         r.genericName         ?? null,
    dosage:               r.dosage              ?? '',
    frequency:            r.frequency           ?? '',
    route:                (r.route              ?? 'ORAL') as PrescriptionRoute,
    quantity:             r.quantity            ?? null,
    refills_allowed:      r.refillsAllowed      ?? 0,
    special_instructions: r.specialInstructions ?? null,
    status,
    effective_status:     effectivePrescriptionStatus(status, expiry, r.effectiveStatus),
    prescribed_date:      r.prescribedDate      ? String(r.prescribedDate) : '',
    expiry_date:          expiry,
    created_at:           r.createdAt           ? String(r.createdAt) : '',
    patient_system_id:    String(r.patientSystemId ?? ''),
    doctor_system_id:     doctorSystemId,
    // The name the server sends, then the directory, then a lookup by id; never the raw DOC-... id.
    doctor_name:          given || doctor?.full_name || ctx.resolved.get(doctorSystemId) || DOCTOR_NAME_FALLBACK,
    doctor_specialization: doctor?.specialization ?? null,
    doctor_avatar_url:    null,
    patient_name:         ctx.patientName,
  };
}

/** Builds the mapping context for some rows: the doctor directory (a nicety) and names for unlisted doctors. */
async function mapContext(rows: any[], patientName: string): Promise<MapContext> {
  // Doctor names and specialities are a nicety: a failed directory must not hide the prescriptions themselves.
  const directory = await loadActiveDoctors().catch(() => [] as Doctor[]);
  primeDoctorNames(directory);
  const doctors = new Map<string, Doctor>();
  directory.forEach(d => { if (d.doctor_system_id) doctors.set(d.doctor_system_id, d); });

  const resolved = new Map<string, string>();
  const missing: string[] = [];
  for (const r of rows) {
    const id = String(r?.doctorSystemId ?? '');
    const named = typeof r?.doctorName === 'string' && r.doctorName.trim() !== '';
    if (!id || named || doctors.has(id) || resolved.has(id) || missing.includes(id)) continue;
    const cached = cachedDoctorName(id);
    if (cached) resolved.set(id, cached);
    else missing.push(id);
  }
  if (missing.length > 0) {
    // Never rejects; a failed lookup keeps the neutral label.
    const found = await resolveDoctorNames(missing);
    found.forEach((name, id) => resolved.set(id, name));
  }
  return { patientName, doctors, resolved };
}

export interface PrescriptionQuery {
  /** Whose prescriptions. A patient may pass only their own id; a caregiver must pass a loved one's id. */
  patientSystemId: string;
  /** Matches the effective status (an ACTIVE prescription past its expiry is found under EXPIRED). */
  status?:         PrescriptionStatus | null;
}

/**
 * One page of the canonical list `GET /api/prescriptions` (newest first), mapped to the view model.
 * Rejects with the axios error.
 */
export async function fetchPrescriptionsPage(
  query: PrescriptionQuery,
  page: number,
  size: number,
  patientName = '',
): Promise<PageResponse<Prescription>> {
  const raw = await fetchPage<any>('/api/prescriptions', page, size, {
    patientSystemId: query.patientSystemId,
    status:          query.status ?? undefined,
  });
  const ctx = await mapContext(raw.content, patientName);
  return mapPage(raw, r => toPrescription(r, ctx));
}

/** One prescription by id (`GET /api/prescriptions/{id}`), for links that name a prescription. */
export async function fetchPrescription(prescriptionId: number, patientName = ''): Promise<Prescription> {
  const { data } = await api.get<any>(`/api/prescriptions/${prescriptionId}`);
  const ctx = await mapContext([data], patientName);
  return toPrescription(data ?? {}, ctx);
}

export interface PatientPrescriptionsOptions {
  status?:      PrescriptionStatus | null;
  /** Rows per request (1 to 100). Default 20. */
  pageSize?:    number;
  /** Shown as `patient_name` on every row. */
  patientName?: string;
  enabled?:     boolean;
}

/**
 * The prescriptions of one patient, page by page: the signed-in patient's own, or a loved one's for a caregiver
 * with the medications permission (a 403 is reported in `errorStatus`).
 */
export function usePatientPrescriptions(
  patientSystemId: string | null | undefined,
  options: PatientPrescriptionsOptions = {},
): PagedState<Prescription> {
  const id = (patientSystemId ?? '').trim();
  const status = options.status ?? null;
  const patientName = options.patientName ?? '';
  return usePaged<Prescription>(
    (page, size) => fetchPrescriptionsPage({ patientSystemId: id, status }, page, size, patientName),
    [id, status, patientName],
    {
      pageSize:     options.pageSize,
      enabled:      (options.enabled ?? true) && id !== '',
      errorMessage: 'Failed to load prescriptions.',
      keyOf:        p => p.prescription_id,
    },
  );
}

export type PrescriptionCounts = Record<PrescriptionStatus, number>;

/**
 * How many prescriptions a patient has per effective status. There is no statistics endpoint: each number is the
 * `totalElements` of a one-row page of the list filtered by that status.
 */
export async function fetchPrescriptionCounts(patientSystemId: string): Promise<PrescriptionCounts> {
  const totals = await Promise.all(
    PRESCRIPTION_STATUSES.map(status =>
      fetchPage<unknown>('/api/prescriptions', 0, 1, { patientSystemId, status }).then(p => p.totalElements)),
  );
  const counts = {} as PrescriptionCounts;
  PRESCRIPTION_STATUSES.forEach((status, i) => { counts[status] = totals[i]; });
  return counts;
}

export interface PrescriptionCountsState {
  /** Null until loaded, and when the numbers could not be loaded (they are a nicety: show the filters without them). */
  counts:  PrescriptionCounts | null;
  total:   number | null;
  refetch: () => void;
}

export function usePrescriptionCounts(
  patientSystemId: string | null | undefined,
  options: { enabled?: boolean } = {},
): PrescriptionCountsState {
  const id = (patientSystemId ?? '').trim();
  const { data, refetch } = useResource<PrescriptionCounts>(
    () => fetchPrescriptionCounts(id),
    [id],
    { enabled: (options.enabled ?? true) && id !== '' },
  );
  const total = data ? PRESCRIPTION_STATUSES.reduce((sum, s) => sum + data[s], 0) : null;
  return { counts: data, total, refetch };
}

export interface UsePrescriptionsOptions {
  /** Only prescriptions with this effective status. Default: all. */
  status?:   PrescriptionStatus | null;
  /**
   * Rows per request. Default 100 (the most the server returns at once), so screens without a "Load more"
   * control keep showing the whole history of a typical patient.
   */
  pageSize?: number;
}

interface State {
  prescriptions: Prescription[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
  /** True when the server has more prescriptions than are loaded. */
  hasMore:       boolean;
  /** Loads the next page and appends it. */
  loadMore:      () => void;
  /** True while `loadMore` is fetching. */
  loadingMore:   boolean;
  /** Number of prescriptions on the server for this filter. */
  total:         number;
  /** The same list as a `PagedState`, for `PagedList`. */
  paged:         PagedState<Prescription>;
}

const noop = () => {};

/** The signed-in patient's prescriptions, newest first, from the paged list `GET /api/prescriptions`. */
export function usePrescriptions(options: UsePrescriptionsOptions = {}): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const patientName = user ? (`${user.first_name} ${user.last_name}`.trim() || 'Unknown Patient') : '';

  const list = usePatientPrescriptions(user?.system_id ?? null, {
    status:      options.status ?? null,
    pageSize:    options.pageSize ?? 100,
    patientName,
    enabled:     !!user,
  });

  // Without a signed-in user there is no list to reload: Retry loads the account again.
  const refetchAccount = useCallback(() => refetchUser(), [refetchUser]);

  if (!user) {
    const accountError = userLoading ? null : (userError ?? 'Could not load your account.');
    const paged: PagedState<Prescription> = {
      items: [], loading: !accountError, loadingMore: false, refreshing: false,
      error: accountError, errorStatus: accountError ? 0 : null,
      hasMore: false, total: 0, loadMore: noop, refetch: refetchAccount, setItems: noop,
    };
    return {
      prescriptions: [], loading: paged.loading, error: accountError, refetch: refetchAccount,
      hasMore: false, loadMore: noop, loadingMore: false, total: 0, paged,
    };
  }

  return {
    prescriptions: list.items,
    loading:       list.loading,
    error:         list.error,
    refetch:       list.refetch,
    hasMore:       list.hasMore,
    loadMore:      list.loadMore,
    loadingMore:   list.loadingMore,
    total:         list.total,
    paged:         list,
  };
}
