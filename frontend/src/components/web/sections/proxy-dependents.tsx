import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { type CaregiverDependent, useCaregiverDependents } from '@/hooks/use-caregiver-dependents';
import { onSignedOut } from '@/lib/auth/auth.events';

// The caregiver's linked patients as shown by the dashboard chrome (the sidebar's loved-ones list).
//
// Opening a section mounts a fresh copy of the dashboard, and without this every copy asked the server for
// the list again. Here the list is loaded once and reused while it is fresh. It is loaded again when it is
// older than DEPENDENTS_FRESH_MS at the next navigation, after a loved one was added, after a patient profile
// was open (name and access can be edited there), and on Retry.

/** How long a loaded list is shown without asking the server again. */
export const DEPENDENTS_FRESH_MS = 30_000;

export interface DependentsState {
  dependents: CaregiverDependent[];
  /** True only while there is no list to show yet; a refresh in the background keeps the current list on screen. */
  loading: boolean;
  error: string | null;
}

const INITIAL: DependentsState = { dependents: [], loading: true, error: null };

let state: DependentsState = INITIAL;
/** When the list on screen was loaded; 0 = never, or marked stale. */
let loadedAt = 0;
/** Bumped to ask the mounted loader for a new request. */
let request = 0;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach(l => l());
}
function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
function getState(): DependentsState {
  return state;
}
function getRequest(): number {
  return request;
}
function isFresh(): boolean {
  return loadedAt > 0 && Date.now() - loadedAt < DEPENDENTS_FRESH_MS;
}
function publish(next: DependentsState): void {
  if (next.dependents === state.dependents && next.loading === state.loading && next.error === state.error) return;
  state = next;
  emit();
}

// The next person to sign in must never see this list.
onSignedOut(() => {
  loadedAt = 0;
  publish(INITIAL);
});

/** Marks the list stale without loading it: the copy of the dashboard mounted by the next navigation loads it. */
export function markDependentsStale(): void {
  loadedAt = 0;
}

/** Marks the list stale and loads it again now (after a possible change to the linked patients, or to retry). */
export function refreshDependents(): void {
  loadedAt = 0;
  request += 1;
  emit();
}

/**
 * The caregiver's linked patients as last loaded. Needs `<DependentsLoader />` mounted (the caregiver shell
 * renders it), which does the loading.
 */
export function useDependents(): DependentsState & { refetch: () => void } {
  const current = useSyncExternalStore(subscribe, getState, getState);
  return useMemo(() => ({ ...current, refetch: refreshDependents }), [current]);
}

/** Runs one request through the shared hook and publishes its outcome. */
function DependentsRequest() {
  const { dependents, loading, error } = useCaregiverDependents();
  useEffect(() => {
    if (loading) {
      // First load (or a retry after a failure): spinner. Otherwise the current list stays up meanwhile.
      if (loadedAt === 0 && (state.error !== null || state.dependents.length === 0)) publish(INITIAL);
      return;
    }
    if (error) {
      loadedAt = 0;
      publish({ dependents: [], loading: false, error });
      return;
    }
    loadedAt = Date.now();
    publish({ dependents, loading: false, error: null });
  }, [dependents, loading, error]);
  return null;
}

/**
 * Mounted once by the caregiver shell. Loads the list when the copy it belongs to starts with a stale (or no)
 * list and whenever a refresh is asked for; renders nothing.
 */
export function DependentsLoader() {
  const current = useSyncExternalStore(subscribe, getRequest, getRequest);
  const [mountedAt] = useState(current);
  const [staleAtMount] = useState(() => !isFresh());
  if (!staleAtMount && current === mountedAt) return null;
  // A new key starts a new request.
  return <DependentsRequest key={current} />;
}
