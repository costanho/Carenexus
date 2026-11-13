import { getDetection, listPatientDetections, reviewDetection, runDetection } from '@/lib/ai/api';
import type {
  DetectionDecision,
  DetectionReviewStatus,
  DetectionRunResult,
  DiseaseDetection,
} from '@/lib/ai/types';
import { type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Disease detections: the screening results behind DISEASE_RISK alerts (contract api/be-ai-detection.md).
// A detection is written only by the screening pipeline; a related doctor confirms or rejects it once.

export interface PatientDetectionsOptions {
  /** Narrows the list for doctors and administrators. Patients and caregivers always get confirmed detections. */
  reviewStatus?: DetectionReviewStatus;
  /** When false nothing is requested and the list is empty. Default true. */
  enabled?:      boolean;
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?:     number;
}

const keyOfDetection = (detection: DiseaseDetection) => detection.detectionId;

/**
 * One patient's detections (GET /api/disease-detections/patient/{patientSystemId}), newest first. Nothing is
 * requested while `patientSystemId` is empty. A caregiver without the records permission, or one whose access
 * ended, gets a 403 (`errorStatus === 403`).
 */
export function usePatientDetections(
  patientSystemId: string | null | undefined,
  options: PatientDetectionsOptions = {},
): PagedState<DiseaseDetection> {
  const id = (patientSystemId ?? '').trim();
  const { reviewStatus } = options;
  return usePaged<DiseaseDetection>(
    (page, size) => listPatientDetections(id, { reviewStatus }, page, size),
    [id, reviewStatus],
    {
      enabled:      (options.enabled ?? true) && id.length > 0,
      pageSize:     options.pageSize,
      errorMessage: 'Screening results could not be loaded.',
      keyOf:        keyOfDetection,
    },
  );
}

/**
 * One detection (GET /api/disease-detections/{detectionId}). Nothing is requested while the id is null.
 * `setData` applies the detection returned by a review without another request.
 */
export function useDetection(
  detectionId: number | null | undefined,
  options: { enabled?: boolean } = {},
): ResourceState<DiseaseDetection> {
  const valid = typeof detectionId === 'number' && Number.isInteger(detectionId) && detectionId > 0;
  return useResource<DiseaseDetection>(
    () => getDetection(detectionId as number),
    [valid ? detectionId : null],
    { enabled: (options.enabled ?? true) && valid, errorMessage: 'The screening result could not be loaded.' },
  );
}

/**
 * Confirms or rejects a pending detection (POST /api/disease-detections/{detectionId}/review) and resolves to
 * the updated detection. The call is not repeatable: a detection that was already reviewed fails with status
 * 409, after which the caller loads the detection again to show who reviewed it. Reviewing does not acknowledge
 * the detection's alert; acknowledge it separately when its row offers that.
 */
export function useReviewDetection(): ActionState<[detectionId: number, decision: DetectionDecision, notes?: string], DiseaseDetection> {
  return useAction(
    (detectionId: number, decision: DetectionDecision, notes?: string) => reviewDetection(detectionId, { decision, notes }),
    { errorMessage: 'The review could not be saved. Try again.' },
  );
}

/**
 * Loads one detection on demand (GET /api/disease-detections/{detectionId}), for a view that holds a detection
 * it did not load through `useDetection` and must show its current state, for example after a 409 on review.
 */
export function useReloadDetection(): ActionState<[detectionId: number], DiseaseDetection> {
  return useAction(
    (detectionId: number) => getDetection(detectionId),
    { errorMessage: 'The screening result could not be loaded.' },
  );
}

/**
 * Runs the screening for a patient now (POST /api/disease-detections/patient/{patientSystemId}/run). Every
 * outcome, including "nothing found" and "nothing to screen", is a successful result; switch on `outcome`.
 * A provider that is switched off or failed is a failure with status 503 and code PROVIDER_UNAVAILABLE.
 */
export function useRunDetection(): ActionState<[patientSystemId: string], DetectionRunResult> {
  return useAction(
    (patientSystemId: string) => runDetection(patientSystemId),
    { errorMessage: 'The screening could not be run. Try again.' },
  );
}
