import { useCallback, useEffect } from 'react';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  createCorrectionRequest, getCorrectionRequest, listAssignedCorrectionRequests, listPatientCorrectionRequests,
  resolveCorrectionRequest,
} from '@/lib/corrections/api';
import { notifyCorrectionsChanged, subscribeCorrectionsChanged } from '@/lib/corrections/events';
import type {
  CorrectionDecision, CorrectionRequest, CorrectionResourceType, CorrectionStatus,
} from '@/lib/corrections/types';
import { type ActionResult, type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// Hooks over the correction-request endpoints (API contract be-corrections). Lists reload when a request is
// filed or resolved anywhere on the screen (lib/corrections/events).

export interface CorrectionListOptions {
  /** One status, or null / absent for every status. */
  status?:   CorrectionStatus | null;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
  pageSize?: number;
}

/**
 * Every correction request about one patient, newest first. Callers: the patient, a caregiver whose grant gives
 * records access (a 403 is reported as `errorStatus`), and a doctor looking at one patient.
 */
export function usePatientCorrectionRequests(
  patientSystemId: string | null | undefined,
  options: CorrectionListOptions = {},
): PagedState<CorrectionRequest> {
  const patient = (patientSystemId ?? '').trim();
  const status = options.status ?? null;
  const state = usePaged<CorrectionRequest>(
    (page, size) => listPatientCorrectionRequests(patient, status, page, size),
    [patient, status],
    {
      enabled:      (options.enabled ?? true) && !!patient,
      pageSize:     options.pageSize,
      keyOf:        r => r.requestId,
      errorMessage: 'Correction requests could not be loaded.',
    },
  );

  const { refetch } = state;
  useEffect(
    () => subscribeCorrectionsChanged(changed => { if (!changed || changed === patient) refetch(); }),
    [patient, refetch],
  );
  return state;
}

/** The signed-in doctor's review queue (requests assigned to them), newest first. */
export function useAssignedCorrectionRequests(options: CorrectionListOptions = {}): PagedState<CorrectionRequest> {
  const status = options.status ?? null;
  const state = usePaged<CorrectionRequest>(
    (page, size) => listAssignedCorrectionRequests(status, page, size),
    [status],
    {
      enabled:      options.enabled ?? true,
      pageSize:     options.pageSize,
      keyOf:        r => r.requestId,
      errorMessage: 'Correction requests could not be loaded.',
    },
  );

  const { refetch } = state;
  useEffect(() => subscribeCorrectionsChanged(() => refetch()), [refetch]);
  return state;
}

/**
 * How many open requests wait for the signed-in doctor (`totalElements` of the queue asked for with one row).
 * Null until loaded or when the count could not be read; a tab badge then simply shows nothing.
 */
export function useOpenCorrectionCount(enabled = true): { count: number | null; refetch: () => void } {
  const { data, refetch } = useResource(
    () => listAssignedCorrectionRequests('OPEN', 0, 1).then(page => page.totalElements),
    [],
    { enabled },
  );
  useEffect(() => subscribeCorrectionsChanged(() => refetch()), [refetch]);
  return { count: data, refetch };
}

/** One request by id, for a notification link whose request is not on the loaded page. */
export function useCorrectionRequest(requestId: number | null, enabled = true): ResourceState<CorrectionRequest> {
  return useResource(
    () => getCorrectionRequest(requestId as number),
    [requestId],
    { enabled: enabled && requestId !== null, errorMessage: 'The correction request could not be loaded.' },
  );
}

export interface NewCorrectionRequest {
  patientSystemId: string;
  resourceType:    CorrectionResourceType;
  resourceId:      number;
  requestedText:   string;
}

export interface CreateCorrectionRequestAction {
  /** Files the request. Never rejects: check `result.ok`. A retry of a failed submit repeats the same key. */
  submit:  (request: NewCorrectionRequest) => Promise<ActionResult<CorrectionRequest>>;
  pending: boolean;
  error:   string | null;
  failure: ActionState<[NewCorrectionRequest], CorrectionRequest>['failure'];
  reset:   () => void;
  /** Call when the form is opened: clears the last error and starts a new Idempotency-Key. */
  begin:   () => void;
}

/**
 * Files a correction request with one Idempotency-Key per open form: the key is kept across retries and
 * replaced after a success. Mounted lists reload after a success, and after a 409 (an open request for the
 * record already exists, so the list on screen is out of date).
 */
export function useCreateCorrectionRequest(): CreateCorrectionRequestAction {
  const { current: currentKey, renew: renewKey } = useIdempotencyKey();
  const action = useAction(
    (request: NewCorrectionRequest) => createCorrectionRequest({
      patientSystemId: request.patientSystemId,
      resourceType:    request.resourceType,
      resourceId:      request.resourceId,
      requestedText:   request.requestedText.trim(),
    }, currentKey()),
    { errorMessage: 'The correction request could not be sent.' },
  );

  const { run } = action;
  const submit = useCallback(async (request: NewCorrectionRequest) => {
    const result = await run(request);
    if (result.ok) {
      renewKey();
      notifyCorrectionsChanged(request.patientSystemId);
    } else if (result.error?.status === 409 && result.error.code !== 'IDEMPOTENCY_IN_PROGRESS') {
      notifyCorrectionsChanged(request.patientSystemId);
    }
    return result;
  }, [run, renewKey]);

  const { reset } = action;
  const begin = useCallback(() => { renewKey(); reset(); }, [renewKey, reset]);

  return { submit, pending: action.pending, error: action.error, failure: action.failure, reset, begin };
}

export interface ResolveCorrectionRequestAction {
  /** Records the decision. Never rejects: check `result.ok`. */
  resolve: (request: CorrectionRequest, decision: CorrectionDecision, note: string) => Promise<ActionResult<CorrectionRequest>>;
  pending: boolean;
  error:   string | null;
  failure: ActionState<[CorrectionRequest, CorrectionDecision, string], CorrectionRequest>['failure'];
  reset:   () => void;
}

/**
 * Accepts or rejects an open request (the assigned doctor or an administrator). Mounted lists reload after a
 * success, and after a 409 (someone resolved it meanwhile).
 */
export function useResolveCorrectionRequest(): ResolveCorrectionRequestAction {
  const action = useAction(
    (request: CorrectionRequest, decision: CorrectionDecision, note: string) =>
      resolveCorrectionRequest(request.requestId, { decision, note: note.trim() }),
    { errorMessage: 'The decision could not be saved.' },
  );

  const { run } = action;
  const resolve = useCallback(async (request: CorrectionRequest, decision: CorrectionDecision, note: string) => {
    const result = await run(request, decision, note);
    if (result.ok || result.error?.status === 409) notifyCorrectionsChanged(request.patientSystemId);
    return result;
  }, [run]);

  return { resolve, pending: action.pending, error: action.error, failure: action.failure, reset: action.reset };
}
