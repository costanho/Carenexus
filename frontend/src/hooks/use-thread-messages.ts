import { useCallback, useEffect, useRef, useState } from 'react';
import { apiErrorMessage } from '@/hooks/use-appointments';
import {
  fetchMessages,
  httpStatus,
  isNetworkError,
  markThreadRead,
  newClientId,
  sendMessage,
} from '@/lib/communications/api';
import {
  type LocalMessage, NOT_SENT_TEXT, loadOutbox, markInFlight, memoryOutboxKey, outboxSession, reviveOutbox,
  subscribeOutbox, updateOutbox,
} from '@/lib/communications/outbox';
import { OFFLINE_SEND_TEXT, type Message } from '@/lib/communications/types';
import { usePageActive, usePolling } from './use-page-active';

export type { LocalMessage, LocalMessageStatus } from '@/lib/communications/outbox';

const PAGE_LIMIT = 30;
const POLL_LIMIT = 100;
const THREAD_POLL_MS = 5_000;

interface State {
  messages:      Message[];
  pending:       LocalMessage[];
  loading:       boolean;
  error:         string | null;
  /** 403: the user was removed or lost permission. */
  forbidden:     boolean;
  notFound:      boolean;
  /** The last poll failed (server unreachable or 5xx) while the browser is online; retrying. */
  stale:         boolean;
  hasOlder:      boolean;
  loadingOlder:  boolean;
  /** Increments each time newer messages arrive (initial load, poll or own send). */
  newestVersion: number;
  loadOlder:     () => Promise<void>;
  reload:        () => void;
  send:          (body: string, urgent: boolean) => void;
  retry:         (clientMessageId: string) => void;
  discard:       (clientMessageId: string) => void;
}

function mergeMessages(prev: Message[], incoming: Message[]): Message[] {
  if (incoming.length === 0) return prev;
  const byId = new Map<number, Message>();
  for (const m of prev) byId.set(m.messageId, m);
  for (const m of incoming) byId.set(m.messageId, m);
  return Array.from(byId.values()).sort((a, b) => a.messageId - b.messageId);
}

/**
 * Message history for one open (visible) thread.
 * Polls `after={cursor}` every 5 s while the page is visible and online, and marks new messages read.
 * The cursor is the highest message id received from a list or poll response. A message confirmed by
 * its own POST is shown straight away but never moves the cursor (or the read position), because
 * someone else's message with a lower id may not have been fetched yet; a poll follows every send.
 * Unconfirmed sends live in the per-thread outbox (`outboxKey`, see lib/communications/outbox.ts), so
 * they survive switching threads; without a key they are kept in memory for this page only.
 */
