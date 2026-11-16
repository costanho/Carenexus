import { useCallback, useEffect, useMemo } from 'react';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage, mapPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type { LabTestType, ResultStatus, ReviewStatus, WritableResultStatus } from '@/lib/validation/results';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

// Doctor lab results: the canonical paged list (GET /api/lab-results), the review queue, and the write calls
// (record, edit, change status, review, cancel). Contract: .claude/build-plan/api/be-lab-imaging.md.

export type { LabTestType, ResultStatus, ReviewStatus, WritableResultStatus } from '@/lib/validation/results';

/** Status of a lab result. Same values as the backend: PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL, CANCELLED. */
export type LabStatus = ResultStatus;

export type KnownLabFlag = 'NORMAL' | 'LOW' | 'HIGH' | 'CRITICAL' | 'ABNORMAL';

/**
 * The flag of a sub-test exactly as stored (upper-cased), or null when the sub-test carries no flag.
 * Any upper-case text can arrive, so screens must not treat "not LOW and not HIGH" as normal: only `NORMAL`
 * is normal, null means "no flag", everything else is abnormal (see `isAbnormalLabFlag`).
 */
export type LabFlag = KnownLabFlag | (string & {}) | null;

export interface LabSubTest {
  value:          number | string;
  unit:           string;
  reference:      string;
  flag:           LabFlag;
  interpretation?: string;
}

// ─── API shapes (camelCase, straight from the API) ────────────────────────────

export interface LabResultDto {
  resultId: number; patientSystemId: string; patientName: string | null;
  doctorSystemId: string; doctorName: string | null; consultationId: number | null;
  testName: string; testType: LabTestType; resultValue: string | null; unit: string | null;
  referenceRange: string | null; resultData: string | null; status: ResultStatus; isCritical: boolean;
  testDate: string; reviewedBy: string | null; reviewedByName: string | null; reviewedAt: string | null;
  reviewNotes: string | null; documentId: number | null; fileUrl: null; createdAt: string;
}

export interface CreateLabResultBody {
  patientSystemId: string; consultationId?: number; testName: string; testType: LabTestType;
  resultValue?: string; unit?: string; referenceRange?: string;
  /** JSON text of an object: `JSON.stringify(object)` (see `buildLabResultData`). */
  resultData?: string;
  status?: WritableResultStatus; isCritical?: boolean; testDate?: string; documentId?: number;
}

/** Partial update: a field that is left out is unchanged; an optional text sent as '' is cleared. */
export type UpdateLabResultBody = Partial<Omit<CreateLabResultBody, 'patientSystemId' | 'consultationId'>>;

/** Body of the review (sign-off) call, shared by lab and imaging results. */
export interface ReviewResultBody { status: ReviewStatus; reviewNotes?: string }

/** Query of the canonical lists GET /api/lab-results and GET /api/imaging-results. */
export interface ResultListQuery {
  patientSystemId?: string; doctorSystemId?: string; status?: ResultStatus; type?: string;
  critical?: boolean; consultationId?: number; page?: number; size?: number;
}

/** One row of GET /api/doctors/patients/options: the doctor's own patients, for pickers and name lookups. */
export interface DoctorPatientOption { patientSystemId: string; firstName: string; lastName: string; fullName: string }

// ─── View model (snake_case, shared with the native doctor screen) ────────────

