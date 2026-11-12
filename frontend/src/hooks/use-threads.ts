import { useCallback, useEffect, useRef, useState } from 'react';
import { apiErrorMessage } from '@/hooks/use-appointments';
import { fetchThreads, isNetworkError, setThreadArchived } from '@/lib/communications/api';
import type { Thread } from '@/lib/communications/types';
import { usePolling } from './use-page-active';

const PAGE_SIZE = 20;
const LIST_POLL_MS = 15_000;

interface State {
  threads:     Thread[];
  loading:     boolean;
  refreshing:  boolean;
  error:       string | null;
  hasMore:     boolean;
  loadingMore: boolean;
  refetch:     () => Promise<void>;
  loadMore:    () => Promise<void>;
  /** Archive / unarchive for the current user; removes it from this list optimistically. */
  archive:     (threadId: number, archived: boolean) => Promise<void>;
  /** Patch a thread locally (e.g. unread count after reading). */
  patch:       (threadId: number, change: Partial<Thread>) => void;
  /** Patch a thread and move it to the top (newest activity first), e.g. after sending in it. */
  bump:        (threadId: number, change: Partial<Thread>) => void;
}

/** Conversation list for the inbox or archive, refreshed every 15 s while the page is active. */
export function useThreads(opts: { archived: boolean; patientSystemId?: string | null; enabled?: boolean }): State {
  const { archived, patientSystemId = null, enabled = true } = opts;
  const [threads,     setThreads]     = useState<Thread[]>([]);
  const [loading,     setLoading]     = useState(true);
  const [refreshing,  setRefreshing]  = useState(false);
  const [error,       setError]       = useState<string | null>(null);
  const [pagesLoaded, setPagesLoaded] = useState(1);
  const [totalPages,  setTotalPages]  = useState(1);
  const [loadingMore, setLoadingMore] = useState(false);
  const genRef = useRef(0);
  // The filter whose list is shown. A callback created for another filter (an old closure, e.g. one an
  // event handler captured before the user switched between Inbox and Archived) must never write into it.
  const filterKey    = `${archived}|${patientSystemId ?? ''}`;
  const filterKeyRef = useRef(filterKey);
  // Only the newest refresh may write: an older poll must not undo a fresher refetch.
  const seqRef = useRef(0);
  /** Pages currently in the list (mirrors `pagesLoaded`; read by refetch without changing its identity). */
  const pagesRef = useRef(1);

  const setPages = useCallback((n: number) => {
    pagesRef.current = n;
    setPagesLoaded(n);
  }, []);

  const load = useCallback(async () => {
    const gen = genRef.current;
    const seq = ++seqRef.current;
    const stale = () => filterKey !== filterKeyRef.current || gen !== genRef.current || seq !== seqRef.current;
    setRefreshing(true);
    try {
      // Re-fetch every loaded page and replace the list. Refreshing only the first page would lose
      // the conversation that new activity pushes across a page boundary (position 20 to 21).
      for (let attempt = 0; ; attempt++) {
        const count = pagesRef.current;
        const pages = await Promise.all(Array.from({ length: count }, (_, page) =>
          fetchThreads({ archived, patientSystemId, page, size: PAGE_SIZE })));
        if (stale()) return;
        // "Load more" added a page while these requests were running: fetch that one as well.
        if (pagesRef.current !== count && attempt < 2) continue;

        const seen = new Set<number>();
        const rows: Thread[] = [];
        for (const page of pages) {
          for (const t of page.content) {
            if (seen.has(t.threadId)) continue;
            seen.add(t.threadId);
            rows.push(t);
          }
        }
        // The pages are separate requests: a conversation that moved between them can be missing
        // from all of them. Fewer rows than the server reports means that happened, so ask again.
        const newest   = pages[0];
        const expected = Math.min(newest.totalElements ?? 0, count * PAGE_SIZE);
        if (rows.length < expected && attempt < 2) continue;

        const total = Math.max(1, newest.totalPages);
        // Still behind "Load more" after the retries: keep its rows (by id) until the next refresh.
        const behind = pagesRef.current > count;
        setThreads(prev => (behind ? [...rows, ...prev.filter(t => !seen.has(t.threadId))] : rows));
        setTotalPages(total);
        // Conversations left the list (archived, access removed): later "Load more" starts after the last full page.
        if (pagesRef.current > total) setPages(total);
        setError(null);
        return;
      }
    } catch (e) {
      if (stale()) return;
      setError(isNetworkError(e)
        ? 'Could not reach CareNexus. Check your connection.'
        : apiErrorMessage(e, 'Failed to load conversations.'));
    } finally {
      if (!stale()) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [archived, patientSystemId, filterKey, setPages]);

  const loadRef = useRef(load);

  // Reset when the filter changes.
  useEffect(() => {
    filterKeyRef.current = filterKey;
    loadRef.current = load;
    genRef.current += 1;
    setThreads([]);
    setLoading(true);
    setError(null);
    setPages(1);
    setTotalPages(1);
    if (enabled) load();
  }, [load, filterKey, enabled, setPages]);

  /**
   * Refreshes the list for the filter shown when it runs, not the one shown when the caller got it: a handler
   * that awaits a request (archive) and then refreshes must not bring the old filter's rows back.
   */
  const refetch = useCallback(() => loadRef.current(), []);

  usePolling(refetch, LIST_POLL_MS, enabled && !loading);

  const loadMore = useCallback(async () => {
    // Created for a filter that is no longer shown: its page would belong to the other list.
    if (filterKey !== filterKeyRef.current) return;
    const index = pagesRef.current;
    if (loadingMore || index >= totalPages) return;
    const gen = genRef.current;
    const stale = () => filterKey !== filterKeyRef.current || gen !== genRef.current;
    setLoadingMore(true);
    try {
      const page = await fetchThreads({ archived, patientSystemId, page: index, size: PAGE_SIZE });
      if (stale()) return;
      // A refresh shortened the list meanwhile, so this page no longer follows the last one shown.
      if (pagesRef.current !== index) return;
      setThreads(prev => {
        const ids = new Set(prev.map(t => t.threadId));
        return [...prev, ...page.content.filter(t => !ids.has(t.threadId))];
      });
      setPages(index + 1);
      setTotalPages(Math.max(1, page.totalPages));
    } catch (e) {
      if (stale()) return;
      setError(apiErrorMessage(e, 'Failed to load more conversations.'));
    } finally {
      setLoadingMore(false);
    }
  }, [archived, patientSystemId, filterKey, totalPages, loadingMore, setPages]);

  const archive = useCallback(async (threadId: number, value: boolean) => {
    const key = filterKeyRef.current;
    await setThreadArchived(threadId, value);
    // The user switched lists meanwhile: the new list was fetched for its own filter, so leave it as it is
    // (in the Archived list the conversation just archived belongs there).
    if (key !== filterKeyRef.current) return;
    setThreads(prev => prev.filter(t => t.threadId !== threadId));
  }, []);

  const patch = useCallback((threadId: number, change: Partial<Thread>) => {
    setThreads(prev => prev.map(t => (t.threadId === threadId ? { ...t, ...change } : t)));
  }, []);

  const bump = useCallback((threadId: number, change: Partial<Thread>) => {
    setThreads(prev => {
      const row = prev.find(t => t.threadId === threadId);
      if (!row) return prev;
      return [{ ...row, ...change }, ...prev.filter(t => t.threadId !== threadId)];
    });
  }, []);

  return {
    threads, loading, refreshing, error,
    hasMore: pagesLoaded < totalPages, loadingMore,
    refetch, loadMore, archive, patch, bump,
  };
}
