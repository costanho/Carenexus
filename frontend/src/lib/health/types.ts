// Health metrics and symptom logs, camelCase straight from the API (`/api/health-metrics`, `/api/symptom-logs`).
// Date-times are clinic wall-clock values ("YYYY-MM-DDTHH:mm:ss", no offset); dates are "YYYY-MM-DD".

/** Metric types in the canonical order the server uses for the latest readings. */
export const HEALTH_METRIC_TYPES = [
  'BP_SYSTOLIC', 'BP_DIASTOLIC', 'HEART_RATE', 'GLUCOSE', 'WEIGHT', 'HEIGHT', 'BMI', 'SPO2', 'TEMPERATURE',
  'RESPIRATORY_RATE', 'STEPS',
] as const;
export type HealthMetricType = typeof HEALTH_METRIC_TYPES[number];

/** Computed by the server when a reading is stored; the web never derives it from ranges. */
export type MetricSeverity = 'NORMAL' | 'ABNORMAL' | 'CRITICAL';

export type MetricSource = 'MANUAL' | 'WEARABLE' | 'DEVICE' | 'INTEGRATION';

export const SYMPTOM_SEVERITIES = ['MILD', 'MODERATE', 'SEVERE', 'CRITICAL'] as const;
export type SymptomSeverity = typeof SYMPTOM_SEVERITIES[number];

export type SymptomSource = 'PATIENT_REPORTED' | 'DOCTOR_ENTERED' | 'WEARABLE' | 'INTEGRATION';

/** One stored reading (`HealthMetricResponse`). */
export interface HealthMetric {
  metricId:           number;
  patientSystemId:    string;
  type:               HealthMetricType;
  /** The number of decimals is not significant: 120, 120.0 and 120.000 are the same reading. */
  value:              number;
  /** The ASCII unit of the type (mmHg, bpm, mmol/L, kg, cm, kg/m2, %, C, breaths/min, steps). */
  unit:               string;
  source:             MetricSource;
  deviceId:           string | null;
  /** Shared by every reading stored by one request; pairs systolic with diastolic. Null on legacy rows. */
  readingGroupId:     string | null;
  isAbnormal:         boolean;
  severity:           MetricSeverity;
  notes:              string | null;
  recordedAt:         string;
  recordedBySystemId: string | null;
  recordedByName:     string | null;
  /** For the signed-in user; the server checks again on delete. */
  canDelete:          boolean;
}

/** One reading of a `POST /api/health-metrics` request. */
export interface MetricReadingInput {
  type:        HealthMetricType;
  value:       number;
  /** Optional; when sent it must be the ASCII unit of the type. */
  unit?:       string;
  /** Clinic wall-clock "YYYY-MM-DDTHH:mm:ss"; omitted = the server's current clinic time. */
  recordedAt?: string;
  notes?:      string;
}

export interface RecordHealthMetricsRequest {
  /** Required for doctors and caregivers; a patient may omit it (defaults to the caller). */
  patientSystemId?: string;
  readings:         MetricReadingInput[];
}

export interface RecordHealthMetricsResponse {
  patientSystemId: string;
  readingGroupId:  string;
  metrics:         HealthMetric[];
}

/** BMI derived by the server from the latest weight and height; never stored or entered. */
export interface DerivedBmi {
  value:          number;
  unit:           string;
  severity:       MetricSeverity;
  isAbnormal:     boolean;
  weightMetricId: number;
  heightMetricId: number;
  recordedAt:     string;
}

export interface LatestHealthMetrics {
  patientSystemId: string;
  /** At most one reading per type, in canonical type order; empty when nothing was ever recorded. */
  metrics:         HealthMetric[];
  bmi:             DerivedBmi | null;
}

export interface TrendPoint {
  /** Clinic calendar day "YYYY-MM-DD". */
  date:     string;
  /** Mean of the day's readings; the value the chart plots. */
  avg:      number;
  min:      number;
  max:      number;
  count:    number;
  /** At least one reading of the day was outside the reference range. */
  abnormal: boolean;
}

export interface HealthMetricTrend {
  patientSystemId: string;
  type:            HealthMetricType;
  unit:            string;
  days:            number;
  from:            string;
  to:              string;
  /** Ascending by date. Days without a reading are absent. */
  points:          TrendPoint[];
}

export interface HealthMetricFilter {
  /** One or several types; empty or omitted = all types. */
  types?: readonly HealthMetricType[];
  /** Clinic days "YYYY-MM-DD", both inclusive. */
  from?:  string;
  to?:    string;
}

/** One symptom log (`SymptomLogResponse`). */
export interface SymptomLog {
  symptomId:        number;
  patientSystemId:  string;
  symptoms:         string[];
  severity:         SymptomSeverity;
  /** 0 = started today; null when not given. */
  durationDays:     number | null;
  notes:            string | null;
  source:           SymptomSource;
  loggedAt:         string;
  loggedBySystemId: string | null;
  loggedByName:     string | null;
  /** For the signed-in user: the author, within 24 hours. The server checks again on delete. */
  canDelete:        boolean;
}

export interface LogSymptomsRequest {
  /** Required for doctors and caregivers; a patient may omit it. */
  patientSystemId?: string;
  symptoms:         string[];
  severity:         SymptomSeverity;
  durationDays?:    number;
  notes?:           string;
}

export interface SymptomLogFilter {
  severity?: SymptomSeverity | '';
  from?:     string;
  to?:       string;
}
