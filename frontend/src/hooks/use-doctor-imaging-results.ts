import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type { ImagingType, ResultStatus, WritableResultStatus } from '@/lib/validation/results';
import type { ResultListOptions, ResultListQuery, ReviewResultBody } from './use-doctor-lab-results';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

// Doctor imaging results: the canonical paged list (GET /api/imaging-results), the review queue, and the write
// calls (record, edit, change status, review, cancel). Contract: .claude/build-plan/api/be-lab-imaging.md.
// Rows are used as the API sends them (camelCase).

export type { ImagingType, ResultStatus, ReviewStatus, WritableResultStatus } from '@/lib/validation/results';
export type { ResultListOptions, ResultListQuery, ReviewResultBody } from './use-doctor-lab-results';

export interface ImagingResultDto {
  imagingId: number; patientSystemId: string; patientName: string | null;
  doctorSystemId: string; doctorName: string | null; consultationId: number | null;
  imagingType: ImagingType; bodyPart: string | null; radiologistReport: string | null;
  findings: string | null; impression: string | null; status: ResultStatus;
  /** Derived by the server: true exactly when `status` is CRITICAL. */
  isCritical: boolean;
  imageDate: string; reviewedBy: string | null; reviewedByName: string | null; reviewedAt: string | null;
  reviewNotes: string | null;
  /** Attached document (GET /api/documents/{id}/download); null when none. */
  documentId: number | null;
  fileUrl: null; thumbnailUrl: null; createdAt: string;
}

export interface CreateImagingResultBody {
  patientSystemId: string; consultationId?: number; imagingType: ImagingType; bodyPart?: string;
  radiologistReport?: string; findings?: string; impression?: string; status?: WritableResultStatus;
  imageDate?: string; documentId?: number;
}

/** Partial update: a field that is left out is unchanged; an optional text sent as '' is cleared. */
export type UpdateImagingResultBody = Partial<Omit<CreateImagingResultBody, 'patientSystemId' | 'consultationId'>>;

const IMAGING_RESULTS_URL = '/api/imaging-results';

// ─── API calls ────────────────────────────────────────────────────────────────

/** GET /api/imaging-results: one page, newest image date first. Without `status`, cancelled results are left out. */
export function listImagingResults(query: ResultListQuery = {}): Promise<PageResponse<ImagingResultDto>> {
  const { page = 0, size, ...filters } = query;
  return fetchPage<ImagingResultDto>(IMAGING_RESULTS_URL, page, size, filters);
}

/**
 * GET /api/imaging-results/me/pending-review: unreviewed results with status REQUIRES_REVIEW or CRITICAL that
 * the signed-in doctor recorded or that belong to a patient on their care team.
 */
export function listImagingPendingReview(page = 0, size?: number): Promise<PageResponse<ImagingResultDto>> {
  return fetchPage<ImagingResultDto>(`${IMAGING_RESULTS_URL}/me/pending-review`, page, size);
}

/** GET /api/imaging-results/{id}. A cancelled result is returned too. */
export async function getImagingResult(id: number): Promise<ImagingResultDto> {
  const { data } = await api.get<ImagingResultDto>(`${IMAGING_RESULTS_URL}/${id}`);
  return data;
}

/**
 * POST /api/imaging-results. `idempotencyKey` is the key of the open form (`useIdempotencyKey`): a retried
 * submit repeats it and the server answers with the result stored by the first call.
 */
export async function createImagingResult(body: CreateImagingResultBody, idempotencyKey: string): Promise<ImagingResultDto> {
  const { data } = await api.post<ImagingResultDto>(IMAGING_RESULTS_URL, body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** PUT /api/imaging-results/{id}: the recording doctor only. Send only the fields that changed. */
export async function updateImagingResult(id: number, body: UpdateImagingResultBody): Promise<ImagingResultDto> {
  const { data } = await api.put<ImagingResultDto>(`${IMAGING_RESULTS_URL}/${id}`, body);
  return data;
}

/** PATCH /api/imaging-results/{id}/status. */
export async function updateImagingResultStatus(id: number, status: WritableResultStatus): Promise<ImagingResultDto> {
  const { data } = await api.patch<ImagingResultDto>(`${IMAGING_RESULTS_URL}/${id}/status`, undefined, { params: { status } });
  return data;
}

/** POST /api/imaging-results/{id}/review: signs the result off as COMPLETED or CRITICAL; the reviewer is the caller. */
export async function reviewImagingResult(id: number, body: ReviewResultBody): Promise<ImagingResultDto> {
  const { data } = await api.post<ImagingResultDto>(`${IMAGING_RESULTS_URL}/${id}/review`, body);
  return data;
}

/** DELETE /api/imaging-results/{id}: sets the status to CANCELLED (nothing is deleted). The recording doctor only. */
export async function cancelImagingResult(id: number): Promise<void> {
  await api.delete(`${IMAGING_RESULTS_URL}/${id}`);
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

export interface DoctorImagingResultFilters {
  /** With a patient: that patient's results by any doctor. Without: the results the signed-in doctor recorded. */
  patientSystemId?: string | null;
  status?:          ResultStatus | null;
  type?:            ImagingType | null;
  /** true: only results with status CRITICAL; false: only the others. */
  critical?:        boolean | null;
  consultationId?:  number | null;
}

/** The canonical paged list with its filters, for `PagedList`. A filter change starts again from the first page. */
export function useDoctorImagingResults(
  filters: DoctorImagingResultFilters = {},
  options: ResultListOptions = {},
): PagedState<ImagingResultDto> {
  const patientSystemId = filters.patientSystemId || undefined;
  const status          = filters.status || undefined;
  const type            = filters.type || undefined;
  const critical        = filters.critical ?? undefined;
  const consultationId  = filters.consultationId ?? undefined;
  return usePaged<ImagingResultDto>(
    (page, size) => listImagingResults({ patientSystemId, status, type, critical, consultationId, page, size }),
    [patientSystemId, status, type, critical, consultationId],
    { pageSize: options.pageSize, enabled: options.enabled, errorMessage: 'Failed to load imaging results.', keyOf: r => r.imagingId },
  );
}

/** The signed-in doctor's imaging review queue. `total` is the queue size (usable as a tab badge). */
export function useDoctorImagingPendingReview(options: ResultListOptions = {}): PagedState<ImagingResultDto> {
  return usePaged<ImagingResultDto>(
    (page, size) => listImagingPendingReview(page, size),
    [],
    { pageSize: options.pageSize, enabled: options.enabled, errorMessage: 'Failed to load the review queue.', keyOf: r => r.imagingId },
  );
}

/**
 * Number of critical imaging results: the signed-in doctor's own, or one patient's when `patientSystemId` is
 * given. `count` is null until it is known and when the request fails (the banner it feeds is then not shown).
 */
export function useCriticalImagingResultCount(
  patientSystemId?: string | null,
  options: { enabled?: boolean } = {},
): { count: number | null; refetch: () => void } {
  const patient = patientSystemId || undefined;
  const { data, refetch } = useResource(
    () => listImagingResults({ patientSystemId: patient, critical: true, page: 0, size: 1 }).then(p => p.totalElements),
    [patient],
    { enabled: options.enabled },
  );
  return { count: data, refetch };
}
