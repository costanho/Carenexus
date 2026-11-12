import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { apiErrorMessage, apiErrorStatus } from '@/lib/api/errors';
import { api } from '@/lib/auth/auth.interceptor';
import { getAccessToken } from '@/lib/auth/auth.storage';
import {
  fetchUnreadCounts,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/lib/communications/api';
import { onMessagingReset } from '@/lib/communications/session';
import type { AppNotification, NotificationType, PageResponse, UnreadCounts } from '@/lib/communications/types';
import { usePageActive } from './use-page-active';

const COUNTS_POLL_MS = 30_000;

// ─── Shared unread-count store ────────────────────────────────────────────────
// The top-navbar bell and the sidebar badge both read this. One timer serves every mounted consumer, so the
// app makes one request per 30 s however many copies of the bell exist (a section navigation mounts a new
// copy of the dashboard, and the previous one can stay mounted underneath for Back).

const EMPTY: UnreadCounts = { unreadCount: 0, unreadMessageCount: 0 };
let counts: UnreadCounts = EMPTY;
let inFlight: Promise<void> | null = null;
/** A refresh asked for while a request was under way; it runs as soon as that request settles. */
let queued: Promise<void> | null = null;
/** Bumped on sign-out so a request started for the previous user cannot write its counts. */
let generation = 0;
/** When the counts were last received; 0 = never, or signed out since. */
let fetchedAt = 0;
/** When the last request settled, successfully or not. The next poll is due one interval after it. */
let settledAt = 0;
/** Mounted consumers that asked for polling and whose page is visible and online. */
let pollers = 0;
let timer: ReturnType<typeof setTimeout> | null = null;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach(l => l());
}

function setCounts(next: UnreadCounts) {
  if (next.unreadCount === counts.unreadCount && next.unreadMessageCount === counts.unreadMessageCount) return;
  counts = next;
  emit();
}

function clearTimer() {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
}

/** Arms the single poll timer: one interval after the last request, straight away when that is already past. */
function schedule(): void {
  clearTimer();
  if (pollers === 0 || inFlight) return; // a request under way re-arms the timer when it settles
  const wait = Math.max(0, settledAt + COUNTS_POLL_MS - Date.now());
  timer = setTimeout(() => {
    timer = null;
    request();
  }, wait);
}

function request(): Promise<void> {
  if (inFlight) return inFlight;
  const gen = generation;
  // Signed out (no access token): there is nobody to ask for, and an unauthenticated request would only be
  // answered with a 401 that ends the session again. Skip the network; the timer checks again next interval.
  const current: Promise<void> = getAccessToken()
    .then(token => (token ? fetchUnreadCounts() : null))
    .then(data => {
      if (gen !== generation || data === null) return;
      fetchedAt = Date.now();
      setCounts({
        unreadCount:        Number(data?.unreadCount ?? 0),
        unreadMessageCount: Number(data?.unreadMessageCount ?? 0),
      });
    })
    .catch(() => { /* keep the last known counts */ })
    .finally(() => {
      if (gen !== generation) return; // signed out meanwhile: the reset already cleared the request state
      settledAt = Date.now();
      if (inFlight === current) inFlight = null;
      schedule();
    });
  inFlight = current;
  clearTimer();
  return current;
}

/** Forget the cached counts (sign-out), so the next user never sees the previous user's badge. */
export function resetUnreadCounts(): void {
  generation += 1;
  inFlight = null;
  queued = null;
  fetchedAt = 0;
  settledAt = 0;
  clearTimer();
  setCounts(EMPTY);
  // The reset also runs on every sign-in, while a polling consumer (the bell) may still be mounted. Such a
  // consumer does not re-arm the timer itself, so re-arm it here; with `settledAt` cleared it fires at once,
  // for the new session. After a sign-out the request finds no access token and goes no further than that
  // check. With nobody polling, nothing is started: the next consumer to mount, or to become active again,
  // arms the timer.
  if (pollers > 0) schedule();
}

onMessagingReset(resetUnreadCounts);

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

function getSnapshot(): UnreadCounts {
  return counts;
}

/** Re-fetch unread counts now (e.g. after reading a thread or a notification). */
export function refreshUnreadCounts(): Promise<void> {
  if (!inFlight) return request();
  // The request under way may have been answered before the change that prompted this call, so its numbers
  // can already be out of date: ask once more when it settles. Callers in the meantime share that follow-up.
  if (!queued) {
    const gen = generation;
    const followUp: Promise<void> = inFlight.then(() => {
      if (queued === followUp) queued = null;
      if (gen !== generation) return undefined;
      return request();
    });
    queued = followUp;
  }
  return queued;
}

/** Counts older than one poll interval (or never fetched) are refreshed; fresher ones are reused. */
function refreshUnreadCountsIfStale(): void {
  if (Date.now() - fetchedAt >= COUNTS_POLL_MS) request();
}

