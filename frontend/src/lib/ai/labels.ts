import type { IoniconsName, SectionParamsInput } from '@/lib/nav/sections';
import type {
  AiAlert,
  AiAlertPriority,
  AiAlertSourceType,
  AiAlertType,
  DetectionReviewStatus,
  DetectionRiskLevel,
  DiseaseDetection,
} from './types';

// Display text, badge tones and deep links for alerts and detections. Kept free of React so hooks, screens and
// panels share one wording.

/** The tone names of the shared StatusBadge. */
type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'critical';

// ─── The "not a diagnosis" label ──────────────────────────────────────────────

/** The fixed `disclaimer` of an alert response, used when a list is empty and no row carries it. */
export const ALERT_DISCLAIMER = 'This alert comes from automated rule-based screening. It is not a diagnosis.';

/** The fixed `disclaimer` of a detection response. */
export const DETECTION_DISCLAIMER = 'This result comes from automated rule-based screening. It is not a diagnosis.';

/** Wording for a view that mixes alerts and screening results (lists that may be empty). */
export const SCREENING_DISCLAIMER =
  'These alerts come from automated rule-based screening. They are not a diagnosis.';

// ─── Alert types ──────────────────────────────────────────────────────────────

export const ALERT_TYPES: readonly AiAlertType[] = [
  'CRITICAL_RESULT',
  'CRITICAL_VITALS',
  'DISEASE_RISK',
  'MEDICATION_INTERACTION',
  'ANOMALY_DETECTED',
  'MISSED_MEDICATION',
  'FOLLOW_UP_DUE',
];

const ALERT_TYPE_LABELS: Record<AiAlertType, string> = {
  CRITICAL_VITALS:        'Critical vitals',
  DISEASE_RISK:           'Possible health risk',
  MEDICATION_INTERACTION: 'Medication interaction',
  MISSED_MEDICATION:      'Missed medication',
  FOLLOW_UP_DUE:          'Follow-up due',
  ANOMALY_DETECTED:       'Unusual reading',
  CRITICAL_RESULT:        'Critical result',
};

const ALERT_TYPE_ICONS: Record<AiAlertType, IoniconsName> = {
  CRITICAL_VITALS:        'pulse-outline',
  DISEASE_RISK:           'shield-half-outline',
  MEDICATION_INTERACTION: 'git-compare-outline',
  MISSED_MEDICATION:      'medkit-outline',
  FOLLOW_UP_DUE:          'calendar-outline',
  ANOMALY_DETECTED:       'analytics-outline',
  CRITICAL_RESULT:        'flask-outline',
};

/** "MISSED_MEDICATION" -> "Missed medication"; a type this build does not know is shown in plain words. */
function humanise(value: string | null | undefined): string {
  const words = (value ?? '').trim().split(/[_\s-]+/).filter(Boolean).join(' ').toLowerCase();
  return words ? words.charAt(0).toUpperCase() + words.slice(1) : 'Alert';
}

export function isAlertType(value: string | null | undefined): value is AiAlertType {
  return !!value && Object.prototype.hasOwnProperty.call(ALERT_TYPE_LABELS, value);
}

export function alertTypeLabel(type: string | null | undefined): string {
  return isAlertType(type) ? ALERT_TYPE_LABELS[type] : humanise(type);
}

export function alertTypeIcon(type: string | null | undefined): IoniconsName {
  return isAlertType(type) ? ALERT_TYPE_ICONS[type] : 'alert-circle-outline';
}

// ─── Priority and risk level ──────────────────────────────────────────────────

