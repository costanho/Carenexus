import { removeStoredKeys, storage } from './drafts';

// Per-thread outbox: messages the user sent that the server has not confirmed yet (sending or
// failed). Kept in memory and mirrored to sessionStorage with the same per-user scheme as drafts,
// so unsent text survives switching threads, remounts and a page reload, and Retry can reuse the
// original clientMessageId (the server stores it once).
// Key: carenexus.outbox.v1.{currentUserSystemId}.{threadId}. Cleared on sign-out (see ./session.ts).

const PREFIX = 'carenexus.outbox.v1';

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

export const NOT_SENT_TEXT = 'Message not sent.';

export function threadOutboxKey(userSystemId: string, threadId: number): string {
  return `${PREFIX}.${userSystemId}.${threadId}`;
}

/** Outbox key used while the signed-in user is not known yet: kept in memory for this page only. */
export function memoryOutboxKey(threadId: number): string {
  return `memory.${threadId}`;
}

const memory    = new Map<string, LocalMessage[]>();
const listeners = new Map<string, Set<() => void>>();
/** clientMessageIds with a POST in flight in this page (survives a thread view unmounting). */
const inFlight  = new Set<string>();
/** Bumped on sign-out: a send started before it must not write its outcome back afterwards. */
let session = 0;

export function outboxSession(): number {
  return session;
}

function isLocalMessage(v: any): v is LocalMessage {
  return !!v && typeof v.clientMessageId === 'string' && typeof v.body === 'string';
}

function readStored(key: string): LocalMessage[] {
  try {
    const raw = storage()?.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter(isLocalMessage) : [];
  } catch {
    return [];
  }
}

function writeStored(key: string, items: LocalMessage[]): void {
  if (!key.startsWith(`${PREFIX}.`)) return; // memory-only key (user not known yet)
  try {
    const s = storage();
    if (!s) return;
    if (items.length) s.setItem(key, JSON.stringify(items));
    else s.removeItem(key);
  } catch {
    // storage full or blocked: the in-memory copy still works for this page
  }
}

function emit(key: string): void {
  listeners.get(key)?.forEach(l => l());
}

export function loadOutbox(key: string): LocalMessage[] {
  const cached = memory.get(key);
  if (cached) return cached;
  const stored = readStored(key);
  if (stored.length) memory.set(key, stored);
  return stored;
}

/** Applies `fn` to the outbox for `key`, persists it and notifies subscribers. */
export function updateOutbox(key: string, fn: (items: LocalMessage[]) => LocalMessage[]): LocalMessage[] {
  const next = fn(loadOutbox(key));
  if (next.length) memory.set(key, next);
  else memory.delete(key);
  writeStored(key, next);
  emit(key);
  return next;
}

/**
 * Puts a message whose send failed outside a thread view (the first message of a new conversation)
 * into that thread's outbox as failed. The thread view then shows it with Retry and Discard, and a
 * retry reuses `clientMessageId`, so the server stores it once even if the first try did land.
 */
export function addFailedMessage(
  key: string,
  message: { clientMessageId: string; body: string; urgent: boolean; error?: string | null },
): void {
  const failed: LocalMessage = {
    clientMessageId: message.clientMessageId,
    body:            message.body,
    urgent:          message.urgent,
    status:          'failed',
    error:           message.error || NOT_SENT_TEXT,
    createdAt:       new Date().toISOString(),
  };
  updateOutbox(key, prev => [...prev.filter(p => p.clientMessageId !== failed.clientMessageId), failed]);
}

export function subscribeOutbox(key: string, fn: () => void): () => void {
  let set = listeners.get(key);
  if (!set) listeners.set(key, (set = new Set()));
  set.add(fn);
  return () => { set?.delete(fn); };
}

export function markInFlight(clientMessageId: string, on: boolean): void {
  if (on) inFlight.add(clientMessageId);
  else inFlight.delete(clientMessageId);
}

/**
 * Outbox as restored on opening a thread: an item still marked "sending" whose request is no longer
 * in flight (page reloaded mid-send) becomes "failed", so the user can retry or discard it.
 */
export function reviveOutbox(key: string): LocalMessage[] {
  const items = loadOutbox(key);
  if (!items.some(i => i.status === 'sending' && !inFlight.has(i.clientMessageId))) return items;
  return updateOutbox(key, list => list.map(i => (i.status === 'sending' && !inFlight.has(i.clientMessageId)
    ? { ...i, status: 'failed', error: NOT_SENT_TEXT } : i)));
}

/** Drops every outbox in this tab (sign-out), or all but `keepUser`'s (a different user signed in). */
export function clearAllOutboxes(keepUser?: string | null): void {
  const keep = keepUser ? `${PREFIX}.${keepUser}.` : null;
  if (!keep) session += 1;
  const touched = Array.from(memory.keys()).filter(k => !(keep && k.startsWith(keep)));
  touched.forEach(k => memory.delete(k));
  removeStoredKeys(PREFIX, keepUser);
  touched.forEach(emit);
}