/**
 * Unread notification counts, refreshed every 30 s while the page is visible and online.
 * Pass `poll=false` for read-only consumers when another mounted component already polls.
 *
 * Mounting does not always fetch: the bell is remounted by every section navigation, so counts received in
 * the last 30 s are reused and the next request goes out when they turn 30 s old.
 */
export function useUnreadCounts(poll = true): UnreadCounts {
  const value = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const { active } = usePageActive();
  const paused = useRef(false);

  useEffect(() => {
    if (!poll) return;
    if (!active) {
      paused.current = true;
      return;
    }
    const resumed = paused.current;
    paused.current = false;
    pollers += 1;
    // Back from a hidden tab or from offline: catch up now when the counts are old, whatever the timer says
    // (a failed request while offline would otherwise hold the next one back for a full interval).
    if (resumed) refreshUnreadCountsIfStale();
    if (!timer && !inFlight) schedule();
    return () => {
      pollers = Math.max(0, pollers - 1);
      if (pollers === 0) clearTimer();
    };
  }, [poll, active]);

  return value;
}

// ─── Notification list ────────────────────────────────────────────────────────

export interface NotificationFilters {
  /** Only notifications that have not been read. */
  unreadOnly?: boolean;
  /** Only notifications of this type; null or undefined lists every type. */
  type?: NotificationType | null;
}

interface ListState {
  notifications: AppNotification[];
  loading:       boolean;
  error:         string | null;
  hasMore:       boolean;
  loadingMore:   boolean;
  /** A failed "load more"; the notifications already loaded stay on screen. Cleared by the next attempt. */
  loadMoreError: string | null;
  refetch:       () => Promise<void>;
  loadMore:      () => Promise<void>;
  markRead:      (notificationId: number) => Promise<void>;
  markAllRead:   () => Promise<void>;
}

const LIST_SIZE = 20;
const NO_ROWS: AppNotification[] = [];

/**
 * One page of the caller's notifications, newest first. The `type` parameter is sent only when a type is
 * selected, so the server filters (an unknown value is a 400; the union keeps that from happening).
 */
async function fetchNotificationPage(opts: {
  unreadOnly: boolean; type: NotificationType | null; page: number;
}): Promise<PageResponse<AppNotification>> {
  const params: Record<string, string | number | boolean> = {
    unreadOnly: opts.unreadOnly,
    page:       opts.page,
    size:       LIST_SIZE,
  };
  if (opts.type) params.type = opts.type;
  const { data } = await api.get<PageResponse<AppNotification>>('/api/notifications', { params });
  return data;
}

function pageCount(res: PageResponse<AppNotification>): number {
  return Math.max(1, Number(res?.totalPages ?? 1));
}

/** The server's order: newest first, the higher id first when two were created at the same instant. */
function newestFirst(a: AppNotification, b: AppNotification): number {
  const byTime = (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0);
  return byTime !== 0 ? byTime : b.notificationId - a.notificationId;
}

