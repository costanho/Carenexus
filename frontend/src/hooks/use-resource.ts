import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { apiError } from '@/lib/api/errors';

// Loads one value (a record, a small list, a summary) and exposes the four states every screen renders:
// loading, data, error with retry, and "not asked" when disabled (CONTRACTS 12.4).

export interface ResourceOptions {
  /** When false nothing is requested and the state is `{ data: null, loading: false }`. Default true. */
  enabled?: boolean;
  /** Shown when the request fails without a message from the server. */
  errorMessage?: string;
}

export interface ResourceState<T> {
  /** The loaded value; null until the first load for the current deps succeeds. */
  data:        T | null;
  /** True until the first result for the current deps arrives. Stays false during `refetch` (see `refreshing`). */
  loading:     boolean;
  /** True while `refetch` reloads data that is already on screen. */
  refreshing:  boolean;
  /** A sentence to show, or null. After a failed refetch the previous `data` is kept alongside the error. */
  error:       string | null;
  /** HTTP status of the failure (0 = no HTTP response), or null. 403 and 404 usually render NoAccessView. */
  errorStatus: number | null;
  /** Loads again, keeping the current data on screen until the new value arrives. */
  refetch:     () => void;
  /** Replaces the cached value, for applying the result of a mutation without another request. */
  setData:     (next: T | null | ((prev: T | null) => T | null)) => void;
}

interface Inner<T> {
  data:        T | null;
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  errorStatus: number | null;
  /** The deps and `enabled` this state was loaded for (see `owns`). */
  deps:        DependencyList;
  enabled:     boolean;
}

function sameDeps(a: DependencyList, b: DependencyList): boolean {
  return a.length === b.length && a.every((d, i) => Object.is(d, b[i]));
}

/**
 * `const { data, loading, error, refetch } = useResource(() => getInvoice(id), [id], { enabled: !!id });`
 *
 * `load` runs when the component mounts, when a value in `deps` changes and on `refetch`. From the very render
 * in which `deps` or `enabled` change, the hook reports `{ data: null, loading: enabled }`: one patient's data is
 * never shown under another's name, and a resource that has just been enabled shows the spinner, not a flash of
 * the empty state. A slower earlier request never overwrites a newer one.
 */
export function useResource<T>(
  load: () => Promise<T>,
  deps: DependencyList,
  options: ResourceOptions = {},
): ResourceState<T> {
  const enabled = options.enabled ?? true;
  const [state, setState] = useState<Inner<T>>(() => ({
    data: null, loading: enabled, refreshing: false, error: null, errorStatus: null, deps, enabled,
  }));
  const [tick, setTick] = useState(0);

  // Always call the latest closure without making it a dependency.
  const loadRef = useRef(load);
  loadRef.current = load;
  const fallbackRef = useRef(options.errorMessage);
  fallbackRef.current = options.errorMessage;

  const generation = useRef(0);
  // The tick the current deps were first loaded with: a different tick with the same deps is a refetch.
  const depsTick = useRef<number | null>(null);
  const lastDeps = useRef<DependencyList | null>(null);

  useEffect(() => {
    const gen = ++generation.current;
    const depsChanged = lastDeps.current === null || !sameDeps(lastDeps.current, deps);
    lastDeps.current = deps;

    if (!enabled) {
      depsTick.current = null;
      setState({ data: null, loading: false, refreshing: false, error: null, errorStatus: null, deps, enabled });
      return;
    }

    const isRefetch = !depsChanged && depsTick.current !== null && depsTick.current !== tick;
    depsTick.current = tick;
    setState(prev => (isRefetch && prev.data !== null
      ? { ...prev, refreshing: true, deps, enabled }
      : { data: null, loading: true, refreshing: false, error: null, errorStatus: null, deps, enabled }));

    let cancelled = false;
    loadRef.current()
      .then(data => {
        if (cancelled || gen !== generation.current) return;
        setState({ data, loading: false, refreshing: false, error: null, errorStatus: null, deps, enabled });
      })
      .catch(e => {
        if (cancelled || gen !== generation.current) return;
        const failure = apiError(e, fallbackRef.current);
        setState(prev => ({
          ...prev, loading: false, refreshing: false, error: failure.message, errorStatus: failure.status,
        }));
      });
    return () => { cancelled = true; };
    // `deps` is the caller's dependency list; `load` is read through a ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, enabled, tick]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  const setData = useCallback((next: T | null | ((prev: T | null) => T | null)) => {
    setState(prev => ({
      ...prev,
      data: typeof next === 'function' ? (next as (p: T | null) => T | null)(prev.data) : next,
    }));
  }, []);

  // The effect above runs after this render is committed. Until it has reset the state, a state that was loaded
  // for other deps (or while disabled) is stale: report "loading" now instead of painting it for a frame.
  const owns = state.enabled === enabled && sameDeps(state.deps, deps);
  if (!owns) {
    return { data: null, loading: enabled, refreshing: false, error: null, errorStatus: null, refetch, setData };
  }
  return {
    data:        state.data,
    loading:     state.loading,
    refreshing:  state.refreshing,
    error:       state.error,
    errorStatus: state.errorStatus,
    refetch,
    setData,
  };
}
