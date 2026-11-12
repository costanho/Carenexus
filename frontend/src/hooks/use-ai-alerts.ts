import { type DependencyList, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { acknowledgeAlert, getUnacknowledgedAlertCount, listAlerts, listPatientAlerts } from '@/lib/ai/api';
import type { AiAlert, AiAlertFilters } from '@/lib/ai/types';
import { apiError } from '@/lib/api/errors';
import type { PageResponse } from '@/lib/api/types';
import { onSignedOut } from '@/lib/auth/auth.events';
import { getUser } from '@/lib/auth/auth.storage';
import { usePolling } from './use-page-active';
import { type PagedState, usePaged } from './use-paged';

// Automated-screening alerts (contract api/be-ai-core.md): the clinician inbox with its filters, one patient's
// alerts, acknowledging an alert and the unacknowledged count behind the "Clinical Alerts" badge.

// ─── Unacknowledged count (shared store, polled every 30 s) ───────────────────
// The sidebar badge, the dashboard stat card and the inbox all read one store, so the app asks the server once
// per interval however many of them are mounted.

const COUNT_POLL_MS = 30_000;
/** A value younger than this is reused instead of asking again (several consumers tick close together). */
const COUNT_FRESH_MS = COUNT_POLL_MS - 2_000;

interface CountSnapshot {
  count:     number;
  /** True once the server has answered for the signed-in user. */
  loaded:    boolean;
  error:     string | null;
  /** The server refused the count (403: not a doctor or administrator), so it is not asked again. */
  forbidden: boolean;
}

const EMPTY_COUNT: CountSnapshot = { count: 0, loaded: false, error: null, forbidden: false };

let countState: CountSnapshot = EMPTY_COUNT;
let countInFlight: Promise<void> | null = null;
let countRequestedAt = 0;
/** Bumped on sign-out so a request started for the previous user cannot write its count. */
let countGeneration = 0;
const countListeners = new Set<() => void>();

function publishCount(next: CountSnapshot): void {
  const same = next.count === countState.count && next.loaded === countState.loaded
    && next.error === countState.error && next.forbidden === countState.forbidden;
  if (same) return;
  countState = next;
  countListeners.forEach(listener => listener());
}

function subscribeCount(listener: () => void): () => void {
  countListeners.add(listener);
  return () => { countListeners.delete(listener); };
}

function getCountSnapshot(): CountSnapshot {
  return countState;
}

// The next person to sign in must never see this count.
onSignedOut(() => {
  countGeneration += 1;
  countInFlight = null;
  countRequestedAt = 0;
  publishCount(EMPTY_COUNT);
});

/**
 * Asks the server for the unacknowledged count now (after an acknowledgement, a review or a manual run).
 * Concurrent calls share one request, and with `maxAgeMs` an answer requested less than that long ago is
 * reused. Never rejects: a failure keeps the last known count and records the error.
 */
export function refreshUnacknowledgedAlertCount(options: { maxAgeMs?: number } = {}): Promise<void> {
  if (countInFlight) return countInFlight;
  if (countState.forbidden) return Promise.resolve();
  const maxAge = options.maxAgeMs ?? 0;
  if (maxAge > 0 && countRequestedAt > 0 && Date.now() - countRequestedAt < maxAge) return Promise.resolve();
  const generation = countGeneration;
  countRequestedAt = Date.now();
  const request: Promise<void> = getUnacknowledgedAlertCount()
    .then(data => {
      if (generation !== countGeneration) return;
      const value = Number(data?.unacknowledgedCount ?? 0);
      publishCount({
        count: Number.isFinite(value) && value > 0 ? Math.floor(value) : 0,
        loaded: true, error: null, forbidden: false,
      });
    })
    .catch(e => {
      if (generation !== countGeneration) return;
      const failure = apiError(e, 'The number of unacknowledged alerts could not be loaded.');
      // 403 means this role has no inbox: stop asking until the next sign-in.
      publishCount(failure.status === 403
        ? { count: 0, loaded: true, error: null, forbidden: true }
        : { ...countState, error: failure.message });
    })
    .finally(() => { if (countInFlight === request) countInFlight = null; });
  countInFlight = request;
  return request;
}

function refreshCountIfStale(): Promise<void> {
  return refreshUnacknowledgedAlertCount({ maxAgeMs: COUNT_FRESH_MS });
}

export interface UnacknowledgedAlertCountState {
  /** Unacknowledged alerts visible to the signed-in doctor (all alerts for an administrator). 0 until loaded. */
  count:   number;
  /** False until the first answer arrives, so a stat card can show a placeholder instead of 0. */
  loaded:  boolean;
  /** Why the last refresh failed, or null. The last known count stays. */
  error:   string | null;
  refetch: () => void;
}

/**
 * The unacknowledged-alert count, refreshed every 30 s while the page is visible and online.
 *
 * The endpoint answers 403 for patients and caregivers, so pass `enabled = false` for those roles
 * (`useUnacknowledgedAlertCount(role === 'doctor' || role === 'admin')`): nothing is requested then and the
 * count is 0.
 */
export function useUnacknowledgedAlertCount(enabled = true): UnacknowledgedAlertCountState {
  const snapshot = useSyncExternalStore(subscribeCount, getCountSnapshot, getCountSnapshot);
  const active = enabled && !snapshot.forbidden;

  useEffect(() => {
    if (active) void refreshCountIfStale();
  }, [active]);
  usePolling(refreshCountIfStale, COUNT_POLL_MS, active);

  return useMemo<UnacknowledgedAlertCountState>(() => ({
    count:   enabled ? snapshot.count : 0,
    loaded:  enabled && snapshot.loaded,
    error:   enabled ? snapshot.error : null,
    refetch: () => { void refreshUnacknowledgedAlertCount(); },
  }), [enabled, snapshot]);
}

// ─── Alert lists ──────────────────────────────────────────────────────────────

export interface AlertListOptions {
  /** When false nothing is requested and the list is empty. Default true. */
  enabled?:  boolean;
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?: number;
}

export interface AlertListState extends PagedState<AiAlert> {
  /**
   * Acknowledges an alert (doctors only; offer it when `alert.canAcknowledge` is true). On success the row is
   * updated in place and the unacknowledged count is refreshed. Resolves to whether it succeeded; never rejects.
   */
  acknowledge:           (alert: AiAlert) => Promise<boolean>;
  /** True while this alert's acknowledgement is in flight. */
  isAcknowledging:       (alertId: number) => boolean;
  /** Why the last acknowledgement failed, or null. */
  acknowledgeError:      string | null;
  clearAcknowledgeError: () => void;
}

const keyOfAlert = (alert: AiAlert) => alert.alertId;

function useAlertList(
  fetchPage: (page: number, size: number) => Promise<PageResponse<AiAlert>>,
  deps: DependencyList,
  options: AlertListOptions,
): AlertListState {
  const paged = usePaged<AiAlert>(fetchPage, deps, {
    enabled:      options.enabled,
    pageSize:     options.pageSize,
    errorMessage: 'Alerts could not be loaded.',
    keyOf:        keyOfAlert,
  });
  const { setItems, refetch } = paged;

  const [pendingIds, setPendingIds] = useState<readonly number[]>([]);
  const [acknowledgeError, setAcknowledgeError] = useState<string | null>(null);
  const pending = useRef(new Set<number>());

  // Who acknowledged, for the row updated in place (the response of the call has no body).
  const me = useRef<string | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    getUser().then(user => { me.current = user?.system_id ?? null; }).catch(() => { me.current = null; });
    return () => { mounted.current = false; };
  }, []);

  const acknowledge = useCallback(async (alert: AiAlert): Promise<boolean> => {
    const id = alert.alertId;
    if (pending.current.has(id)) return false;
    pending.current.add(id);
    setPendingIds(Array.from(pending.current));
    setAcknowledgeError(null);
    try {
      await acknowledgeAlert(id);
      if (mounted.current) {
        setItems(rows => rows.map(row => (row.alertId === id
          ? {
              ...row,
              acknowledged:           true,
              canAcknowledge:         false,
              // A repeat keeps the first acknowledger and time; only fill what the row does not have yet.
              acknowledgedBySystemId: row.acknowledgedBySystemId ?? me.current,
              acknowledgedAt:         row.acknowledgedAt ?? new Date().toISOString(),
            }
          : row)));
      }
      void refreshUnacknowledgedAlertCount();
      return true;
    } catch (e) {
      const failure = apiError(e, 'The alert could not be acknowledged. Try again.');
      if (mounted.current) {
        setAcknowledgeError(failure.message);
        // The alert no longer exists (404) or is no longer visible to this doctor (403): show the current list.
        if (failure.status === 404 || failure.status === 403) refetch();
      }
      return false;
    } finally {
      pending.current.delete(id);
      if (mounted.current) setPendingIds(Array.from(pending.current));
    }
  }, [setItems, refetch]);

  const isAcknowledging = useCallback((alertId: number) => pendingIds.includes(alertId), [pendingIds]);
  const clearAcknowledgeError = useCallback(() => setAcknowledgeError(null), []);

  return { ...paged, acknowledge, isAcknowledging, acknowledgeError, clearAcknowledgeError };
}