export function useNotifications(opts: { enabled?: boolean } & NotificationFilters = {}): ListState {
  const { enabled = true, unreadOnly = false, type = null } = opts;
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [page,          setPage]          = useState(0);
  const [totalPages,    setTotalPages]    = useState(1);
  const [loadingMore,   setLoadingMore]   = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);

  /** Bumped by every full load (and on unmount): an answer for an older load, or for other filters, is dropped. */
  const loadSeq = useRef(0);
  const filters = `${unreadOnly ? 'unread' : 'all'}|${type ?? ''}`;
  /** The filters the loaded rows belong to. Rows of other filters are never returned, not even for one frame. */
  const [rowsFilters, setRowsFilters] = useState(filters);
  const loadedFilters = useRef(filters);
  const latest = useRef(notifications);
  latest.current = notifications;
  /** Unread count seen when the list was last in step with the server; null until a load has finished. */
  const seenUnread = useRef<number | null>(null);

  useEffect(() => () => { loadSeq.current += 1; }, []);

  const refetch = useCallback(async () => {
    const seq = ++loadSeq.current;
    if (loadedFilters.current !== filters) {
      // Rows loaded for other filters must not stay on screen under the new ones.
      loadedFilters.current = filters;
      setRowsFilters(filters);
      setNotifications([]);
      setPage(0);
      setTotalPages(1);
    }
    seenUnread.current = null;
    setLoading(true);
    setLoadingMore(false);
    setLoadMoreError(null);
    try {
      const res = await fetchNotificationPage({ unreadOnly, type, page: 0 });
      if (seq !== loadSeq.current) return;
      setNotifications(res.content ?? []);
      setPage(0);
      setTotalPages(pageCount(res));
      setError(null);
      // The badge and the list were read at different moments; bring the badge up to date when it is old.
      refreshUnreadCountsIfStale();
    } catch (e) {
      if (seq !== loadSeq.current) return;
      setError(apiErrorMessage(e, 'Failed to load notifications.'));
    } finally {
      if (seq === loadSeq.current) setLoading(false);
    }
  }, [filters, unreadOnly, type]);

  useEffect(() => {
    if (enabled) refetch();
  }, [enabled, refetch]);

  /**
   * The page to ask for next. Under the unread filter, rows read here stay on screen but leave the server's
   * unread set, so every later row moves up; counting only the loaded rows that are still unread finds where
   * the server's set continues. Pages may then overlap (the de-duplication below absorbs that) but never
   * leave a gap. Under the other filters reading a row does not remove it, so the pages simply follow on.
   */
  const nextPage = unreadOnly
    ? Math.floor(notifications.filter(n => !n.read).length / LIST_SIZE)
    : page + 1;

  const loadMore = useCallback(async () => {
    if (loading || loadingMore || nextPage >= totalPages) return;
    const seq = loadSeq.current;
    const requested = nextPage;
    setLoadingMore(true);
    setLoadMoreError(null);
    try {
      const res = await fetchNotificationPage({ unreadOnly, type, page: requested });
      if (seq !== loadSeq.current) return;
      setNotifications(prev => {
        const ids = new Set(prev.map(n => n.notificationId));
        return [...prev, ...(res.content ?? []).filter(n => !ids.has(n.notificationId))].sort(newestFirst);
      });
      setPage(requested);
      setTotalPages(pageCount(res));
    } catch (e) {
      if (seq !== loadSeq.current) return;
      setLoadMoreError(apiErrorMessage(e, 'Failed to load more notifications.'));
    } finally {
      if (seq === loadSeq.current) setLoadingMore(false);
    }
  }, [unreadOnly, type, nextPage, totalPages, loading, loadingMore]);

  /**
   * Quietly re-reads the newest page and merges it into what is loaded (new notifications appear, rows
   * already on screen stay). A failure leaves the list as it is.
   */
  const refreshNewest = useCallback(async () => {
    const seq = loadSeq.current;
    try {
      const res = await fetchNotificationPage({ unreadOnly, type, page: 0 });
      if (seq !== loadSeq.current) return;
      const fresh = res.content ?? [];
      const freshIds = new Set(fresh.map(n => n.notificationId));
      setNotifications(prev => {
        // A row marked read here a moment ago may still be unread in an answer that was already on its way.
        const readHere = new Map(prev.filter(n => n.read).map(n => [n.notificationId, n.readAt] as const));
        const merged = fresh.map(n => (!n.read && readHere.has(n.notificationId)
          ? { ...n, read: true, readAt: readHere.get(n.notificationId) ?? n.readAt } : n));
        // Rows that are no longer on the newest page (older ones, or ones read here under the unread filter)
        // stay, each at its place in time.
        return [...merged, ...prev.filter(n => !freshIds.has(n.notificationId))].sort(newestFirst);
      });
      setTotalPages(pageCount(res));
    } catch {
      // nothing to show: the rows on screen are still valid
    }
  }, [unreadOnly, type]);

  // A rising unread count (the shared store polls it) means something new arrived while the list is open.
  const { unreadCount } = useUnreadCounts(false);
  useEffect(() => {
    if (!enabled || loading || error) return;
    const seen = seenUnread.current;
    seenUnread.current = unreadCount;
    if (seen !== null && unreadCount > seen) refreshNewest();
  }, [enabled, loading, error, unreadCount, refreshNewest]);

  const markRead = useCallback(async (notificationId: number) => {
    const wasUnread = latest.current.some(n => n.notificationId === notificationId && !n.read);
    setNotifications(prev => prev.map(n => (n.notificationId === notificationId && !n.read
      ? { ...n, read: true, readAt: n.readAt ?? new Date().toISOString() } : n)));
    try {
      await markNotificationRead(notificationId);
    } catch (e) {
      // Not saved: show it as unread again. A 404 means the row no longer exists, so there is nothing to undo.
      if (wasUnread && apiErrorStatus(e) !== 404) {
        setNotifications(prev => prev.map(n => (n.notificationId === notificationId
          ? { ...n, read: false, readAt: null } : n)));
      }
      throw e;
    } finally {
      refreshUnreadCounts();
    }
  }, []);

  const markAllRead = useCallback(async () => {
    await markAllNotificationsRead();
    // Mark-all covers every type, so both counts are zero now; the request below confirms it.
    setCounts(EMPTY);
    if (unreadOnly) {
      // Nothing is unread any more, so the unread filter has nothing left to show.
      setNotifications([]);
      setPage(0);
      setTotalPages(1);
    } else {
      const now = new Date().toISOString();
      setNotifications(prev => prev.map(n => (n.read ? n : { ...n, read: true, readAt: now })));
    }
    refreshUnreadCounts();
  }, [unreadOnly]);

  // The filters changed in this render and the load for them starts in the effect: show that load, not old rows.
  if (rowsFilters !== filters) {
    return {
      notifications: NO_ROWS, loading: enabled, error: null,
      hasMore: false, loadingMore: false, loadMoreError: null,
      refetch, loadMore, markRead, markAllRead,
    };
  }
  return {
    notifications, loading, error,
    hasMore: nextPage < totalPages, loadingMore, loadMoreError,
    refetch, loadMore, markRead, markAllRead,
  };
}
