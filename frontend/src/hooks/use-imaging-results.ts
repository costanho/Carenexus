import type { PagedState } from './use-paged';
import type { ResourceState } from './use-resource';
import {
  enumOr, idOrNull, recordDoctor, recordPatientName, textOrNull,
  sumCounts, usePatientRecordById, usePatientRecordCount, usePatientRecordCounts, usePatientRecordPages,
  type PatientRecordContext, type RecordFilterValue,
} from './use-medical-records';

export type ImagingType = 'XRAY' | 'MRI' | 'CT_SCAN' | 'ULTRASOUND' | 'PET' | 'MAMMOGRAPHY' | 'DEXA' | 'ECHO';
/** Every status the API can send. List rows are never CANCELLED; a result read by id can be. */
export type ImagingResultStatus = 'PENDING' | 'COMPLETED' | 'REQUIRES_REVIEW' | 'CRITICAL' | 'CANCELLED';

export const IMAGING_TYPES: readonly ImagingType[] = [
  'XRAY', 'MRI', 'CT_SCAN', 'ULTRASOUND', 'PET', 'MAMMOGRAPHY', 'DEXA', 'ECHO',
];
const IMAGING_STATUSES: readonly ImagingResultStatus[] = ['PENDING', 'REQUIRES_REVIEW', 'COMPLETED', 'CRITICAL', 'CANCELLED'];

/** `ImagingResultResponse` as the API sends it (GET /api/imaging-results and /api/imaging-results/{id}). */
export interface ImagingResultDto {
  imagingId:         number;
  patientSystemId:   string;
  patientName:       string | null;
  doctorSystemId:    string;
  doctorName:        string | null;
  consultationId:    number | null;
  imagingType:       ImagingType;
  bodyPart:          string | null;
  radiologistReport: string | null;
  findings:          string | null;
  impression:        string | null;
  status:            ImagingResultStatus;
  /** Derived by the server: status is CRITICAL. */
  isCritical:        boolean;
  /** Date only, YYYY-MM-DD. */
  imageDate:         string;
  reviewedBy:        string | null;
  reviewedByName:    string | null;
  /** Clinic wall-clock date-time. */
  reviewedAt:        string | null;
  reviewNotes:       string | null;
  documentId:        number | null;
  /** Deprecated; always null. */
  fileUrl:           string | null;
  /** Deprecated; always null. */
  thumbnailUrl:      string | null;
  createdAt:         string;
}

