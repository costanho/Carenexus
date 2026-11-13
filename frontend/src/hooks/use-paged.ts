import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { apiError } from '@/lib/api/errors';
import { DEFAULT_PAGE_SIZE } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';

// Loads a paged list page by page ("Load more") from an endpoint that answers with the pagination envelope
// (CONTRACTS 3.2 and 12.4). `PagedList` renders a `PagedState` directly.

export interface PagedOptions<T> {
  /** Rows per request, 1 to 100. Default 20. */
  pageSize?: number;
  /** When false nothing is requested and the list is empty and not loading. Default true. */
  enabled?: boolean;
  /** Shown when a request fails without a message from the server. */
  errorMessage?: string;
  /**
   * Row identity. When given, a row that shows up again on a later page (rows were added on the server between
   * two requests, shifting the pages) is not listed twice.
   */
  keyOf?: (item: T) => string | number;
}

export interface PagedState<T> {
  /** All rows loaded so far, in server order. */
  items:       T[];
  /** True until the first page for the current deps arrives. */
  loading:     boolean;
  /** True while "Load more" is fetching the next page. */
  loadingMore: boolean;
  /** True while `refetch` reloads a list that is already on screen. */
  refreshing:  boolean;
  /** A sentence to show, or null. When `items` is not empty the rows stay and the error is shown above or below them. */
  error:       string | null;
  /** HTTP status of the failure (0 = no HTTP response), or null. */
  errorStatus: number | null;
  /** True when the server has more rows than are loaded. */
  hasMore:     boolean;
  /** Number of rows on the server for this query. */
  total:       number;
  /** Fetches the next page. Does nothing while a request is running or when there is no next page. */
  loadMore:    () => void;
  /** Starts again from the first page, keeping the current rows on screen until the new page arrives. */
  refetch:     () => void;
  /** Replaces the loaded rows, for applying the result of a mutation (an edited or removed row) without a request. */
  setItems:    (next: T[] | ((prev: T[]) => T[])) => void;
}

interface Inner<T> {
  items:       T[];
  loading:     boolean;
  loadingMore: boolean;
  refreshing:  boolean;
  error:       string | null;
  errorStatus: number | null;
  nextPage:    number;
  totalPages:  number;
  total:       number;
  /** The query (deps, enabled, page size) these rows were loaded for (see `owns`). */
  query:       DependencyList;
}

/** What a query is identified by: the caller's deps, `enabled` and the page size. */
function sameQuery(a: DependencyList, b: DependencyList): boolean {
  return a.length === b.length && a.every((d, i) => Object.is(d, b[i]));
}

function initial<T>(loading: boolean, query: DependencyList): Inner<T> {
  return {
    items: [], loading, loadingMore: false, refreshing: false, error: null, errorStatus: null,
    nextPage: 0, totalPages: 0, total: 0, query,
  };
}

function appendUnique<T>(existing: T[], incoming: T[], keyOf?: (item: T) => string | number): T[] {
  if (!keyOf) return existing.concat(incoming);
  const seen = new Set(existing.map(keyOf));
  const out = existing.slice();
  for (const item of incoming) {
    const key = keyOf(item);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(item);
  }
  return out;
}

/**
 * ```ts
 * const invoices = usePaged((page, size) => listInvoices(patientId, { status }, page, size), [patientId, status]);
 * return <PagedList state={invoices} keyOf={i => i.invoiceId} renderItem={...} empty={{ icon: 'receipt-outline', title: 'No invoices yet' }} />;
 * ```
 * A change in `deps` (a filter, the selected patient) drops the rows and loads the first page again. The rows are
 * gone, and `loading` is true, from the very render in which `deps` change, so one query's rows are never shown
 * under another's filter and a list that has just been enabled shows the spinner, not a flash of the empty state.
 */
