import { useCallback, useRef, useState } from 'react';
import type { ApiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import { deleteSymptomLog, getSymptomLog, listSymptomLogs, logSymptoms } from '@/lib/health/api';
import { notifyHealthChanged, useHealthChange } from '@/lib/health/events';
import type { LogSymptomsRequest, SymptomLog, SymptomLogFilter } from '@/lib/health/types';
import { type ActionResult, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Symptom logs of one patient: paged history, one log, log and delete. The reads reload by themselves after a
// log of the same patient is added or deleted anywhere on the page. A 403 is reported as `errorStatus === 403`.

export interface SymptomQueryOptions {
  /** When false nothing is requested. A missing patient or log id disables the query as well. Default true. */
  enabled?: boolean;
}

/** GET /api/symptom-logs/patient/{id}: logs newest first, loaded page by page. Deleted logs never appear. */
export function useSymptomLogs(
  patientSystemId: string | null | undefined,
  filter: SymptomLogFilter = {},
  options: SymptomQueryOptions & { pageSize?: number } = {},
): PagedState<SymptomLog> {
  const id = patientSystemId ?? '';
  const severity = filter.severity ?? '';
  const from = filter.from ?? '';
  const to = filter.to ?? '';
  const state = usePaged<SymptomLog>(
    (page, size) => listSymptomLogs(id, { severity, from: from || undefined, to: to || undefined }, page, size),
    [id, severity, from, to],
    {
      enabled:      !!id && (options.enabled ?? true),
      pageSize:     options.pageSize,
      keyOf:        log => log.symptomId,
      errorMessage: 'Could not load the symptom logs.',
    },
  );
  useHealthChange('symptoms', id, state.refetch);
  return state;
}

/** GET /api/symptom-logs/{symptomId}: one log, for example the one a notification links to. 404 once deleted. */
export function useSymptomLog(
  symptomId: number | null | undefined,
  options: SymptomQueryOptions = {},
): ResourceState<SymptomLog> {
  const id = typeof symptomId === 'number' && Number.isInteger(symptomId) && symptomId > 0 ? symptomId : 0;
  const state = useResource(
    () => getSymptomLog(id),
    [id],
    { enabled: id > 0 && (options.enabled ?? true), errorMessage: 'Could not load the symptom log.' },
  );
  useHealthChange('symptoms', state.data?.patientSystemId, state.refetch);
  return state;
}

export interface LogSymptomsState {
  /** Stores the log. Never rejects: check `result.ok`. A second call while one is running is ignored. */
  log:     (body: LogSymptomsRequest) => Promise<ActionResult<SymptomLog>>;
  pending: boolean;
  error:   string | null;
  failure: ApiError | null;
  reset:   () => void;
}

/**
 * POST /api/symptom-logs with an Idempotency-Key. The key is kept while the same entry is retried and replaced
 * after a success or when the entry changes.
 */
export function useLogSymptoms(): LogSymptomsState {
  const { current: currentKey, renew: renewKey } = useIdempotencyKey();
  const lastBody = useRef<string | null>(null);
  const action = useAction((body: LogSymptomsRequest) => logSymptoms(body, currentKey()), {
    errorMessage: 'Could not save the symptoms. Please try again.',
  });
  const { run } = action;

  const log = useCallback(async (body: LogSymptomsRequest) => {
    const fingerprint = JSON.stringify(body);
    if (lastBody.current !== null && lastBody.current !== fingerprint) renewKey();
    lastBody.current = fingerprint;
    const result = await run(body);
    if (result.ok) {
      renewKey();
      lastBody.current = null;
      notifyHealthChanged('symptoms', result.data.patientSystemId);
    }
    return result;
  }, [run, renewKey]);

  return { log, pending: action.pending, error: action.error, failure: action.failure, reset: action.reset };
}

export interface DeleteSymptomLogState {
  /** Deletes a log the signed-in user wrote in the last 24 hours. Never rejects. */
  remove:    (log: Pick<SymptomLog, 'symptomId' | 'patientSystemId'>) => Promise<ActionResult<void>>;
  pending:   boolean;
  /** The log being deleted, for a spinner on its row. */
  pendingId: number | null;
  error:     string | null;
  failure:   ApiError | null;
  reset:     () => void;
}

/** DELETE /api/symptom-logs/{symptomId}. A 404 means the log is already gone, so the lists reload as well. */
export function useDeleteSymptomLog(): DeleteSymptomLogState {
  const [pendingId, setPendingId] = useState<number | null>(null);
  const action = useAction((symptomId: number) => deleteSymptomLog(symptomId), {
    errorMessage: 'Could not delete the symptom log. Please try again.',
  });
  const { run } = action;
  const busy = useRef(false);

  const remove = useCallback(async (log: Pick<SymptomLog, 'symptomId' | 'patientSystemId'>): Promise<ActionResult<void>> => {
    // One delete at a time; a press on another row meanwhile is ignored and keeps the first row's spinner.
    if (busy.current) return { ok: false, error: null };
    busy.current = true;
    setPendingId(log.symptomId);
    const result = await run(log.symptomId);
    busy.current = false;
    setPendingId(null);
    if (result.ok || result.error?.status === 404) notifyHealthChanged('symptoms', log.patientSystemId);
    return result;
  }, [run]);

  return { remove, pending: action.pending, pendingId, error: action.error, failure: action.failure, reset: action.reset };
}
