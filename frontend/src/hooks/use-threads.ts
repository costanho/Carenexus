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
  // Only the newest first-page request may write: an older poll must not undo a fresher refetch.
  const seqRef = useRef(0);

  const refetch = useCallback(async () => {
    const gen = genRef.current;
    const seq = ++seqRef.current;
    setRefreshing(true);
    try {
      const page = await fetchThreads({ archived, patientSystemId, page: 0, size: PAGE_SIZE });
      if (gen !== genRef.current || seq !== seqRef.current) return;
      const firstIds = new Set(page.content.map(t => t.threadId));
      setThreads(prev => {
        // Keep rows from later pages that are not in the fresh first page.
        const rest = prev.slice(PAGE_SIZE).filter(t => !firstIds.has(t.threadId));
        return [...page.content, ...rest];
      });
      setTotalPages(Math.max(1, page.totalPages));
      setError(null);
    } catch (e) {
      if (gen !== genRef.current || seq !== seqRef.current) return;
      setError(isNetworkError(e)
        ? 'Could not reach CareNexus. Check your connection.'
        : apiErrorMessage(e, 'Failed to load conversations.'));
    } finally {
      if (gen === genRef.current && seq === seqRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [archived, patientSystemId]);

  // Reset when the filter changes.
  useEffect(() => {
    genRef.current += 1;
    setThreads([]);
    setLoading(true);
    setError(null);
    setPagesLoaded(1);
    setTotalPages(1);
    if (enabled) refetch();
  }, [refetch, enabled]);

  usePolling(refetch, LIST_POLL_MS, enabled && !loading);

  const loadMore = useCallback(async () => {
    if (loadingMore || pagesLoaded >= totalPages) return;
    const gen = genRef.current;
    setLoadingMore(true);
    try {
      const page = await fetchThreads({ archived, patientSystemId, page: pagesLoaded, size: PAGE_SIZE });
      if (gen !== genRef.current) return;
      setThreads(prev => {
        const ids = new Set(prev.map(t => t.threadId));
        return [...prev, ...page.content.filter(t => !ids.has(t.threadId))];
      });
      setPagesLoaded(p => p + 1);
      setTotalPages(Math.max(1, page.totalPages));
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to load more conversations.'));
    } finally {
      setLoadingMore(false);
    }
  }, [archived, patientSystemId, pagesLoaded, totalPages, loadingMore]);

  const archive = useCallback(async (threadId: number, value: boolean) => {
    await setThreadArchived(threadId, value);
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