export const ALERT_PRIORITIES: readonly AiAlertPriority[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

const LEVEL_WORD: Record<AiAlertPriority, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', CRITICAL: 'Critical' };

/** Tones of the contract: LOW neutral, MEDIUM info, HIGH warning, CRITICAL critical. */
const LEVEL_TONE: Record<AiAlertPriority, Tone> = { LOW: 'neutral', MEDIUM: 'info', HIGH: 'warning', CRITICAL: 'critical' };

function isLevel(value: string | null | undefined): value is AiAlertPriority {
  return !!value && Object.prototype.hasOwnProperty.call(LEVEL_WORD, value);
}

export function isAlertPriority(value: string | null | undefined): value is AiAlertPriority {
  return isLevel(value);
}

/** "High" for a filter option. */
export function priorityWord(priority: string | null | undefined): string {
  return isLevel(priority) ? LEVEL_WORD[priority] : humanise(priority);
}

/** "High priority" for a badge. */
export function priorityLabel(priority: string | null | undefined): string {
  return `${priorityWord(priority)} priority`;
}

export function priorityTone(priority: string | null | undefined): Tone {
  return isLevel(priority) ? LEVEL_TONE[priority] : 'neutral';
}

/** "High risk" for a badge on a detection or one of its conditions. */
export function riskLevelLabel(level: DetectionRiskLevel | string | null | undefined): string {
  return `${isLevel(level) ? LEVEL_WORD[level] : humanise(level)} risk`;
}

export function riskLevelTone(level: DetectionRiskLevel | string | null | undefined): Tone {
  return isLevel(level) ? LEVEL_TONE[level] : 'neutral';
}

// ─── Detection review status ──────────────────────────────────────────────────

const REVIEW_LABELS: Record<DetectionReviewStatus, string> = {
  PENDING_REVIEW: 'Pending doctor review',
  CONFIRMED:      'Confirmed by a doctor',
  REJECTED:       'Rejected by a doctor',
};

// A rejected screening result is a closed false alarm, not a failure, so it is neutral rather than red.
const REVIEW_TONES: Record<DetectionReviewStatus, Tone> = {
  PENDING_REVIEW: 'warning',
  CONFIRMED:      'success',
  REJECTED:       'neutral',
};

function isReviewStatus(value: string | null | undefined): value is DetectionReviewStatus {
  return !!value && Object.prototype.hasOwnProperty.call(REVIEW_LABELS, value);
}

export function reviewStatusLabel(status: string | null | undefined): string {
  return isReviewStatus(status) ? REVIEW_LABELS[status] : humanise(status);
}

export function reviewStatusTone(status: string | null | undefined): Tone {
  return isReviewStatus(status) ? REVIEW_TONES[status] : 'neutral';
}

// ─── Numbers ──────────────────────────────────────────────────────────────────

/** A 0..1 confidence as "75%"; null when the value is missing or not a number. */
export function confidencePercent(confidence: number | null | undefined): string | null {
  if (typeof confidence !== 'number' || !Number.isFinite(confidence)) return null;
  const clamped = Math.min(1, Math.max(0, confidence));
  return `${Math.round(clamped * 100)}%`;
}

// ─── What triggered a detection ───────────────────────────────────────────────

/** A sentence saying how the screening started; the doctor's name of a manual run is added by the caller. */
export function detectionTrigger(detection: Pick<DiseaseDetection, 'sourceType' | 'requestedBySystemId'>): string {
  switch (detection.sourceType) {
    case 'SYMPTOM_LOG':   return 'Run automatically after a symptom log was saved';
    case 'HEALTH_METRIC': return 'Run automatically after a reading outside the reference range was saved';
    default:              return detection.requestedBySystemId ? 'Run by a doctor' : 'Run automatically';
  }
}

// ─── Source links (doctor dashboard only) ─────────────────────────────────────

export interface AlertSourceLink {
  /** Section key of the doctor dashboard. */
  section: string;
  params:  SectionParamsInput;
  /** Visible text of the link. */
  label:   string;
}

const SOURCE_LABELS: Record<AiAlertSourceType, string> = {
  LAB_RESULT:          'Open lab result',
  IMAGING_RESULT:      'Open imaging result',
  HEALTH_METRIC:       'Open vitals',
  SYMPTOM_LOG:         'Open symptoms and vitals',
  PRESCRIPTION:        'Open prescription',
  MEDICATION_SCHEDULE: 'Open prescriptions',
  CONSULTATION:        'Open consultation',
};

/**
 * Where the record behind an alert lives in the doctor dashboard (contract api/be-ai-core.md 2.3), or null when
 * the alert has no source. The patient and caregiver views never show a source link.
 */
export function alertSourceLink(
  alert: Pick<AiAlert, 'sourceType' | 'sourceId' | 'patientSystemId'>,
): AlertSourceLink | null {
  const { sourceType, sourceId, patientSystemId } = alert;
  if (!sourceType || sourceId === null || sourceId === undefined) return null;
  const label = SOURCE_LABELS[sourceType];
  switch (sourceType) {
    case 'LAB_RESULT':          return { section: 'labs', params: { id: sourceId }, label };
    case 'IMAGING_RESULT':      return { section: 'imaging', params: { id: sourceId }, label };
    case 'HEALTH_METRIC':
    case 'SYMPTOM_LOG':         return { section: 'patients', params: { patient: patientSystemId, tab: 'vitals' }, label };
    case 'PRESCRIPTION':        return { section: 'prescriptions', params: { id: sourceId }, label };
    case 'MEDICATION_SCHEDULE': return { section: 'prescriptions', params: { patient: patientSystemId }, label };
    case 'CONSULTATION':        return { section: 'consultations', params: { id: sourceId }, label };
    default:                    return null;
  }
}