export interface DoctorLabResult {
  result_id:       number;
  patient_system_id: string;
  /** @deprecated not exposed by the API; always 0. Use patient_system_id. */
  patient_id:      number;
  /** @deprecated not exposed by the API; always 0. Use doctor_system_id. */
  doctor_id:       number;
  /** The doctor who recorded the result (its author). */
  doctor_system_id: string;
  consultation_id: number | null;
  test_name:       string;
  test_type:       LabTestType;
  result_value:    string | null;
  unit:            string | null;
  reference_range: string | null;
  result_data:     Record<string, LabSubTest> | null;
  /** The stored `resultData` JSON text, kept so an edit can carry over entries the form does not model. */
  result_data_raw: string | null;
  status:          LabStatus;
  is_critical:     number;
  test_date:       string;
  /** @deprecated never a numeric id; always null. Use reviewed_by_system_id and reviewer_name. */
  reviewed_by:     number | null;
  reviewed_by_system_id: string | null;
  /** "Dr. First Last" of the reviewer; null when not reviewed. */
  reviewer_name:   string | null;
  reviewed_at:     string | null;
  review_notes:    string | null;
  /** Attached document (GET /api/documents/{id}/download); null when none. */
  document_id:     number | null;
  /** @deprecated always null; attachments are referenced by document_id. */
  file_url:        string | null;
  created_at:      string;
  /**
   * Author name for the native screen: the API's doctorName, or "Unknown Doctor" when the API has none.
   * Web screens render `useDoctorName(doctor_system_id, recorded_by_name)` instead (CONTRACTS 12.4).
   */
  doctor_name:           string;
  /** The API's doctorName as sent (trimmed), or null when the API did not name the author. */
  recorded_by_name:      string | null;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  patient_name:          string;
  patient_avatar_url:    string | null;
}

interface State {
  results:  DoctorLabResult[];
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
  /** True when the server has more rows than are loaded. */
  hasMore:  boolean;
  /** Fetches the next page. */
  loadMore: () => void;
  /** Number of rows on the server for this query. */
  total:    number;
}

// ─── resultData ───────────────────────────────────────────────────────────────

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function parseObject(raw: unknown): Record<string, unknown> | null {
  if (!raw) return null;
  let obj: unknown = raw;
  if (typeof raw === 'string') {
    try { obj = JSON.parse(raw); } catch { return null; }
  }
  return isPlainObject(obj) ? obj : null;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : typeof value === 'number' ? String(value) : '';
}

/**
 * resultData arrives as a JSON string; keep only well-formed sub-test objects. The flag is kept as stored
 * (upper-cased) and is null when the sub-test has none: an unknown or missing flag is never turned into NORMAL.
 */
export function parseResultData(raw: unknown): Record<string, LabSubTest> | null {
  const obj = parseObject(raw);
  if (!obj) return null;
  const out: Record<string, LabSubTest> = {};
  Object.entries(obj).forEach(([key, v]) => {
    if (!isPlainObject(v)) return;
    const flag = text(v.flag).trim().toUpperCase();
    const interpretation = text(v.interpretation).trim();
    out[key] = {
      value:          typeof v.value === 'number' || typeof v.value === 'string' ? v.value : '',
      unit:           text(v.unit),
      reference:      text(v.reference) || text(v.referenceRange),
      flag:           flag || null,
      interpretation: interpretation || undefined,
    };
  });
  return Object.keys(out).length ? out : null;
}

/** True for a flag that is present and is not NORMAL (LOW, HIGH, CRITICAL, ABNORMAL or any other text). */
export function isAbnormalLabFlag(flag: LabFlag | undefined): boolean {
  return !!flag && flag !== 'NORMAL';
}

/** Number of sub-tests of a result whose flag is present and not NORMAL. */
export function abnormalSubTestCount(result: Pick<DoctorLabResult, 'result_data'>): number {
  return result.result_data ? Object.values(result.result_data).filter(s => isAbnormalLabFlag(s.flag)).length : 0;
}

/** One structured sub-test row of the record form. */
export interface LabSubTestInput {
  name:            string;
  value:           string;
  unit?:           string;
  reference?:      string;
  /** '' or null for no flag. */
  flag?:           string | null;
  interpretation?: string;
}

/** "6.1" is stored as the number 6.1; text that is not exactly a number ("6.10", "Positive", "<5") stays text. */
function storedValue(typed: string): number | string {
  const n = Number(typed);
  return typed !== '' && Number.isFinite(n) && String(n) === typed ? n : typed;
}

/**
 * Serialises sub-test rows to the `resultData` JSON text:
 * `{ "<name>": { "value": 10.8, "unit": "g/dL", "reference": "12.0-16.0 g/dL", "flag": "LOW" } }`.
 * Returns '' when there is nothing to store (on an edit, '' clears the stored data).
 *
 * `originalRaw` is the stored text of the result being edited: entries that are not sub-test objects, and
 * properties of a sub-test the form does not show, are carried over instead of being dropped.
 */
