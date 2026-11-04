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
import { OFFLINE_SEND_TEXT, type Message } from '@/lib/communications/types';
import { usePolling } from './use-page-active';

const PAGE_LIMIT = 30;
const POLL_LIMIT = 100;
const THREAD_POLL_MS = 5_000;

export type LocalMessageStatus = 'sending' | 'failed';

/** A message the user sent that the server has not confirmed yet. */
export interface LocalMessage {
  clientMessageId: string;
  body:            string;
  urgent:          boolean;
  status:          LocalMessageStatus;
  error:           string | null;
  createdAt:       string;
}

interface State {
  messages:      Message[];
  pending:       LocalMessage[];
  loading:       boolean;
  error:         string | null;
  /** 403: the user was removed or lost permission. */
  forbidden:     boolean;
  notFound:      boolean;
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
 * Polls `after={newestId}` every 5 s while the page is visible and online, and marks new messages read.
 */
export function useThreadMessages(
  threadId: number | null,
  opts: { onRead?: (threadId: number, unreadCount: number) => void } = {},
): State {
  const [messages,      setMessages]      = useState<Message[]>([]);
  const [pending,       setPending]       = useState<LocalMessage[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState<string | null>(null);
  const [forbidden,     setForbidden]     = useState(false);
  const [notFound,      setNotFound]      = useState(false);
  const [hasOlder,      setHasOlder]      = useState(false);
  const [loadingOlder,  setLoadingOlder]  = useState(false);
  const [newestVersion, setNewestVersion] = useState(0);
  const [tick,          setTick]          = useState(0);

  const messagesRef = useRef<Message[]>([]);
  messagesRef.current = messages;
  const pendingRef = useRef<LocalMessage[]>([]);
  pendingRef.current = pending;
  const readSentRef = useRef(0);
  const threadRef   = useRef(threadId);
  threadRef.current = threadId;
  const onReadRef = useRef(opts.onRead);
  onReadRef.current = opts.onRead;

  const handleFailure = useCallback((e: unknown, fallback: string): string => {
    const status = httpStatus(e);
    if (status === 403) setForbidden(true);
    if (status === 404) setNotFound(true);
    if (isNetworkError(e)) return 'Could not reach CareNexus. Check your connection.';
    return apiErrorMessage(e, fallback);
  }, []);

  const markRead = useCallback((list: Message[]) => {
    const id = threadRef.current;
    if (id == null || list.length === 0) return;
    const newest = list[list.length - 1].messageId;
    if (newest <= readSentRef.current) return;
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
    if (confirmed.size === 0) return;
    setPending(prev => prev.filter(p => !confirmed.has(p.clientMessageId)));
  }, []);

  const absorb = useCallback((incoming: Message[]) => {
    if (incoming.length === 0) return;
    const merged = mergeMessages(messagesRef.current, incoming);
    messagesRef.current = merged;
    setMessages(merged);
    settlePending(incoming);
    setNewestVersion(v => v + 1);
    markRead(merged);
  }, [markRead, settlePending]);

  // Initial load (and reload) for the thread.
  useEffect(() => {
    setMessages([]);
    messagesRef.current = [];
    setPending([]);
    setError(null);
    setForbidden(false);
    setNotFound(false);
    setHasOlder(false);
    readSentRef.current = 0;
    if (threadId == null) {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchMessages(threadId, { limit: PAGE_LIMIT })
      .then(res => {
        if (cancelled) return;
        readSentRef.current = res.lastReadMessageId ?? 0;
        setHasOlder(res.hasMore);
        absorb(res.messages ?? []);
      })
      .catch(e => { if (!cancelled) setError(handleFailure(e, 'Failed to load messages.')); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [threadId, tick, absorb, handleFailure]);

  const poll = useCallback(async () => {
    const id = threadRef.current;
    if (id == null) return;
    // Follow `hasMore` a few pages so a long gap catches up in one poll.
    for (let i = 0; i < 5; i++) {
      const list   = messagesRef.current;
      const newest = list.length ? list[list.length - 1].messageId : undefined;
      try {
        const res = newest != null
          ? await fetchMessages(id, { after: newest, limit: POLL_LIMIT })
          : await fetchMessages(id, { limit: PAGE_LIMIT });
        if (threadRef.current !== id) return;
        setError(null);
        absorb(res.messages ?? []);
        if (newest == null) setHasOlder(res.hasMore);
        if (newest == null || !res.hasMore) return;
      } catch (e) {
        if (threadRef.current !== id) return;
        const status = httpStatus(e);
        if (status === 403 || status === 404) handleFailure(e, '');
        return; // transient errors: keep showing what we have, try again next tick
      }
    }
  }, [absorb, handleFailure]);

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

  const post = useCallback((local: LocalMessage) => {
    const id = threadRef.current;
    if (id == null) return;
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      setPending(prev => prev.map(p => p.clientMessageId === local.clientMessageId
        ? { ...p, status: 'failed', error: OFFLINE_SEND_TEXT } : p));
      return;
    }
    sendMessage(id, { clientMessageId: local.clientMessageId, body: local.body, urgent: local.urgent })
      .then(msg => {
        if (threadRef.current !== id) return;
        absorb([{ ...msg, mine: true, clientMessageId: msg.clientMessageId ?? local.clientMessageId }]);
        setPending(prev => prev.filter(p => p.clientMessageId !== local.clientMessageId));
      })
      .catch(e => {
        if (threadRef.current !== id) return;
        const status = httpStatus(e);
        if (status === 403) setForbidden(true);
        const msg = isNetworkError(e) ? OFFLINE_SEND_TEXT : apiErrorMessage(e, 'Message not sent.');
        setPending(prev => prev.map(p => p.clientMessageId === local.clientMessageId
          ? { ...p, status: 'failed', error: msg } : p));
      });
  }, [absorb]);

  const send = useCallback((body: string, urgent: boolean) => {
    const local: LocalMessage = {
      clientMessageId: newClientId(),
      body,
      urgent,
      status:    'sending',
      error:     null,
      createdAt: new Date().toISOString(),
    };
    setPending(prev => [...prev, local]);
    setNewestVersion(v => v + 1);
    post(local);
  }, [post]);

  const retry = useCallback((clientMessageId: string) => {
    const local = pendingRef.current.find(p => p.clientMessageId === clientMessageId);
    if (!local) return;
    const again: LocalMessage = { ...local, status: 'sending', error: null };
    setPending(prev => prev.map(p => (p.clientMessageId === clientMessageId ? again : p)));
    post(again); // same clientMessageId: the server returns the stored copy if the first try landed
  }, [post]);

  const discard = useCallback((clientMessageId: string) => {
    setPending(prev => prev.filter(p => p.clientMessageId !== clientMessageId));
  }, []);

  return {
    messages, pending, loading, error, forbidden, notFound,
    hasOlder, loadingOlder, newestVersion,
    loadOlder,
    reload: () => setTick(t => t + 1),
    send, retry, discard,
  };
}
