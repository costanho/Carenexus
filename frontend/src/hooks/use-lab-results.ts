import type { PagedState } from './use-paged';
import type { ResourceState } from './use-resource';
import {
  enumOr, idOrNull, recordDoctor, recordPatientName, textOrNull,
  sumCounts, usePatientRecordById, usePatientRecordCount, usePatientRecordCounts, usePatientRecordPages,
  type PatientRecordContext, type RecordFilterValue,
} from './use-medical-records';

export type LabTestType = 'BLOOD' | 'URINE' | 'STOOL' | 'CULTURE' | 'BIOPSY' | 'GENETIC' | 'SWAB' | 'OTHER';
export type LabResultStatus = 'PENDING' | 'COMPLETED' | 'CRITICAL' | 'REQUIRES_REVIEW' | 'CANCELLED';

export const LAB_TEST_TYPES: readonly LabTestType[] = [
  'BLOOD', 'URINE', 'STOOL', 'CULTURE', 'BIOPSY', 'GENETIC', 'SWAB', 'OTHER',
];
const LAB_STATUSES: readonly LabResultStatus[] = ['PENDING', 'REQUIRES_REVIEW', 'COMPLETED', 'CRITICAL', 'CANCELLED'];

/** `LabResultResponse` as the API sends it (GET /api/lab-results and /api/lab-results/{id}). */
export interface LabResultDto {
  resultId:        number;
  patientSystemId: string;
  patientName:     string | null;
  doctorSystemId:  string;
  doctorName:      string | null;
  consultationId:  number | null;
  testName:        string;
  testType:        LabTestType;
  resultValue:     string | null;
  unit:            string | null;
  referenceRange:  string | null;
  /** JSON text of an object. */
  resultData:      string | null;
  status:          LabResultStatus;
  isCritical:      boolean;
  /** Date only, YYYY-MM-DD. */
  testDate:        string;
  reviewedBy:      string | null;
  reviewedByName:  string | null;
  /** Clinic wall-clock date-time. */
  reviewedAt:      string | null;
  reviewNotes:     string | null;
  documentId:      number | null;
  /** Deprecated; always null. */
  fileUrl:         string | null;
  createdAt:       string;
}

export interface LabResult {
  result_id:             number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  test_name:             string;
  test_type:             LabTestType;
  result_value:          string | null;
  unit:                  string | null;
  reference_range:       string | null;
  result_data:           Record<string, unknown> | null;
  status:                LabResultStatus;
  is_critical:           number;
  /** Date only, YYYY-MM-DD. */
  test_date:             string;
  reviewed_by:           number | null;
  /** Clinic wall-clock date-time. */
  reviewed_at:           string | null;
  /** Always null: an attachment is offered through `document_id`. */
  file_url:              string | null;
  created_at:            string;
  doctor_system_id:      string;
  doctor_name:           string;
  doctor_specialization: string | null;
  doctor_avatar_url:     string | null;
  /** The reviewing doctor's name, never the raw id. */
  reviewer_name:         string | null;
  patient_name:          string;
  /** Note written by the reviewing doctor. */
  review_notes:          string | null;
  /** Attached document (GET /api/documents/{id}/download), or null. */
  document_id:           number | null;
}