export function buildLabResultData(rows: readonly LabSubTestInput[], originalRaw?: string | null): string {
  const original = parseObject(originalRaw) ?? {};
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(original)) {
    if (!isPlainObject(value)) out[key] = value;
  }
  for (const row of rows) {
    const name = row.name.trim().replace(/\s+/g, ' ');
    if (!name) continue;
    const previous = original[name];
    const entry: Record<string, unknown> = isPlainObject(previous) ? { ...previous } : {};
    // `referenceRange` is an old alias of `reference`; the row's reference is written under the current name.
    delete entry.referenceRange;
    entry.value = storedValue(row.value.trim());
    const optional: [string, string][] = [
      ['unit',           (row.unit ?? '').trim()],
      ['reference',      (row.reference ?? '').trim()],
      ['flag',           (row.flag ?? '').trim().toUpperCase()],
      ['interpretation', (row.interpretation ?? '').trim()],
    ];
    for (const [key, value] of optional) {
      if (value) entry[key] = value;
      else delete entry[key];
    }
    out[name] = entry;
  }
  return Object.keys(out).length > 0 ? JSON.stringify(out) : '';
}

// ─── Mapping ──────────────────────────────────────────────────────────────────

function upper(value: unknown): string {
  return typeof value === 'string' ? value.trim().toUpperCase() : '';
}

/** API row to the screen's view model. The status and flag shown are always the ones the server answered with. */
export function toDoctorLabResult(r: LabResultDto): DoctorLabResult {
  return {
    result_id:         r.resultId ?? 0,
    patient_system_id: r.patientSystemId ?? '',
    patient_id:        0,
    doctor_id:         0,
    doctor_system_id:  r.doctorSystemId ?? '',
    consultation_id:   r.consultationId ?? null,
    test_name:         r.testName ?? '',
    // The enums are fixed by the API; a value outside them is kept as sent so it is shown, not relabelled.
    test_type:         (upper(r.testType) || 'OTHER') as LabTestType,
    result_value:      r.resultValue ?? null,
    unit:              r.unit ?? null,
    reference_range:   r.referenceRange ?? null,
    result_data:       parseResultData(r.resultData),
    result_data_raw:   typeof r.resultData === 'string' && r.resultData.trim() ? r.resultData : null,
    status:            (upper(r.status) || 'PENDING') as LabStatus,
    is_critical:       r.isCritical ? 1 : 0,
    test_date:         r.testDate ? String(r.testDate) : '',
    reviewed_by:       null,
    reviewed_by_system_id: r.reviewedBy ?? null,
    reviewer_name:     r.reviewedByName?.trim() || null,
    reviewed_at:       r.reviewedAt ? String(r.reviewedAt) : null,
    review_notes:      r.reviewNotes?.trim() || null,
    document_id:       typeof r.documentId === 'number' ? r.documentId : null,
    file_url:          null,
    created_at:        r.createdAt ? String(r.createdAt) : '',
    doctor_name:           r.doctorName?.trim() || 'Unknown Doctor',
    recorded_by_name:      r.doctorName?.trim() || null,
    doctor_specialization: null,
    doctor_avatar_url:     null,
    patient_name:          r.patientName?.trim() || 'Unknown Patient',
    patient_avatar_url:    null,
  };
}

// ─── API calls ────────────────────────────────────────────────────────────────

const LAB_RESULTS_URL = '/api/lab-results';

/** GET /api/lab-results: one page, newest test date first. Without `status`, cancelled results are left out. */
export function listLabResults(query: ResultListQuery = {}): Promise<PageResponse<LabResultDto>> {
  const { page = 0, size, ...filters } = query;
  return fetchPage<LabResultDto>(LAB_RESULTS_URL, page, size, filters);
}

/**
 * GET /api/lab-results/me/pending-review: unreviewed results with status REQUIRES_REVIEW or CRITICAL that the
 * signed-in doctor recorded or that belong to a patient on their care team.
 */
