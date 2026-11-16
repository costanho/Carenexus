import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  CorrectionRequest, CorrectionStatus, CreateCorrectionRequestBody, ResolveCorrectionRequestBody,
} from './types';

// Typed client of the correction-request endpoints (API contract be-corrections). One function per endpoint;
// each rejects with the axios error (show it with `apiError`). The administrator endpoints live in lib/admin.

const BASE = '/api/correction-requests';

/**
 * Files a correction request (patient, or caregiver allowed to manage the care plan). Send the same
 * `idempotencyKey` when a submit is retried. 409: an open request already exists for the record. 429: more than
 * ten requests in a day.
 */
export async function createCorrectionRequest(body: CreateCorrectionRequestBody, idempotencyKey: string): Promise<CorrectionRequest> {
  const { data } = await api.post<CorrectionRequest>(BASE, body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** Every correction request about one patient, newest first (the patient, a caregiver with records access, a related doctor). */
export function listPatientCorrectionRequests(
  patientSystemId: string,
  status: CorrectionStatus | null,
  page: number,
  size: number,
): Promise<PageResponse<CorrectionRequest>> {
  return fetchPage<CorrectionRequest>(`${BASE}/patient/${encodeURIComponent(patientSystemId)}`, page, size, { status });
}

/** The signed-in doctor's review queue, newest first. */
export function listAssignedCorrectionRequests(
  status: CorrectionStatus | null,
  page: number,
  size: number,
): Promise<PageResponse<CorrectionRequest>> {
  return fetchPage<CorrectionRequest>(`${BASE}/assigned`, page, size, { status });
}

/** One request by id (the `id` of a notification link). 403 or 404 when the caller may not see it. */
export async function getCorrectionRequest(requestId: number): Promise<CorrectionRequest> {
  const { data } = await api.get<CorrectionRequest>(`${BASE}/${requestId}`);
  return data;
}

/** Accepts or rejects an open request (the assigned doctor, or an administrator). 409 when it is already resolved. */
export async function resolveCorrectionRequest(requestId: number, body: ResolveCorrectionRequestBody): Promise<CorrectionRequest> {
  const { data } = await api.post<CorrectionRequest>(`${BASE}/${requestId}/resolve`, body);
  return data;
}
