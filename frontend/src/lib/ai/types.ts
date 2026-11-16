// Types of the automated-screening API: the alert inbox (`/api/ai-alerts`, contract api/be-ai-core.md) and the
// disease detections behind DISEASE_RISK alerts (`/api/disease-detections`, contract api/be-ai-detection.md).
// Field names are the camelCase JSON of the backend DTOs. Every time field is an ISO-8601 UTC instant
// ("2026-11-10T07:42:11Z"), never a clinic wall-clock string.

// ─── Alerts ───────────────────────────────────────────────────────────────────

export type AiAlertType =
  | 'CRITICAL_VITALS'
  | 'DISEASE_RISK'
  | 'MEDICATION_INTERACTION'
  | 'MISSED_MEDICATION'
  | 'FOLLOW_UP_DUE'
  | 'ANOMALY_DETECTED'
  | 'CRITICAL_RESULT';

export type AiAlertPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

/** The record that triggered an alert; `sourceId` is that record's id. */
export type AiAlertSourceType =
  | 'LAB_RESULT'
  | 'IMAGING_RESULT'
  | 'HEALTH_METRIC'
  | 'SYMPTOM_LOG'
  | 'PRESCRIPTION'
  | 'MEDICATION_SCHEDULE'
  | 'CONSULTATION';

export type DetectionReviewStatus = 'PENDING_REVIEW' | 'CONFIRMED' | 'REJECTED';

export interface AiAlert {
  alertId:                number;
  patientSystemId:        string;
  patientName:            string;
  /** The doctor the alert is addressed to; null = every doctor related to the patient. */
  doctorSystemId:         string | null;
  /** Set when the alert came from a detection (always for DISEASE_RISK). */
  detectionId:            number | null;
  detectionReviewStatus:  DetectionReviewStatus | null;
  alertType:              AiAlertType;
  priority:               AiAlertPriority;
  title:                  string;
  message:                string;
  actionRequired:         boolean;
  /** Clinician-directed; always null in the patient and caregiver views. */
  suggestedAction:        string | null;
  /** Both set or both null. */
  sourceType:             AiAlertSourceType | null;
  sourceId:               number | null;
  acknowledged:           boolean;
  acknowledgedBySystemId: string | null;
  /** ISO instant. */
  acknowledgedAt:         string | null;
  /** ISO instant. */
  createdAt:              string;
  /** True only for a doctor who may acknowledge this alert while it is unacknowledged. */
  canAcknowledge:         boolean;
  disclaimer:             string;
}

/** Filters of the clinician inbox. A filter that is not set is left out of the request. */
export interface AiAlertFilters {
  acknowledged?:    boolean;
  priority?:        AiAlertPriority;
  type?:            AiAlertType;
  patientSystemId?: string;
}

export interface UnacknowledgedAlertCount {
  unacknowledgedCount: number;
  disclaimer:          string;
}

// ─── Disease detections ───────────────────────────────────────────────────────

export type DetectionRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type DetectionSourceType = 'SYMPTOM_LOG' | 'HEALTH_METRIC';
export type DetectionDecision = 'CONFIRM' | 'REJECT';
export type DetectionRunOutcome = 'DETECTION_CREATED' | 'DETECTION_EXISTS' | 'NO_FINDING' | 'NO_DATA';

export interface DetectedCondition {
  /** Stable identifier of the finding. Display `name`; never switch on the code. */
  code:       string;
  name:       string;
  riskLevel:  DetectionRiskLevel;
  /** 0..1 */
  confidence: number;
  evidence:   string[];
}

export interface DiseaseDetection {
  detectionId:         number;
  patientSystemId:     string;
  patientName:         string;
  /** What triggered the screening; null when a doctor ran it by hand. */
  sourceType:          DetectionSourceType | null;
  sourceId:            number | null;
  /** The doctor who ran the screening by hand; null for automatic screenings. */
  requestedBySystemId: string | null;
  modelVersion:        string;
  riskLevel:           DetectionRiskLevel;
  /** 0..1, the confidence of the primary condition. */
  confidenceScore:     number | null;
  /** The first entry is the primary condition. */
  detectedConditions:  DetectedCondition[];
  /** Clinician-directed; always [] in the patient and caregiver views. */
  recommendations:     string[];
  reviewStatus:        DetectionReviewStatus;
  /** Always null in the patient and caregiver views. */
  reviewNotes:         string | null;
  reviewedBySystemId:  string | null;
  /** ISO instant. */
  reviewedAt:          string | null;
  /** ISO instant. */
  createdAt:           string;
  /** True only for a doctor related to the patient while the detection is pending review. */
  canReview:           boolean;
  disclaimer:          string;
}

export interface DetectionFilters {
  /** Honoured for doctors and administrators; patients and caregivers always get confirmed detections. */
  reviewStatus?: DetectionReviewStatus;
}

export interface ReviewDetectionRequest {
  decision: DetectionDecision;
  /** At most 2000 characters; left out when empty. */
  notes?:   string;
}

export interface DetectionRunResult {
  outcome:      DetectionRunOutcome;
  /** The fixed sentence for the outcome, shown as received. */
  message:      string;
  /** The new or already stored detection; null for NO_FINDING and NO_DATA. */
  detection:    DiseaseDetection | null;
  modelVersion: string;
  /** ISO instant. */
  assessedAt:   string;
  disclaimer:   string;
}