export interface ImagingResult {
  imaging_id:            number;
  patient_id:            number;
  doctor_id:             number;
  consultation_id:       number | null;
  imaging_type:          ImagingType;
  body_part:             string | null;
  radiologist_report:    string | null;
  findings:              string | null;
  impression:            string | null;
  /** Always null: an attachment is offered through `document_id`. */
  file_url:              string | null;
  /** Always null. */
  thumbnail_url:         string | null;
  status:                'PENDING' | 'COMPLETED' | 'REQUIRES_REVIEW' | 'CRITICAL';
  /** Date only, YYYY-MM-DD. */
  image_date:            string;
  reviewed_by:           number | null;
  /** Clinic wall-clock date-time. */
  reviewed_at:           string | null;
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

/**
 * An imaging result that may be cancelled: what a read by id returns. Every list row (`ImagingResult`) is one of
 * these, so components that show both take this type.
 */
export type ImagingResultRecord = Omit<ImagingResult, 'status'> & { status: ImagingResultStatus };

export function toImagingResultRecord(r: ImagingResultDto, ctx: PatientRecordContext): ImagingResultRecord {
  return {
    imaging_id:         r.imagingId ?? 0,
    patient_id:         0,
    doctor_id:          0,
    consultation_id:    idOrNull(r.consultationId),
    imaging_type:       enumOr(r.imagingType, IMAGING_TYPES, 'XRAY'),
    body_part:          textOrNull(r.bodyPart),
    radiologist_report: textOrNull(r.radiologistReport),
    findings:           textOrNull(r.findings),
    impression:         textOrNull(r.impression),
    file_url:           null,
    thumbnail_url:      null,
    status:             enumOr(r.status, IMAGING_STATUSES, 'PENDING'),
    image_date:         textOrNull(r.imageDate) ?? '',
    reviewed_by:        null,
    reviewed_at:        textOrNull(r.reviewedAt),
    created_at:         textOrNull(r.createdAt) ?? '',
    ...recordDoctor(r.doctorSystemId, r.doctorName, ctx),
    doctor_avatar_url:  null,
    reviewer_name:      textOrNull(r.reviewedByName),
    patient_name:       recordPatientName(r.patientName, ctx),
    review_notes:       textOrNull(r.reviewNotes),
    document_id:        idOrNull(r.documentId),
  };
}

/**
 * A list row, or null for a cancelled result: the list endpoint leaves those out, and the list view model has no
 * status for one.
 */
export function toImagingResult(r: ImagingResultDto, ctx: PatientRecordContext): ImagingResult | null {
  const row = toImagingResultRecord(r, ctx);
  return row.status === 'CANCELLED' ? null : { ...row, status: row.status };
}

export interface ImagingResultsOptions {
  /** Only studies of this type (filtered by the server). */
  type?:     ImagingType;
  /** true: only critical results; false: only the others. */
  critical?: boolean;
  /** Rows per request. Default `RECORD_LIST_DEFAULT_PAGE_SIZE` (100); later rows come with `loadMore`. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

interface State {
  results:     ImagingResult[];
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
  paged:       PagedState<ImagingResult>;
}

const IMAGING_RESULTS_URL = '/api/imaging-results';
const imagingResultKey = (row: { imaging_id: number }) => row.imaging_id;

/**
 * The signed-in patient's imaging results, newest image date first (GET /api/imaging-results). Cancelled results
 * are left out by the server.
 */
export function useImagingResults(options: ImagingResultsOptions = {}): State {
  const paged = usePatientRecordPages<ImagingResultDto, ImagingResult>({
    url:          IMAGING_RESULTS_URL,
    filters:      { type: options.type, critical: options.critical },
    map:          toImagingResult,
    keyOf:        imagingResultKey,
    errorMessage: 'Failed to load imaging results.',
    pageSize:     options.pageSize,
    enabled:      options.enabled,
  });
  return {
    results: paged.items, loading: paged.loading, error: paged.error, refetch: paged.refetch,
    hasMore: paged.hasMore, loadMore: paged.loadMore, loadingMore: paged.loadingMore,
    refreshing: paged.refreshing, total: paged.total, errorStatus: paged.errorStatus, paged,
  };
}

/** One imaging result by id (GET /api/imaging-results/{id}); `null` loads nothing. A cancelled result is still returned. */
export function useImagingResultById(imagingId: number | null): ResourceState<ImagingResultRecord> {
  return usePatientRecordById<ImagingResultDto, ImagingResultRecord>({
    url: IMAGING_RESULTS_URL, id: imagingId, map: toImagingResultRecord, errorMessage: 'Failed to load the imaging result.',
  });
}

/** How many of the patient's imaging results are critical. Null until known. */
export function useCriticalImagingResultCount(options: { enabled?: boolean } = {}): { count: number | null; refetch: () => void } {
  return usePatientRecordCount(IMAGING_RESULTS_URL, { critical: true }, options);
}

/** The counts shown above the patient's imaging results: the stat cards and the count on each type filter. */
export interface ImagingResultSummary {
  /** Every result the patient can see (cancelled ones are left out by the server). */
  total:     number | null;
  completed: number | null;
  /** PENDING plus REQUIRES_REVIEW: results the patient is still waiting on. */
  pending:   number | null;
  /** Results with status CRITICAL. */
  critical:  number | null;
  byType:    Record<ImagingType, number | null>;
  /** Counts again (after a refresh). */
  refetch:   () => void;
}

const SUMMARY_SETS: Record<string, Record<string, RecordFilterValue>> = {
  total:     {},
  completed: { status: 'COMPLETED' },
  pending:   { status: 'PENDING' },
  review:    { status: 'REQUIRES_REVIEW' },
  critical:  { critical: true },
  ...Object.fromEntries(IMAGING_TYPES.map(t => [`type:${t}`, { type: t }])),
};

/**
 * How many of the patient's imaging results there are in total, per status group and per type, each read from
 * `totalElements` of a one-row page of GET /api/imaging-results (the API has no count endpoint). A count
 * that is still loading, or whose request failed, is null.
 */
export function useImagingResultSummary(options: { enabled?: boolean } = {}): ImagingResultSummary {
  const { counts, refetch } = usePatientRecordCounts(IMAGING_RESULTS_URL, SUMMARY_SETS, options);
  const byType = Object.fromEntries(IMAGING_TYPES.map(t => [t, counts[`type:${t}`]])) as Record<ImagingType, number | null>;
  return {
    total:     counts.total,
    completed: counts.completed,
    pending:   sumCounts(counts.pending, counts.review),
    critical:  counts.critical,
    byType,
    refetch,
  };
}
