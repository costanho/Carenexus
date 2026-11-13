import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  AiAlert,
  AiAlertFilters,
  DetectionFilters,
  DetectionRunResult,
  DiseaseDetection,
  ReviewDetectionRequest,
  UnacknowledgedAlertCount,
} from './types';

// One function per endpoint of the automated-screening API (contracts api/be-ai-core.md and
// api/be-ai-detection.md). Lists answer with the pagination envelope, newest first.

const ALERTS = '/api/ai-alerts';
const DETECTIONS = '/api/disease-detections';

function patientPath(patientSystemId: string): string {
  return encodeURIComponent(patientSystemId.trim());
}

// ─── Alerts ───────────────────────────────────────────────────────────────────

/**
 * GET /api/ai-alerts: the clinician inbox. Doctors get the alerts visible to them, administrators all alerts;
 * patients and caregivers are refused (403). Filters that are not set are not sent.
 */
export function listAlerts(filters: AiAlertFilters, page: number, size?: number): Promise<PageResponse<AiAlert>> {
  return fetchPage<AiAlert>(ALERTS, page, size, {
    acknowledged:    filters.acknowledged,
    priority:        filters.priority,
    type:            filters.type,
    patientSystemId: filters.patientSystemId,
  });
}

/** GET /api/ai-alerts/unacknowledged-count: the inbox badge. Doctors and administrators only. */
export async function getUnacknowledgedAlertCount(): Promise<UnacknowledgedAlertCount> {
  const { data } = await api.get<UnacknowledgedAlertCount>(`${ALERTS}/unacknowledged-count`);
  return data;
}

/**
 * POST /api/ai-alerts/{alertId}/acknowledge: answers 204 with no body. Doctors only. Repeating it is a no-op
 * that still succeeds.
 */
export async function acknowledgeAlert(alertId: number): Promise<void> {
  await api.post(`${ALERTS}/${alertId}/acknowledge`);
}

/**
 * GET /api/ai-alerts/patient/{patientSystemId}: one patient's alerts. A related doctor (or an administrator)
 * gets every alert; the patient and permitted caregivers get the non-diagnostic alerts and the alerts of
 * doctor-confirmed detections, without `suggestedAction`.
 */
export function listPatientAlerts(patientSystemId: string, page: number, size?: number): Promise<PageResponse<AiAlert>> {
  return fetchPage<AiAlert>(`${ALERTS}/patient/${patientPath(patientSystemId)}`, page, size);
}

// ─── Disease detections ───────────────────────────────────────────────────────

/**
 * GET /api/disease-detections/patient/{patientSystemId}: one patient's detections. Patients and caregivers get
 * confirmed detections only (the `reviewStatus` filter is ignored for them).
 */
export function listPatientDetections(
  patientSystemId: string,
  filters: DetectionFilters,
  page: number,
  size?: number,
): Promise<PageResponse<DiseaseDetection>> {
  return fetchPage<DiseaseDetection>(`${DETECTIONS}/patient/${patientPath(patientSystemId)}`, page, size, {
    reviewStatus: filters.reviewStatus,
  });
}

/** GET /api/disease-detections/{detectionId}. A pending or rejected detection answers 404 to patients and caregivers. */
export async function getDetection(detectionId: number): Promise<DiseaseDetection> {
  const { data } = await api.get<DiseaseDetection>(`${DETECTIONS}/${detectionId}`);
  return data;
}

/**
 * POST /api/disease-detections/{detectionId}/review: a related doctor confirms or rejects a pending detection
 * and gets the updated detection back. A detection that was already reviewed answers 409.
 */
export async function reviewDetection(detectionId: number, request: ReviewDetectionRequest): Promise<DiseaseDetection> {
  const notes = request.notes?.trim();
  const body: ReviewDetectionRequest = notes ? { decision: request.decision, notes } : { decision: request.decision };
  const { data } = await api.post<DiseaseDetection>(`${DETECTIONS}/${detectionId}/review`, body);
  return data;
}

/**
 * POST /api/disease-detections/patient/{patientSystemId}/run: a related doctor runs the screening now. Answers
 * 201 (a detection was stored) or 200 (already recorded, nothing found, nothing to screen) with the same shape;
 * switch on `outcome`. A provider that is switched off or failed answers 503 with code PROVIDER_UNAVAILABLE.
 */
export async function runDetection(patientSystemId: string): Promise<DetectionRunResult> {
  const { data } = await api.post<DetectionRunResult>(`${DETECTIONS}/patient/${patientPath(patientSystemId)}/run`);
  return data;
}
