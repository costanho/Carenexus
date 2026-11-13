import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError, isNetworkError } from '@/lib/api/errors';
import { closeSupportThread, fetchSupportThreads } from '@/lib/support/api';
import type { SupportThread } from '@/lib/support/types';
import { usePolling } from './use-page-active';

const PAGE_SIZE = 20;
const LIST_POLL_MS = 15_000;

export interface SupportThreadsState {
  threads:     SupportThread[];
  /** True until the first page for the current filter arrives. */
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  /** HTTP status of the last failure (0 = no response), or null. 403 means the user is not an administrator. */
  errorStatus: number | null;
  hasMore:     boolean;
  loadingMore: boolean;
  /** Conversations on the server for this filter. */
  total:       number;
  refetch:     () => Promise<void>;
  loadMore:    () => Promise<void>;
  /** Closes a conversation. With the open filter the row leaves the list; otherwise it is updated in place. */
  close:       (threadId: number) => Promise<SupportThread>;
  /** Patch a row locally (e.g. `unread` after reading). */
  patch:       (threadId: number, change: Partial<SupportThread>) => void;
  /** Patch a row and move it to the top (newest activity first), e.g. after replying in it. */
  bump:        (threadId: number, change: Partial<SupportThread>) => void;
}

/**
 * Administrator inbox of support conversations, refreshed every 15 s while the page is active.
 * `open`: true lists only open conversations, false only closed ones, null both.
 */
export function useSupportThreads(opts: { open?: boolean | null; enabled?: boolean } = {}): SupportThreadsState {
  const { open = null, enabled = true } = opts;
  const [threads,     setThreads]     = useState<SupportThread[]>([]);
  const [loading,     setLoading]     = useState(enabled);
  const [refreshing,  setRefreshing]  = useState(false);
  const [error,       setError]       = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | null>(null);
  const [pagesLoaded, setPagesLoaded] = useState(1);
  const [totalPages,  setTotalPages]  = useState(1);
  const [total,       setTotal]       = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  /** Bumped when the filter changes: responses for an earlier filter are dropped. */
  const genRef = useRef(0);
  /** Only the newest refresh may write: an older poll must not undo a fresher refetch. */
  const seqRef = useRef(0);
  /** Pages currently in the list (mirrors `pagesLoaded`; read by refetch without changing its identity). */
  const pagesRef = useRef(1);

  const setPages = useCallback((n: number) => {
    pagesRef.current = n;
    setPagesLoaded(n);
  }, []);

  const refetch = useCallback(async () => {
    const gen = genRef.current;
    const seq = ++seqRef.current;
    const stale = () => gen !== genRef.current || seq !== seqRef.current;
    setRefreshing(true);
    try {
      // Every loaded page is fetched again and the list replaced: new activity moves rows across page borders.
      for (let attempt = 0; ; attempt++) {
        const count = pagesRef.current;
        const pages = await Promise.all(Array.from({ length: count }, (_, page) =>
          fetchSupportThreads({ open, page, size: PAGE_SIZE })));
        if (stale()) return;
        // "Load more" added a page while these requests were running: fetch that one as well.
        if (pagesRef.current !== count && attempt < 2) continue;

        const seen = new Set<number>();
        const rows: SupportThread[] = [];
        for (const page of pages) {
          for (const row of page.content) {
            if (seen.has(row.threadId)) continue;
            seen.add(row.threadId);
            rows.push(row);
          }
        }
        // The pages are separate requests: a row that moved between them can be missing from all of them.
        const newest   = pages[0];
        const expected = Math.min(newest.totalElements, count * PAGE_SIZE);
        if (rows.length < expected && attempt < 2) continue;

        const pagesOnServer = Math.max(1, newest.totalPages);
        const behind = pagesRef.current > count;
        setThreads(prev => (behind ? [...rows, ...prev.filter(r => !seen.has(r.threadId))] : rows));
        setTotalPages(pagesOnServer);
        setTotal(newest.totalElements);
        if (pagesRef.current > pagesOnServer) setPages(pagesOnServer);
        setError(null);
        setErrorStatus(null);
        return;
      }
    } catch (e) {
      if (stale()) return;
      const failure = apiError(e, 'Failed to load support conversations.');
      setError(isNetworkError(e) ? 'Could not reach CareNexus. Check your connection.' : failure.message);
      setErrorStatus(failure.status);
    } finally {
      if (gen === genRef.current && seq === seqRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [open, setPages]);

  // Reset when the filter changes.
  useEffect(() => {
    genRef.current += 1;
    setThreads([]);
    setLoading(enabled);
    setError(null);
    setErrorStatus(null);
    setPages(1);
    setTotalPages(1);
    setTotal(0);
    if (enabled) refetch();
  }, [refetch, enabled, setPages]);

  // A list the user may not read (403) is not asked for again every 15 s.
  usePolling(refetch, LIST_POLL_MS, enabled && !loading && errorStatus !== 403);

  const loadMore = useCallback(async () => {
    const index = pagesRef.current;
    if (loadingMore || index >= totalPages) return;
    const gen = genRef.current;
    setLoadingMore(true);
    try {
      const page = await fetchSupportThreads({ open, page: index, size: PAGE_SIZE });
      if (gen !== genRef.current) return;
      // A refresh shortened the list meanwhile, so this page no longer follows the last one shown.
      if (pagesRef.current !== index) return;
      setThreads(prev => {
        const ids = new Set(prev.map(r => r.threadId));
        return [...prev, ...page.content.filter(r => !ids.has(r.threadId))];
      });
      setPages(index + 1);
      setTotalPages(Math.max(1, page.totalPages));
      setTotal(page.totalElements);
    } catch (e) {
      if (gen !== genRef.current) return;
      setError(apiError(e, 'Failed to load more support conversations.').message);
    } finally {
      setLoadingMore(false);
    }
  }, [open, totalPages, loadingMore, setPages]);

  const close = useCallback(async (threadId: number) => {
    const gen = genRef.current;
    const closed = await closeSupportThread(threadId);
    if (gen === genRef.current) {
      setThreads(prev => (open === true
        ? prev.filter(r => r.threadId !== threadId)
        : prev.map(r => (r.threadId === threadId ? { ...r, ...closed } : r))));
    }
    return closed;
  }, [open]);

  const patch = useCallback((threadId: number, change: Partial<SupportThread>) => {
    setThreads(prev => prev.map(r => (r.threadId === threadId ? { ...r, ...change } : r)));
  }, []);

  const bump = useCallback((threadId: number, change: Partial<SupportThread>) => {
    setThreads(prev => {
      const row = prev.find(r => r.threadId === threadId);
      if (!row) return prev;
      return [{ ...row, ...change }, ...prev.filter(r => r.threadId !== threadId)];
    });
  }, []);

  return {
    threads, loading, refreshing, error, errorStatus,
    hasMore: pagesLoaded < totalPages, loadingMore, total,
    refetch, loadMore, close, patch, bump,
  };
}