export function listLabPendingReview(page = 0, size?: number): Promise<PageResponse<LabResultDto>> {
  return fetchPage<LabResultDto>(`${LAB_RESULTS_URL}/me/pending-review`, page, size);
}

/** GET /api/lab-results/{id}. A cancelled result is returned too. */
export async function getLabResult(id: number): Promise<DoctorLabResult> {
  const { data } = await api.get<LabResultDto>(`${LAB_RESULTS_URL}/${id}`);
  return toDoctorLabResult(data);
}

/**
 * POST /api/lab-results. `idempotencyKey` is the key of the open form (`useIdempotencyKey`): a retried submit
 * repeats it and the server answers with the result stored by the first call.
 */
export async function createLabResult(body: CreateLabResultBody, idempotencyKey: string): Promise<DoctorLabResult> {
  const { data } = await api.post<LabResultDto>(LAB_RESULTS_URL, body, withIdempotencyKey(idempotencyKey));
  return toDoctorLabResult(data);
}

/** PUT /api/lab-results/{id}: the recording doctor only. Send only the fields that changed. */
export async function updateLabResult(id: number, body: UpdateLabResultBody): Promise<DoctorLabResult> {
  const { data } = await api.put<LabResultDto>(`${LAB_RESULTS_URL}/${id}`, body);
  return toDoctorLabResult(data);
}

/**
 * PATCH /api/lab-results/{id}/status. The returned status can differ from the requested one: a result flagged
 * critical that is set to COMPLETED comes back CRITICAL.
 */
export async function updateLabResultStatus(id: number, status: WritableResultStatus): Promise<DoctorLabResult> {
  const { data } = await api.patch<LabResultDto>(`${LAB_RESULTS_URL}/${id}/status`, undefined, { params: { status } });
  return toDoctorLabResult(data);
}

/** POST /api/lab-results/{id}/review: signs the result off as COMPLETED or CRITICAL; the reviewer is the caller. */
export async function reviewLabResult(id: number, body: ReviewResultBody): Promise<DoctorLabResult> {
  const { data } = await api.post<LabResultDto>(`${LAB_RESULTS_URL}/${id}/review`, body);
  return toDoctorLabResult(data);
}

/** DELETE /api/lab-results/{id}: sets the status to CANCELLED (nothing is deleted). The recording doctor only. */
export async function cancelLabResult(id: number): Promise<void> {
  await api.delete(`${LAB_RESULTS_URL}/${id}`);
}