export function useThreadMessages(
  threadId: number | null,
  opts: {
    outboxKey?: string | null;
    onRead?:    (threadId: number, unreadCount: number) => void;
    /** A send was confirmed by the server (used to refresh the conversation list). */
    onSent?:    (threadId: number, message: Message) => void;
  } = {},
): State {
  const { online } = usePageActive();
  const boxKey = threadId == null ? null : (opts.outboxKey ?? memoryOutboxKey(threadId));
  const [messages,      setMessages]      = useState<Message[]>([]);
  const [pending,       setPending]       = useState<LocalMessage[]>(() => (boxKey ? reviveOutbox(boxKey) : []));
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [forbidden,     setForbidden]     = useState(false);
  const [notFound,      setNotFound]      = useState(false);
  const [stale,         setStale]         = useState(false);
  const [hasOlder,      setHasOlder]      = useState(false);
  const [loadingOlder,  setLoadingOlder]  = useState(false);
  const [newestVersion, setNewestVersion] = useState(0);
  const [tick,          setTick]          = useState(0);

  const messagesRef = useRef<Message[]>([]);
  messagesRef.current = messages;
  const pendingRef = useRef<LocalMessage[]>([]);
  pendingRef.current = pending;
  const readSentRef = useRef(0);
  /**
   * Contiguous server cursor: every visible message up to this id has been received from a list or
   * poll response. `null` until the first history response arrives; 0 for a thread with no messages.
   */
  const cursorRef  = useRef<number | null>(null);
  /** Bumped by every (re)load so a response that belongs to an earlier load is dropped. */
  const loadGenRef = useRef(0);
  /** The first history request of the current load is running (it, not a poll, sets the cursor). */
  const loadingRef = useRef(true);
  const threadRef   = useRef(threadId);
  threadRef.current = threadId;
  const onReadRef = useRef(opts.onRead);
  onReadRef.current = opts.onRead;
  const onSentRef = useRef(opts.onSent);
  onSentRef.current = opts.onSent;
  const boxKeyRef = useRef(boxKey);
  boxKeyRef.current = boxKey;
  const mountedRef = useRef(true);
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  const pollRef = useRef<() => Promise<void>>(async () => {});

  // Mirror the outbox for this thread (restored on mount; updated by sends that finish after a switch).
  useEffect(() => {
    if (!boxKey) { setPending([]); return; }
    let seen = reviveOutbox(boxKey);
    setPending(seen);
    return subscribeOutbox(boxKey, () => {
      const next = loadOutbox(boxKey);
      // A send started by an earlier view of this thread was confirmed: fetch it now, not on the next tick.
      const settled = seen.some(p => !next.some(n => n.clientMessageId === p.clientMessageId)
        && !messagesRef.current.some(m => m.clientMessageId === p.clientMessageId));
      seen = next;
      setPending(next);
      if (settled) pollRef.current().catch(() => {});
    });
  }, [boxKey]);

  const handleFailure = useCallback((e: unknown, fallback: string): string => {
    const status = httpStatus(e);
    if (status === 403) setForbidden(true);
    if (status === 404) setNotFound(true);
    if (isNetworkError(e)) return 'Could not reach CareNexus. Check your connection.';
    return apiErrorMessage(e, fallback);
  }, []);

  /** Marks the thread read up to `newest`, an id the server listed (never a POST-confirmed one). */
  const markRead = useCallback((newest: number) => {
    const id = threadRef.current;
    if (id == null || newest <= readSentRef.current) return;
    readSentRef.current = newest;
    markThreadRead(id, newest)
      .then(res => { if (threadRef.current === id) onReadRef.current?.(id, res.unreadCount); })
      .catch(e => {
        readSentRef.current = 0;
        if (httpStatus(e) === 403 && threadRef.current === id) setForbidden(true);
      });
  }, []);

  /** Drop local copies that the server now has (matched by clientMessageId). */
  const settlePending = useCallback((incoming: Message[]) => {
    const confirmed = new Set(incoming.filter(m => m.mine && m.clientMessageId).map(m => m.clientMessageId as string));
    const key = boxKeyRef.current;
    if (confirmed.size === 0 || !key) return;
    updateOutbox(key, prev => prev.filter(p => !confirmed.has(p.clientMessageId)));
  }, []);

  /** Adds messages to the visible list. Does not touch the cursor or the read position. */
  const show = useCallback((incoming: Message[]) => {
    if (incoming.length === 0) return;
    const known = new Set(messagesRef.current.map(m => m.messageId));
    const added = incoming.some(m => !known.has(m.messageId));
    const merged = mergeMessages(messagesRef.current, incoming);
    messagesRef.current = merged;
    setMessages(merged);
    settlePending(incoming);
    if (added) setNewestVersion(v => v + 1);
  }, [settlePending]);

  /**
   * Takes one list / poll response: shows its messages, moves the cursor to its highest id and marks
   * the thread read up to there. `floor` is the cursor an empty response establishes (0 on first load).
   */
  const absorb = useCallback((incoming: Message[], floor?: number) => {
    show(incoming);
    const cursor = incoming.reduce((max, m) => Math.max(max, m.messageId), cursorRef.current ?? floor ?? 0);
    cursorRef.current = cursor;
    markRead(cursor);
  }, [show, markRead]);

  // Initial load (and reload) for the thread.
  useEffect(() => {
    setMessages([]);
    messagesRef.current = [];
    setError(null);
    setForbidden(false);
    setNotFound(false);
    setStale(false);
    setHasOlder(false);
    readSentRef.current = 0;
    cursorRef.current = null;
    loadGenRef.current += 1;
    if (threadId == null) {
      loadingRef.current = false;
      setLoading(false);
      return;
    }
    let cancelled = false;
    loadingRef.current = true;
    setLoading(true);
    // No cursor: the server treats this as opening the conversation (newest page, audited once).
    fetchMessages(threadId, { limit: PAGE_LIMIT })
      .then(res => {
        if (cancelled) return;
        readSentRef.current = res.lastReadMessageId ?? 0;
        setHasOlder(res.hasMore);
        absorb(res.messages ?? [], 0);
      })
      .catch(e => { if (!cancelled) setError(handleFailure(e, 'Failed to load messages.')); })
      .finally(() => {
        if (cancelled) return;
        loadingRef.current = false;
        setLoading(false);
      });
    return () => { cancelled = true; };
  }, [threadId, tick, absorb, handleFailure]);

  const poll = useCallback(async () => {
    const id  = threadRef.current;
    const gen = loadGenRef.current;
    if (id == null) return;
    const current = () => threadRef.current === id && loadGenRef.current === gen;
    // Follow `hasMore` a few pages so a long gap catches up in one poll.
    for (let i = 0; i < 5; i++) {
      const cursor = cursorRef.current;
      // The first history request is still running; it sets the cursor.
      if (cursor == null && loadingRef.current) return;
      try {
        if (cursor == null) {
          // The first load failed: repeat it (newest page). Every later request carries the cursor.
          const res = await fetchMessages(id, { limit: PAGE_LIMIT });
          if (!current()) return;
          setError(null);
          setStale(false);
          // Another request set the cursor meanwhile: carry on from it instead of from this page.
          if (cursorRef.current != null) continue;
          readSentRef.current = res.lastReadMessageId ?? 0;
          setHasOlder(res.hasMore);
          absorb(res.messages ?? [], 0);
          return;
        }
        // `after=0` for a thread with no messages yet: the polling path, not a fresh open.
        const res = await fetchMessages(id, { after: cursor, limit: POLL_LIMIT });
        if (!current()) return;
        setError(null);
        setStale(false);
        absorb(res.messages ?? []);
        if (!res.hasMore) return;
      } catch (e) {
        if (!current()) return;
        const status = httpStatus(e);
        if (status === 403 || status === 404) handleFailure(e, '');
        // Transient (unreachable, timeout, 5xx): keep showing what we have, flag it, try again next tick.
        else setStale(true);
        return;
      }
    }
  }, [absorb, handleFailure]);

  pollRef.current = poll;
  usePolling(poll, THREAD_POLL_MS, threadId != null && !loading && !forbidden && !notFound);

  const loadOlder = useCallback(async () => {
    const id = threadRef.current;
    const oldest = messagesRef.current[0]?.messageId;
    if (id == null || oldest == null || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const res = await fetchMessages(id, { before: oldest, limit: PAGE_LIMIT });
      if (threadRef.current !== id) return;
      const merged = mergeMessages(messagesRef.current, res.messages ?? []);
      messagesRef.current = merged;
      setMessages(merged);
      setHasOlder(res.hasMore);
    } catch (e) {
      setError(handleFailure(e, 'Failed to load earlier messages.'));
    } finally {
      setLoadingOlder(false);
    }
  }, [loadingOlder, handleFailure]);

  // The outbox is updated through its key even if this view unmounted meanwhile (thread switch),
  // so a send that finishes later is settled or marked failed when the user comes back.
  const post = useCallback((local: LocalMessage) => {
    const id  = threadRef.current;
    const key = boxKeyRef.current;
    if (id == null || !key) return;
    const cid = local.clientMessageId;
    const started = outboxSession();
    // After a sign-out the outbox was cleared on purpose; a late result must not write the text back.
    const fail = (error: string) => {
      if (outboxSession() !== started) return;
      updateOutbox(key, prev => prev.map(p => (p.clientMessageId === cid ? { ...p, status: 'failed', error } : p)));
    };
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      fail(OFFLINE_SEND_TEXT);
      return;
    }
    markInFlight(cid, true);
    sendMessage(id, { clientMessageId: cid, body: local.body, urgent: local.urgent })
      .then(msg => {
        markInFlight(cid, false);
        const confirmed = { ...msg, mine: true, clientMessageId: msg.clientMessageId ?? cid };
        if (outboxSession() !== started) return;
        const visible = mountedRef.current && threadRef.current === id;
        // Shown at once, but the cursor stays where the last list response left it: the poll below
        // asks for everything after that, so a message stored just before this one is not skipped.
        if (visible) show([confirmed]);
        updateOutbox(key, prev => prev.filter(p => p.clientMessageId !== cid));
        onSentRef.current?.(id, confirmed);
        if (visible) pollRef.current().catch(() => {});
      })
      .catch(e => {
        markInFlight(cid, false);
        fail(isNetworkError(e) ? OFFLINE_SEND_TEXT : apiErrorMessage(e, NOT_SENT_TEXT));
        if (mountedRef.current && threadRef.current === id && httpStatus(e) === 403) setForbidden(true);
      });
  }, [show]);

  const send = useCallback((body: string, urgent: boolean) => {
    const local: LocalMessage = {
      clientMessageId: newClientId(),
      body,
      urgent,
      status:    'sending',
      error:     null,
      createdAt: new Date().toISOString(),
    };
    const key = boxKeyRef.current;
    if (!key) return;
    updateOutbox(key, prev => [...prev, local]);
    setNewestVersion(v => v + 1);
    post(local);
  }, [post]);

  const retry = useCallback((clientMessageId: string) => {
    const key = boxKeyRef.current;
    const local = pendingRef.current.find(p => p.clientMessageId === clientMessageId);
    if (!local || !key) return;
    const again: LocalMessage = { ...local, status: 'sending', error: null };
    updateOutbox(key, prev => prev.map(p => (p.clientMessageId === clientMessageId ? again : p)));
    post(again); // same clientMessageId: the server returns the stored copy if the first try landed
  }, [post]);

  const discard = useCallback((clientMessageId: string) => {
    const key = boxKeyRef.current;
    if (key) updateOutbox(key, prev => prev.filter(p => p.clientMessageId !== clientMessageId));
  }, []);

  return {
    messages, pending, loading, error, forbidden, notFound,
    stale: stale && online,
    hasOlder, loadingOlder, newestVersion,
    loadOlder,
    reload: () => setTick(t => t + 1),
    send, retry, discard,
  };
}
