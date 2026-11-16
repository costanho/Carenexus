import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage, mapPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  DerivedBmi, HealthMetric, HealthMetricFilter, HealthMetricTrend, HealthMetricType, LatestHealthMetrics,
  LogSymptomsRequest, MetricSeverity, RecordHealthMetricsRequest, RecordHealthMetricsResponse, SymptomLog,
  SymptomLogFilter, TrendPoint,
} from './types';

// Typed client for /api/health-metrics and /api/symptom-logs: one function per endpoint.
// Requests reject with the axios error; use `apiError` / `apiErrorMessage` to show it.

const METRICS = '/api/health-metrics';
const SYMPTOMS = '/api/symptom-logs';

const patientPath = (root: string, patientSystemId: string) => `${root}/patient/${encodeURIComponent(patientSystemId)}`;

// ─── Reading the answers ──────────────────────────────────────────────────────
// Numbers are read through Number() because a decimal may arrive as 120, 120.0 or "120.000".

function toNumber(value: unknown): number {
  const n = typeof value === 'string' ? Number(value) : value;
  return typeof n === 'number' && Number.isFinite(n) ? n : NaN;
}

function toSeverity(severity: unknown, isAbnormal: unknown): MetricSeverity {
  const s = typeof severity === 'string' ? severity.trim().toUpperCase() : '';
  if (s === 'NORMAL' || s === 'ABNORMAL' || s === 'CRITICAL') return s;
  return isAbnormal === true ? 'ABNORMAL' : 'NORMAL';
}

function toMetric(raw: HealthMetric): HealthMetric {
  const severity = toSeverity(raw.severity, raw.isAbnormal);
  return {
    ...raw,
    value:              toNumber(raw.value),
    severity,
    isAbnormal:         severity !== 'NORMAL',
    deviceId:           raw.deviceId ?? null,
    readingGroupId:     raw.readingGroupId ?? null,
    notes:              raw.notes ?? null,
    recordedBySystemId: raw.recordedBySystemId ?? null,
    recordedByName:     raw.recordedByName ?? null,
    canDelete:          raw.canDelete === true,
  };
}

function toBmi(raw: DerivedBmi | null | undefined): DerivedBmi | null {
  if (!raw) return null;
  const severity = toSeverity(raw.severity, raw.isAbnormal);
  return { ...raw, value: toNumber(raw.value), severity, isAbnormal: severity !== 'NORMAL' };
}

function toTrendPoint(raw: TrendPoint): TrendPoint {
  return {
    date:     raw.date,
    avg:      toNumber(raw.avg),
    min:      toNumber(raw.min),
    max:      toNumber(raw.max),
    count:    toNumber(raw.count),
    abnormal: raw.abnormal === true,
  };
}

function toSymptomLog(raw: SymptomLog): SymptomLog {
  return {
    ...raw,
    symptoms:         Array.isArray(raw.symptoms) ? raw.symptoms.filter(s => typeof s === 'string') : [],
    durationDays:     raw.durationDays ?? null,
    notes:            raw.notes ?? null,
    loggedBySystemId: raw.loggedBySystemId ?? null,
    loggedByName:     raw.loggedByName ?? null,
    canDelete:        raw.canDelete === true,
  };
}

// ─── Health metrics ───────────────────────────────────────────────────────────

/** POST /api/health-metrics: stores one measurement session (1 to 10 readings). 201, or 200 on an idempotent replay. */
export async function recordHealthMetrics(
  body: RecordHealthMetricsRequest,
  idempotencyKey?: string,
): Promise<RecordHealthMetricsResponse> {
  const { data } = await api.post<RecordHealthMetricsResponse>(
    METRICS, body, idempotencyKey ? withIdempotencyKey(idempotencyKey) : undefined,
  );
  return { ...data, metrics: (data.metrics ?? []).map(toMetric) };
}

/** GET /api/health-metrics/patient/{id}: paged history, newest first. */
export async function listHealthMetrics(
  patientSystemId: string,
  filter: HealthMetricFilter,
  page: number,
  size?: number,
): Promise<PageResponse<HealthMetric>> {
  const result = await fetchPage<HealthMetric>(patientPath(METRICS, patientSystemId), page, size, {
    type: filter.types && filter.types.length > 0 ? filter.types.join(',') : undefined,
    from: filter.from,
    to:   filter.to,
  });
  return mapPage(result, toMetric);
}

/** GET /api/health-metrics/patient/{id}/latest: the latest reading of each type plus the derived BMI. */
export async function getLatestHealthMetrics(patientSystemId: string): Promise<LatestHealthMetrics> {
  const { data } = await api.get<LatestHealthMetrics>(`${patientPath(METRICS, patientSystemId)}/latest`);
  return {
    patientSystemId: data.patientSystemId ?? patientSystemId,
    metrics:         (data.metrics ?? []).map(toMetric),
    bmi:             toBmi(data.bmi),
  };
}

/** GET /api/health-metrics/patient/{id}/trend: one point per clinic day that has a reading, over the last `days` days (1 to 366). */
export async function getHealthMetricTrend(
  patientSystemId: string,
  type: HealthMetricType,
  days: number,
): Promise<HealthMetricTrend> {
  const { data } = await api.get<HealthMetricTrend>(`${patientPath(METRICS, patientSystemId)}/trend`, { params: { type, days } });
  return { ...data, points: (data.points ?? []).map(toTrendPoint) };
}

/** DELETE /api/health-metrics/{metricId}: removes a manual reading (and its blood-pressure partner). 204. */
export async function deleteHealthMetric(metricId: number): Promise<void> {
  await api.delete(`${METRICS}/${metricId}`);
}

// ─── Symptom logs ─────────────────────────────────────────────────────────────

/** POST /api/symptom-logs. 201, or 200 on an idempotent replay. */
export async function logSymptoms(body: LogSymptomsRequest, idempotencyKey?: string): Promise<SymptomLog> {
  const { data } = await api.post<SymptomLog>(SYMPTOMS, body, idempotencyKey ? withIdempotencyKey(idempotencyKey) : undefined);
  return toSymptomLog(data);
}

/** GET /api/symptom-logs/patient/{id}: paged history, newest first. */
export async function listSymptomLogs(
  patientSystemId: string,
  filter: SymptomLogFilter,
  page: number,
  size?: number,
): Promise<PageResponse<SymptomLog>> {
  const result = await fetchPage<SymptomLog>(patientPath(SYMPTOMS, patientSystemId), page, size, {
    severity: filter.severity || undefined,
    from:     filter.from,
    to:       filter.to,
  });
  return mapPage(result, toSymptomLog);
}

/** GET /api/symptom-logs/{symptomId}. 404 when the log does not exist or was deleted. */
export async function getSymptomLog(symptomId: number): Promise<SymptomLog> {
  const { data } = await api.get<SymptomLog>(`${SYMPTOMS}/${symptomId}`);
  return toSymptomLog(data);
}

/** DELETE /api/symptom-logs/{symptomId}: the author only, within 24 hours. 204. */
export async function deleteSymptomLog(symptomId: number): Promise<void> {
  await api.delete(`${SYMPTOMS}/${symptomId}`);
}