export function usePaged<T>(
  fetchPage: (page: number, size: number) => Promise<PageResponse<T>>,
  deps: DependencyList,
  options: PagedOptions<T> = {},
): PagedState<T> {
  const enabled  = options.enabled ?? true;
  const pageSize = Math.min(100, Math.max(1, Math.floor(options.pageSize ?? DEFAULT_PAGE_SIZE)));

  const query: DependencyList = [...deps, enabled, pageSize];
  const [state, setState] = useState<Inner<T>>(() => initial<T>(enabled, query));
  const [tick, setTick] = useState(0);

  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const keyOfRef = useRef(options.keyOf);
  keyOfRef.current = options.keyOf;
  const fallbackRef = useRef(options.errorMessage);
  fallbackRef.current = options.errorMessage;

  // Bumped whenever the query restarts; a response from an older generation is dropped.
  const generation = useRef(0);
  const busy = useRef(false);
  const depsTick = useRef<number | null>(null);
  const lastDeps = useRef<DependencyList | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;

  useEffect(() => {
    const gen = ++generation.current;
    const depsChanged = lastDeps.current === null || !sameQuery(lastDeps.current, deps);
    lastDeps.current = deps;

    if (!enabled) {
      depsTick.current = null;
      busy.current = false;
      setState(initial<T>(false, query));
      return;
    }

    const isRefetch = !depsChanged && depsTick.current !== null && depsTick.current !== tick;
    depsTick.current = tick;
    setState(prev => (isRefetch && prev.items.length > 0
      ? { ...prev, refreshing: true, loadingMore: false, query }
      : initial<T>(true, query)));

    busy.current = true;
    let cancelled = false;
    fetchRef.current(0, pageSize)
      .then(page => {
        if (cancelled || gen !== generation.current) return;
        setState({
          items: appendUnique<T>([], page.content ?? [], keyOfRef.current),
          loading: false, loadingMore: false, refreshing: false, error: null, errorStatus: null,
          nextPage: 1, totalPages: page.totalPages ?? 0, total: page.totalElements ?? 0, query,
        });
      })
      .catch(e => {
        if (cancelled || gen !== generation.current) return;
        const failure = apiError(e, fallbackRef.current);
        setState(prev => ({
          ...prev, loading: false, loadingMore: false, refreshing: false,
          error: failure.message, errorStatus: failure.status,
        }));
      })
      .finally(() => { if (gen === generation.current) busy.current = false; });
    return () => { cancelled = true; };
    // `deps` is the caller's dependency list; `fetchPage` is read through a ref.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, enabled, pageSize, tick]);

  const loadMore = useCallback(() => {
    const current = stateRef.current;
    if (busy.current || current.loading || current.nextPage >= current.totalPages) return;
    const gen = generation.current;
    const pageToLoad = current.nextPage;
    busy.current = true;
    setState(prev => ({ ...prev, loadingMore: true, error: null, errorStatus: null }));
    fetchRef.current(pageToLoad, pageSize)
      .then(page => {
        if (gen !== generation.current) return;
        setState(prev => ({
          ...prev,
          items: appendUnique(prev.items, page.content ?? [], keyOfRef.current),
          loadingMore: false,
          nextPage: pageToLoad + 1,
          totalPages: page.totalPages ?? prev.totalPages,
          total: page.totalElements ?? prev.total,
        }));
      })
      .catch(e => {
        if (gen !== generation.current) return;
        const failure = apiError(e, fallbackRef.current);
        // The rows stay; pressing "Load more" again retries the same page.
        setState(prev => ({ ...prev, loadingMore: false, error: failure.message, errorStatus: failure.status }));
      })
      .finally(() => { if (gen === generation.current) busy.current = false; });
  }, [pageSize]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  const setItems = useCallback((next: T[] | ((prev: T[]) => T[])) => {
    setState(prev => ({ ...prev, items: typeof next === 'function' ? next(prev.items) : next }));
  }, []);

  // The effect above runs after this render is committed. Until it has reset the state, rows that were loaded
  // for another query (or while disabled) are stale: report "loading" now instead of painting them, or the
  // empty state, for a frame.
  const owns = sameQuery(state.query, query);
  const shown = owns ? state : initial<T>(enabled, query);

  return {
    items:       shown.items,
    loading:     shown.loading,
    loadingMore: shown.loadingMore,
    refreshing:  shown.refreshing,
    error:       shown.error,
    errorStatus: shown.errorStatus,
    hasMore:     shown.nextPage < shown.totalPages,
    total:       shown.total,
    loadMore,
    refetch,
    setItems,
  };
}
