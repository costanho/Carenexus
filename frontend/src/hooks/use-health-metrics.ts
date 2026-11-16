import { useCallback, useRef, useState } from 'react';
import type { ApiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  deleteHealthMetric, getHealthMetricTrend, getLatestHealthMetrics, listHealthMetrics, recordHealthMetrics,
} from '@/lib/health/api';
import { notifyHealthChanged, useHealthChange } from '@/lib/health/events';
import type {
  HealthMetric, HealthMetricFilter, HealthMetricTrend, HealthMetricType, LatestHealthMetrics,
  RecordHealthMetricsRequest, RecordHealthMetricsResponse,
} from '@/lib/health/types';
import { type ActionResult, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Health metrics (vitals) of one patient: latest readings, paged history, daily trend, record and delete.
// Every read reloads by itself after a reading of the same patient is recorded or deleted anywhere on the page.
// A 403 is reported as `errorStatus === 403`; screens render the no-access state for it.

export interface HealthQueryOptions {
  /** When false nothing is requested. A missing patient id disables the query as well. Default true. */
  enabled?: boolean;
}

/** GET /api/health-metrics/patient/{id}/latest: the latest reading of each type and the derived BMI. */
export function useLatestHealthMetrics(
  patientSystemId: string | null | undefined,
  options: HealthQueryOptions = {},
): ResourceState<LatestHealthMetrics> {
  const id = patientSystemId ?? '';
  const state = useResource(
    () => getLatestHealthMetrics(id),
    [id],
    { enabled: !!id && (options.enabled ?? true), errorMessage: 'Could not load the latest readings.' },
  );
  useHealthChange('metrics', id, state.refetch);
  return state;
}

/** GET /api/health-metrics/patient/{id}: readings newest first, loaded page by page. */
export function useHealthMetricHistory(
  patientSystemId: string | null | undefined,
  filter: HealthMetricFilter = {},
  options: HealthQueryOptions & { pageSize?: number } = {},
): PagedState<HealthMetric> {
  const id = patientSystemId ?? '';
  const types = (filter.types ?? []).join(',');
  const from = filter.from ?? '';
  const to = filter.to ?? '';
  const state = usePaged<HealthMetric>(
    (page, size) => listHealthMetrics(id, { types: filter.types, from: from || undefined, to: to || undefined }, page, size),
    [id, types, from, to],
    {
      enabled:      !!id && (options.enabled ?? true),
      pageSize:     options.pageSize,
      keyOf:        metric => metric.metricId,
      errorMessage: 'Could not load the readings.',
    },
  );
  useHealthChange('metrics', id, state.refetch);
  return state;
}

/**
 * GET /api/health-metrics/patient/{id}/trend, once per type: one series per requested type, in the same order
 * (blood pressure is two types). Days without a reading are absent from a series.
 */
export function useHealthMetricTrend(
  patientSystemId: string | null | undefined,
  types: readonly HealthMetricType[],
  days: number,
  options: HealthQueryOptions = {},
): ResourceState<HealthMetricTrend[]> {
  const id = patientSystemId ?? '';
  const typesKey = types.join(',');
  const state = useResource(
    () => Promise.all(types.map(type => getHealthMetricTrend(id, type, days))),
    [id, typesKey, days],
    { enabled: !!id && types.length > 0 && (options.enabled ?? true), errorMessage: 'Could not load the trend.' },
  );
  useHealthChange('metrics', id, state.refetch);
  return state;
}

export interface RecordHealthMetricsState {
  /** Stores the readings. Never rejects: check `result.ok`. A second call while one is running is ignored. */
  record:  (body: RecordHealthMetricsRequest) => Promise<ActionResult<RecordHealthMetricsResponse>>;
  pending: boolean;
  error:   string | null;
  failure: ApiError | null;
  reset:   () => void;
}

/**
 * POST /api/health-metrics with an Idempotency-Key. The key is kept while the same readings are retried (so a
 * retry after a lost answer cannot store them twice) and replaced after a success or when the readings change.
 */
export function useRecordHealthMetrics(): RecordHealthMetricsState {
  const { current: currentKey, renew: renewKey } = useIdempotencyKey();
  const lastBody = useRef<string | null>(null);
  const action = useAction((body: RecordHealthMetricsRequest) => recordHealthMetrics(body, currentKey()), {
    errorMessage: 'Could not save the readings. Please try again.',
  });
  const { run } = action;

  const record = useCallback(async (body: RecordHealthMetricsRequest) => {
    const fingerprint = JSON.stringify(body);
    if (lastBody.current !== null && lastBody.current !== fingerprint) renewKey();
    lastBody.current = fingerprint;
    const result = await run(body);
    if (result.ok) {
      renewKey();
      lastBody.current = null;
      notifyHealthChanged('metrics', result.data.patientSystemId);
    }
    return result;
  }, [run, renewKey]);

  return { record, pending: action.pending, error: action.error, failure: action.failure, reset: action.reset };
}

export interface DeleteHealthMetricState {
  /** Removes a manual reading (a blood-pressure reading goes together with its partner). Never rejects. */
  remove:    (metric: Pick<HealthMetric, 'metricId' | 'patientSystemId'>) => Promise<ActionResult<void>>;
  pending:   boolean;
  /** The reading being removed, for a spinner on its row. */
  pendingId: number | null;
  error:     string | null;
  failure:   ApiError | null;
  reset:     () => void;
}

/** DELETE /api/health-metrics/{metricId}. A 404 means the reading is already gone, so the lists reload as well. */
export function useDeleteHealthMetric(): DeleteHealthMetricState {
  const [pendingId, setPendingId] = useState<number | null>(null);
  const action = useAction((metricId: number) => deleteHealthMetric(metricId), {
    errorMessage: 'Could not delete the reading. Please try again.',
  });
  const { run } = action;
  const busy = useRef(false);

  const remove = useCallback(async (metric: Pick<HealthMetric, 'metricId' | 'patientSystemId'>): Promise<ActionResult<void>> => {
    // One delete at a time; a press on another row meanwhile is ignored and keeps the first row's spinner.
    if (busy.current) return { ok: false, error: null };
    busy.current = true;
    setPendingId(metric.metricId);
    const result = await run(metric.metricId);
    busy.current = false;
    setPendingId(null);
    if (result.ok || result.error?.status === 404) notifyHealthChanged('metrics', metric.patientSystemId);
    return result;
  }, [run]);

  return { remove, pending: action.pending, pendingId, error: action.error, failure: action.failure, reset: action.reset };
}