/** `resultData` is JSON text of an object; anything else (an array, a number, broken JSON) is no structured data. */
function parseResultData(raw: unknown): Record<string, unknown> | null {
  let value: unknown = raw;
  if (typeof raw === 'string') {
    if (!raw.trim()) return null;
    try { value = JSON.parse(raw); } catch { return null; }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return Object.keys(value).length > 0 ? (value as Record<string, unknown>) : null;
}

export function toLabResult(r: LabResultDto, ctx: PatientRecordContext): LabResult {
  return {
    result_id:         r.resultId ?? 0,
    patient_id:        0,
    doctor_id:         0,
    consultation_id:   idOrNull(r.consultationId),
    test_name:         r.testName ?? '',
    test_type:         enumOr(r.testType, LAB_TEST_TYPES, 'OTHER'),
    result_value:      textOrNull(r.resultValue),
    unit:              textOrNull(r.unit),
    reference_range:   textOrNull(r.referenceRange),
    result_data:       parseResultData(r.resultData),
    status:            enumOr(r.status, LAB_STATUSES, 'PENDING'),
    is_critical:       r.isCritical ? 1 : 0,
    test_date:         textOrNull(r.testDate) ?? '',
    reviewed_by:       null,
    reviewed_at:       textOrNull(r.reviewedAt),
    file_url:          null,
    created_at:        textOrNull(r.createdAt) ?? '',
    ...recordDoctor(r.doctorSystemId, r.doctorName, ctx),
    doctor_avatar_url: null,
    reviewer_name:     textOrNull(r.reviewedByName),
    patient_name:      recordPatientName(r.patientName, ctx),
    review_notes:      textOrNull(r.reviewNotes),
    document_id:       idOrNull(r.documentId),
  };
}

export interface LabResultsOptions {
  /** Only results of this test type (filtered by the server). */
  type?:     LabTestType;
  /** true: only results flagged critical; false: only the others. */
  critical?: boolean;
  /** Rows per request. Default `RECORD_LIST_DEFAULT_PAGE_SIZE` (100); later rows come with `loadMore`. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

interface State {
  results:     LabResult[];
  loading:     boolean;
  error:       string | null;
  refetch:     () => void;
  /** True when the server has more results than are loaded. */
  hasMore:     boolean;
  /** Fetches the next page and appends it to `results`. */
  loadMore:    () => void;
  loadingMore: boolean;
  refreshing:  boolean;
  /** Number of results on the server for the current filter. */
  total:       number;
  errorStatus: number | null;
  /** The same list in the shape `PagedList` renders. */
  paged:       PagedState<LabResult>;
}

const LAB_RESULTS_URL = '/api/lab-results';
const labResultKey = (row: LabResult) => row.result_id;

/**
 * The signed-in patient's lab results, newest test date first (GET /api/lab-results). Cancelled results are left
 * out by the server.
 */
export function useLabResults(options: LabResultsOptions = {}): State {
  const paged = usePatientRecordPages<LabResultDto, LabResult>({
    url:          LAB_RESULTS_URL,
    filters:      { type: options.type, critical: options.critical },
    map:          toLabResult,
    keyOf:        labResultKey,
    errorMessage: 'Failed to load lab results.',
    pageSize:     options.pageSize,
    enabled:      options.enabled,
  });
  return {
    results: paged.items, loading: paged.loading, error: paged.error, refetch: paged.refetch,
    hasMore: paged.hasMore, loadMore: paged.loadMore, loadingMore: paged.loadingMore,
    refreshing: paged.refreshing, total: paged.total, errorStatus: paged.errorStatus, paged,
  };
}

/** One lab result by id (GET /api/lab-results/{id}); `null` loads nothing. A cancelled result is still returned. */
export function useLabResultById(resultId: number | null): ResourceState<LabResult> {
  return usePatientRecordById<LabResultDto, LabResult>({
    url: LAB_RESULTS_URL, id: resultId, map: toLabResult, errorMessage: 'Failed to load the lab result.',
  });
}

/** How many of the patient's lab results are flagged critical. Null until known. */
export function useCriticalLabResultCount(options: { enabled?: boolean } = {}): { count: number | null; refetch: () => void } {
  return usePatientRecordCount(LAB_RESULTS_URL, { critical: true }, options);
}

/** The counts shown above the patient's lab results: the stat cards and the count on each type filter. */
export interface LabResultSummary {
  /** Every result the patient can see (cancelled ones are left out by the server). */
  total:     number | null;
  completed: number | null;
  /** PENDING plus REQUIRES_REVIEW: results the patient is still waiting on. */
  pending:   number | null;
  /** Results flagged critical. */
  critical:  number | null;
  byType:    Record<LabTestType, number | null>;
  /** Counts again (after a refresh). */
  refetch:   () => void;
}

const SUMMARY_SETS: Record<string, Record<string, RecordFilterValue>> = {
  total:     {},
  completed: { status: 'COMPLETED' },
  pending:   { status: 'PENDING' },
  review:    { status: 'REQUIRES_REVIEW' },
  critical:  { critical: true },
  ...Object.fromEntries(LAB_TEST_TYPES.map(t => [`type:${t}`, { type: t }])),
};

/**
 * How many of the patient's lab results there are in total, per status group and per type, each read from
 * `totalElements` of a one-row page of GET /api/lab-results (the API has no count endpoint). A count
 * that is still loading, or whose request failed, is null.
 */
export function useLabResultSummary(options: { enabled?: boolean } = {}): LabResultSummary {
  const { counts, refetch } = usePatientRecordCounts(LAB_RESULTS_URL, SUMMARY_SETS, options);
  const byType = Object.fromEntries(LAB_TEST_TYPES.map(t => [t, counts[`type:${t}`]])) as Record<LabTestType, number | null>;
  return {
    total:     counts.total,
    completed: counts.completed,
    pending:   sumCounts(counts.pending, counts.review),
    critical:  counts.critical,
    byType,
    refetch,
  };
}