/** GET /api/doctors/patients/options: id and name of the signed-in doctor's patients (at most 500). */
export async function listDoctorPatientOptions(query?: string): Promise<DoctorPatientOption[]> {
  const q = (query ?? '').trim();
  const { data } = await api.get<DoctorPatientOption[]>('/api/doctors/patients/options', {
    params: q ? { query: q } : undefined,
  });
  return Array.isArray(data) ? data : [];
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

export interface DoctorLabResultFilters {
  /** With a patient: that patient's results by any doctor. Without: the results the signed-in doctor recorded. */
  patientSystemId?: string | null;
  status?:          LabStatus | null;
  type?:            LabTestType | null;
  /** true: only results flagged critical; false: only results that are not. */
  critical?:        boolean | null;
  consultationId?:  number | null;
}

export interface ResultListOptions {
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

const LOAD_ERROR = 'Failed to load lab results.';

/** The canonical paged list with its filters, for `PagedList`. A filter change starts again from the first page. */
export function useDoctorLabResultList(
  filters: DoctorLabResultFilters = {},
  options: ResultListOptions = {},
): PagedState<DoctorLabResult> {
  const patientSystemId = filters.patientSystemId || undefined;
  const status          = filters.status || undefined;
  const type            = filters.type || undefined;
  const critical        = filters.critical ?? undefined;
  const consultationId  = filters.consultationId ?? undefined;
  return usePaged<DoctorLabResult>(
    (page, size) => listLabResults({ patientSystemId, status, type, critical, consultationId, page, size })
      .then(result => mapPage(result, toDoctorLabResult)),
    [patientSystemId, status, type, critical, consultationId],
    { pageSize: options.pageSize, enabled: options.enabled, errorMessage: LOAD_ERROR, keyOf: r => r.result_id },
  );
}

/** The signed-in doctor's review queue. `total` is the queue size (usable as a tab badge). */
export function useDoctorLabPendingReview(options: ResultListOptions = {}): PagedState<DoctorLabResult> {
  return usePaged<DoctorLabResult>(
    (page, size) => listLabPendingReview(page, size).then(result => mapPage(result, toDoctorLabResult)),
    [],
    { pageSize: options.pageSize, enabled: options.enabled, errorMessage: 'Failed to load the review queue.', keyOf: r => r.result_id },
  );
}

/**
 * Number of results flagged critical: the signed-in doctor's own, or one patient's when `patientSystemId` is
 * given. `count` is null until it is known and when the request fails (the banner it feeds is then not shown).
 */
export function useCriticalLabResultCount(
  patientSystemId?: string | null,
  options: { enabled?: boolean } = {},
): { count: number | null; refetch: () => void } {
  const patient = patientSystemId || undefined;
  const { data, refetch } = useResource(
    () => listLabResults({ patientSystemId: patient, critical: true, page: 0, size: 1 }).then(p => p.totalElements),
    [patient],
    { enabled: options.enabled },
  );
  return { count: data, refetch };
}

/** The doctor's patients (id and name) from GET /api/doctors/patients/options. */
export function useDoctorPatientOptions(options: { enabled?: boolean } = {}): {
  options: DoctorPatientOption[];
  /** The patient's full name, or null when the id is not one of the doctor's patients (or the list is not loaded). */
  nameOf:  (patientSystemId: string | null | undefined) => string | null;
  loading: boolean;
  error:   string | null;
  refetch: () => void;
} {
  const { data, loading, error, refetch } = useResource(() => listDoctorPatientOptions(), [], {
    enabled: options.enabled, errorMessage: 'Failed to load your patients.',
  });
  const byId = useMemo(() => {
    const map = new Map<string, string>();
    (data ?? []).forEach(p => {
      const name = (p.fullName ?? `${p.firstName ?? ''} ${p.lastName ?? ''}`).trim();
      if (p.patientSystemId && name) map.set(p.patientSystemId, name);
    });
    return map;
  }, [data]);
  const nameOf = useCallback((id: string | null | undefined) => (id ? byId.get(id) ?? null : null), [byId]);
  return { options: data ?? [], nameOf, loading, error, refetch };
}

/**
 * The name of one of the doctor's patients, for the patient filter chip. Asks the options endpoint for that id
 * only. `name` is null while loading, when the lookup fails and when the id is not one of the doctor's patients.
 */
export function useDoctorPatientName(patientSystemId: string | null | undefined): { name: string | null; loading: boolean } {
  const id = (patientSystemId ?? '').trim();
  const { data, loading } = useResource(() => listDoctorPatientOptions(id), [id], { enabled: !!id });
  const match = (data ?? []).find(p => p.patientSystemId === id);
  const name = match ? (match.fullName ?? `${match.firstName ?? ''} ${match.lastName ?? ''}`).trim() : '';
  return { name: name || null, loading };
}

/** Most rows the list-without-paging hook loads: what the legacy array endpoint returned (newest 200). */
const UNPAGED_ROW_CAP = 200;

/**
 * The results the signed-in doctor recorded, newest first, for screens without a "Load more" control (the native
 * doctor screen): loads up to 200 rows from the paged endpoint. Cancelled results are left out by the server.
 * Screens that page and filter use `useDoctorLabResultList`.
 */
export function useDoctorLabResults(): State {
  const paged = useDoctorLabResultList({}, { pageSize: 100 });
  const { items, loading, loadingMore, error, hasMore, total, loadMore, refetch } = paged;

  const filling = !loading && !error && hasMore && items.length < UNPAGED_ROW_CAP;
  useEffect(() => {
    if (filling && !loadingMore) loadMore();
  }, [filling, loadingMore, loadMore, items.length]);

  return {
    results: items,
    // The list is shown once the rows are complete, so counts made from it do not jump while it fills.
    loading: loading || filling,
    error,
    refetch,
    hasMore,
    loadMore,
    total,
  };
}
