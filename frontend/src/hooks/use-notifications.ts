import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { apiErrorMessage } from '@/hooks/use-appointments';
import {
  fetchNotifications,
  fetchUnreadCounts,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/lib/communications/api';
import type { AppNotification, UnreadCounts } from '@/lib/communications/types';
import { usePolling } from './use-page-active';

const COUNTS_POLL_MS = 30_000;

// ─── Shared unread-count store ────────────────────────────────────────────────
// The top-navbar bell and the sidebar badge both read this, so the app makes one
// request per 30 s instead of one per component.

const EMPTY: UnreadCounts = { unreadCount: 0, unreadMessageCount: 0 };
let counts: UnreadCounts = EMPTY;
let inFlight: Promise<void> | null = null;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach(l => l());
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function getSnapshot(): UnreadCounts {
  return counts;
}

/** Re-fetch unread counts now (e.g. after reading a thread or a notification). */
export function refreshUnreadCounts(): Promise<void> {
  if (inFlight) return inFlight;
  inFlight = fetchUnreadCounts()
    .then(data => {
      const next = {
        unreadCount:        Number(data?.unreadCount ?? 0),
        unreadMessageCount: Number(data?.unreadMessageCount ?? 0),
      };
      if (next.unreadCount !== counts.unreadCount || next.unreadMessageCount !== counts.unreadMessageCount) {
        counts = next;
        emit();
      }
    })
    .catch(() => { /* keep the last known counts */ })
    .finally(() => { inFlight = null; });
  return inFlight;
}

let pollers = 0;

/**
 * Unread notification counts, refreshed every 30 s while the page is visible and online.
 * Pass `poll=false` for read-only consumers when another mounted component already polls.
 */
export function useUnreadCounts(poll = true): UnreadCounts {
  const value = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);

  useEffect(() => {
    if (!poll) return;
    pollers += 1;
    if (pollers === 1) refreshUnreadCounts();
    return () => { pollers -= 1; };
  }, [poll]);

  // Every polling consumer schedules a tick; `refreshUnreadCounts` coalesces concurrent calls.
  usePolling(refreshUnreadCounts, COUNTS_POLL_MS, poll);
  return value;
}

// ─── Notification list ────────────────────────────────────────────────────────

interface ListState {
  notifications: AppNotification[];
  loading:       boolean;
  error:         string | null;
  hasMore:       boolean;
  loadingMore:   boolean;
  refetch:       () => Promise<void>;
  loadMore:      () => Promise<void>;
  markRead:      (notificationId: number) => Promise<void>;
  markAllRead:   () => Promise<void>;
}

const LIST_SIZE = 20;

export function useNotifications(opts: { enabled?: boolean; unreadOnly?: boolean } = {}): ListState {
  const { enabled = true, unreadOnly = false } = opts;
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [page,          setPage]          = useState(0);
  const [totalPages,    setTotalPages]    = useState(1);
  const [loadingMore,   setLoadingMore]   = useState(false);

  const refetch = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchNotifications({ unreadOnly, page: 0, size: LIST_SIZE });
      setNotifications(res.content ?? []);
      setPage(0);
      setTotalPages(Math.max(1, res.totalPages));
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to load notifications.'));
    } finally {
      setLoading(false);
    }
  }, [unreadOnly]);

  useEffect(() => {
    if (enabled) refetch();
  }, [enabled, refetch]);

  const loadMore = useCallback(async () => {
    if (loadingMore || page + 1 >= totalPages) return;
    setLoadingMore(true);
    try {
      const res = await fetchNotifications({ unreadOnly, page: page + 1, size: LIST_SIZE });
      setNotifications(prev => {
        const ids = new Set(prev.map(n => n.notificationId));
        return [...prev, ...(res.content ?? []).filter(n => !ids.has(n.notificationId))];
      });
      setPage(p => p + 1);
      setTotalPages(Math.max(1, res.totalPages));
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to load more notifications.'));
    } finally {
      setLoadingMore(false);
    }
  }, [unreadOnly, page, totalPages, loadingMore]);

  const markRead = useCallback(async (notificationId: number) => {
    setNotifications(prev => prev.map(n => (n.notificationId === notificationId
      ? { ...n, read: true, readAt: n.readAt ?? new Date().toISOString() } : n)));
    try {
      await markNotificationRead(notificationId);
    } finally {
      refreshUnreadCounts();
    }
  }, []);

  const markAllRead = useCallback(async () => {
    await markAllNotificationsRead();
    const now = new Date().toISOString();
    setNotifications(prev => prev.map(n => (n.read ? n : { ...n, read: true, readAt: now })));
    refreshUnreadCounts();
  }, []);

  return {
    notifications, loading, error,
    hasMore: page + 1 < totalPages, loadingMore,
    refetch, loadMore, markRead, markAllRead,
  };
}