/**
 * The clinician inbox (GET /api/ai-alerts), newest first, filtered by acknowledged state, priority, type and
 * patient. Doctors and administrators only: the server answers 403 for patients and caregivers, which
 * `PagedList` renders as its no-access state.
 *
 * ```ts
 * const alerts = useAiAlerts({ acknowledged: false, priority: 'CRITICAL' });
 * ```
 */
export function useAiAlerts(filters: AiAlertFilters = {}, options: AlertListOptions = {}): AlertListState {
  const { acknowledged, priority, type, patientSystemId } = filters;
  return useAlertList(
    (page, size) => listAlerts({ acknowledged, priority, type, patientSystemId }, page, size),
    [acknowledged, priority, type, patientSystemId],
    options,
  );
}

/**
 * One patient's alerts (GET /api/ai-alerts/patient/{patientSystemId}), newest first. A related doctor gets every
 * alert and may acknowledge; the patient and permitted caregivers get the non-diagnostic alerts and the alerts of
 * doctor-confirmed detections, read-only. Nothing is requested while `patientSystemId` is empty.
 */
export function usePatientAiAlerts(
  patientSystemId: string | null | undefined,
  options: AlertListOptions = {},
): AlertListState {
  const id = (patientSystemId ?? '').trim();
  return useAlertList(
    (page, size) => listPatientAlerts(id, page, size),
    [id],
    { ...options, enabled: (options.enabled ?? true) && id.length > 0 },
  );
}
