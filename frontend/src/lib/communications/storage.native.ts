import * as SecureStore from 'expo-secure-store';
import { AppState } from 'react-native';
import type { MessagingStorage } from './storage';

export type { MessagingStorage } from './storage';

// iOS / Android store for messaging drafts and the unsent outbox (web uses sessionStorage, ./storage.ts).
//
// Why expo-secure-store and not AsyncStorage: drafts and unsent messages can hold health details, and
// SecureStore keeps them encrypted at rest (iOS Keychain, Android Keystore), "this device only" so they are
// never synced to another device through a keychain backup. It is already installed (auth tokens use it) and
// runs in Expo Go; AsyncStorage would add a dependency and write plain text to disk.
//
// SecureStore has no key listing and warns above ~2 KB per value, while drafts and the outbox need
// key()/length (sign-out clears every key with a prefix) and messages can be 4000 characters. So:
//   - every value lives in memory, which serves all reads synchronously (the drafts/outbox code is sync);
//   - an index records which keys exist, and each value is split into small chunks;
//   - writes go to SecureStore in the background, coalesced and in order.
// The index and values are read once, synchronously, when this module loads, so drafts survive app restarts.
//
// Crash safety. The app can be killed between any two SecureStore writes, and SecureStore cannot list keys,
// so a chunk the index no longer names could never be found again (and sign-out could never clear it). So:
//   - a changed value is written under a NEW id; chunks that an index names are never overwritten;
//   - before any data is written, the ids it will use are reserved by writing a high-water mark, so ids are
//     never reused and the next start knows exactly which uncommitted ids may hold data;
//   - the index goes to whichever of two slots is not live, and one write of the pointer switches to it;
//   - ids replaced or removed by a commit are listed in that index as `trash` and deleted after it is live;
//     an id leaves the trash only once its chunks are confirmed gone (iOS reports a failed delete as success
//     while the device is locked), so a kill or a locked device leaves them listed for a later pass.
// A pass that fails (e.g. device locked) is retried with backoff and when the app becomes active again.

const NS          = 'carenexus.msgstore.v1';
const POINTER     = `${NS}.index.ptr`;          // "<slot>:<chunks>:<nextId>"
const RESERVED    = `${NS}.reserved`;           // high-water mark: every id below it may hold data
const LEGACY_BASE = `${NS}.index`;              // pre-slot index: chunks `${LEGACY_BASE}.${i}`, count in `.n`
// UTF-16 code units per chunk. At most 3 bytes each in UTF-8, so a chunk stays under 2048 bytes.
const CHUNK_UNITS = 600;
// Upper bound when probing for chunks of unknown count (a 4000-character message is 7 chunks).
const MAX_PROBE   = 64;
// The old format had no high-water mark; when its index is unreadable, ids are probed until this many misses.
const LEGACY_MISS_WINDOW = 256;
const RETRY_MS    = [2_000, 10_000, 60_000];
const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

type Slot = 'a' | 'b';
/** Where a key's value is stored: data chunks `${NS}.d${id}.${i}`, i < chunks. */
interface Entry { id: number; chunks: number }
interface IndexFile { entries: [string, number, number][]; trash: [number, number][] }

const memory  = new Map<string, string>();
const entries = new Map<string, Entry>();
/** Data ids that no live index names and that still have to be deleted (id -> chunk count). */
const trash   = new Map<number, number>();
let slot: Slot = 'a';
let nextId = 1;
/** Old single-slot index chunks to delete after the first commit (0 when there are none). */
let legacyChunks = 0;
/** False while the store could not be read: writes stay in memory, and loading is retried before any write,
 *  so a commit never forgets data it could not see. */
let loaded = false;
/** Keys set or removed while not loaded: their in-memory state wins over what loading finds. */
const touched = new Set<string>();
/** Prefix removals (sign-out) requested while not loaded, applied to whatever loading finds. */
const pendingClears: { prefix: string; keep: string | null }[] = [];
const clearedByPending = (key: string) =>
  pendingClears.some(c => key.startsWith(c.prefix) && !(c.keep && key.startsWith(c.keep)));
/** Chunk count of the live index slot (the spare slot holds at most the previous one). */
let liveChunks = 0;
let spareChunks = 0;

const dataBase  = (id: number) => `${NS}.d${id}`;
const indexBase = (s: Slot) => `${NS}.index.${s}`;
const other     = (s: Slot): Slot => (s === 'a' ? 'b' : 'a');

function split(value: string): string[] {
  const parts: string[] = [];
  let i = 0;
  while (i < value.length) {
    let end = Math.min(i + CHUNK_UNITS, value.length);
    // Never cut a surrogate pair in half (a lone surrogate would not survive UTF-8 encoding).
    const code = value.charCodeAt(end - 1);
    if (end < value.length && code >= 0xd800 && code <= 0xdbff) end -= 1;
    parts.push(value.slice(i, end));
    i = end;
  }
  return parts.length ? parts : [''];
}

function readChunksSync(base: string, count: number): string | null {
  let out = '';
  for (let i = 0; i < count; i++) {
    const part = SecureStore.getItem(`${base}.${i}`, OPTIONS);
    if (part == null) return null;
    out += part;
  }
  return out;
}

/** Number of consecutive chunks `${base}.0`, `.1`, … that exist (bounded). */
function countChunksSync(base: string): number {
  let n = 0;
  while (n < MAX_PROBE && SecureStore.getItem(`${base}.${n}`, OPTIONS) != null) n++;
  return n;
}

/** Writes `value` as chunks, then removes any chunks past the new end (`previousCount`, or as many as exist). */
async function writeChunks(base: string, value: string, previousCount = 0): Promise<number> {
  const parts = split(value);
  for (let i = 0; i < parts.length; i++) await SecureStore.setItemAsync(`${base}.${i}`, parts[i], OPTIONS);
  for (let i = parts.length; i < MAX_PROBE; i++) {
    if (i >= previousCount && SecureStore.getItem(`${base}.${i}`, OPTIONS) == null) break;
    await SecureStore.deleteItemAsync(`${base}.${i}`, OPTIONS);
  }
  return parts.length;
}

/** Deletes chunks and confirms they are gone (throws if any is still there or cannot be checked). Only chunks
 *  past `count` are probed, so a known count costs one read of a missing key, not a decrypt per chunk. */
async function deleteChunks(base: string, count: number): Promise<void> {
  let n = Math.max(0, count);
  while (n < MAX_PROBE && SecureStore.getItem(`${base}.${n}`, OPTIONS) != null) n++;
  for (let i = 0; i < n; i++) await SecureStore.deleteItemAsync(`${base}.${i}`, OPTIONS);
  for (let i = 0; i < n; i++) {
    if (SecureStore.getItem(`${base}.${i}`, OPTIONS) != null) throw new Error('chunk still present');
  }
}

// ─── Load ─────────────────────────────────────────────────────────────────────

interface Loaded {
  slot: Slot; nextId: number; legacyChunks: number; liveChunks: number; spareChunks: number;
  entries: Map<string, Entry>; values: Map<string, string>; trash: Map<number, number>;
  /** True when the store needs a commit right away (cleanup or migration). */
  needsCommit: boolean;
}

/** Every id from `from` with data, until `window` ids in a row have none. */
function probeIds(from: number, window: number, into: Map<number, number>): number {
  let misses = 0, last = from - 1;
  for (let id = from; misses < window; id++) {
    const chunks = countChunksSync(dataBase(id));
    if (chunks) { into.set(id, chunks); misses = 0; last = id; } else misses++;
  }
  return last + 1;
}

/** Reads the whole store without touching module state; throws when the keychain cannot be read. */
function readStoreSync(): Loaded {
  const out: Loaded = { slot: 'a', nextId: 1, legacyChunks: 0, liveChunks: 0, spareChunks: 0, entries: new Map(), values: new Map(), trash: new Map(), needsCommit: false };
  let list: IndexFile = { entries: [], trash: [] };
  const ptr = SecureStore.getItem(POINTER, OPTIONS);
  if (ptr) {
    const [s, c, n] = ptr.split(':');
    out.slot = s === 'b' ? 'b' : 'a';
    out.nextId = Math.max(1, Number(n) || 1);
    const chunks = Math.max(0, Number(c) || 0);
    out.liveChunks = chunks;
    const raw = chunks ? readChunksSync(indexBase(out.slot), chunks) : null;
    let parsed: Partial<IndexFile> | null = null;
    try { parsed = raw == null ? null : JSON.parse(raw) as Partial<IndexFile>; } catch { parsed = null; }
    if (parsed) {
      list = { entries: Array.isArray(parsed.entries) ? parsed.entries : [], trash: Array.isArray(parsed.trash) ? parsed.trash : [] };
    } else {
      // The live index is lost (should not happen: it is written before the pointer). Everything below the
      // high-water mark could hold data nothing names any more, so all of it goes to the trash (below).
      out.nextId = 1;
    }
    // Leftovers of the old single-slot format, if a kill came right after the first commit
    out.legacyChunks = countChunksSync(LEGACY_BASE);
    if (SecureStore.getItem(`${LEGACY_BASE}.n`, OPTIONS) != null) out.legacyChunks = Math.max(out.legacyChunks, 1);
  } else {
    // Format before the two slots: `${LEGACY_BASE}.n` chunks of [key, id, chunks][]
    const count = Number(SecureStore.getItem(`${LEGACY_BASE}.n`, OPTIONS) ?? 0);
    const found = Math.max(count, countChunksSync(LEGACY_BASE));
    if (found) {
      out.legacyChunks = found;
      const raw = count ? readChunksSync(LEGACY_BASE, count) : null;
      let parsed: unknown = null;
      try { parsed = raw == null ? null : JSON.parse(raw); } catch { parsed = null; }
      if (Array.isArray(parsed)) {
        list = { entries: parsed as [string, number, number][], trash: [] };
      } else {
        // Torn old index (the old code rewrote it in place): it had no high-water mark, so probe for its data.
        out.nextId = probeIds(1, LEGACY_MISS_WINDOW, out.trash);
      }
    }
  }

  for (const [id, chunks] of list.trash) {
    if (typeof id === 'number' && typeof chunks === 'number') out.trash.set(id, chunks);
  }
  for (const [key, id, chunks] of list.entries) {
    if (typeof key !== 'string' || typeof id !== 'number' || typeof chunks !== 'number') continue;
    out.nextId = Math.max(out.nextId, id + 1);
    const value = readChunksSync(dataBase(id), chunks);
    if (value != null) {
      out.entries.set(key, { id, chunks });
      out.values.set(key, value);
    } else {
      // Incomplete (keychain error): drop it, and delete whatever chunks it has
      out.trash.set(id, Math.max(chunks, countChunksSync(dataBase(id))));
    }
  }
  for (const id of out.trash.keys()) out.nextId = Math.max(out.nextId, id + 1);

  // Ids reserved after the last commit (written in whole or part, but never made live), and anything past them.
  const reserved = Number(SecureStore.getItem(RESERVED, OPTIONS) ?? 0) || 0;
  const live = new Set(Array.from(out.entries.values(), e => e.id));
  for (let id = ptr && out.nextId > 1 ? out.nextId : 1; id < Math.max(reserved, out.nextId); id++) {
    if (live.has(id) || out.trash.has(id)) continue;
    const chunks = countChunksSync(dataBase(id));
    if (chunks) out.trash.set(id, chunks);
  }
  out.nextId = probeIds(Math.max(reserved, out.nextId), 1, out.trash);

  out.spareChunks = countChunksSync(indexBase(other(out.slot)));
  out.needsCommit = out.trash.size > 0 || out.legacyChunks > 0 || out.spareChunks > 0;
  return out;
}

/** Loads the store; while it cannot be read, the app keeps working from memory and this is retried. */
function load(): boolean {
  let found: Loaded;
  try {
    found = readStoreSync();
  } catch {
    return false; // e.g. keychain locked: try again before the next write
  }
  slot = found.slot;
  nextId = found.nextId;
  legacyChunks = found.legacyChunks;
  for (const [id, chunks] of found.trash) trash.set(id, chunks);
  for (const [key, e] of found.entries) {
    entries.set(key, e);
    if (touched.has(key)) dirty.add(key);          // this run's value (or removal) replaces it
    else if (clearedByPending(key)) dirty.add(key); // signed out before the store could load: remove it
    else memory.set(key, found.values.get(key) as string);
  }
  for (const key of touched) if (!entries.has(key)) dirty.add(key);
  touched.clear();
  pendingClears.length = 0;
  liveChunks = found.liveChunks;
  spareChunks = found.spareChunks;
  loaded = true;
  if (found.needsCommit) commitWanted = true;
  return true;
}

// ─── Background writes ────────────────────────────────────────────────────────

const dirty = new Set<string>();
let flushing: Promise<void> | null = null;
let scheduled = false;
let commitWanted = false;
let retryTimer: ReturnType<typeof setTimeout> | null = null;
let retries = 0;

/** One write pass. Returns false when the keychain refused something and the pass should be retried. */
async function pass(): Promise<boolean> {
  commitWanted = false;
  const keys = Array.from(dirty);
  dirty.clear();
  // Values as of now: a key changed during this pass's awaits is back in `dirty` for the next pass, and the
  // ids reserved below must match exactly the values written.
  const snap = new Map(keys.map(k => [k, memory.get(k)] as const));
  const toWrite = keys.filter(k => snap.get(k) != null);

  // Reserve the ids this pass will use before writing any data under them.
  const first = nextId;
  if (toWrite.length) {
    try {
      await SecureStore.setItemAsync(RESERVED, String(first + toWrite.length), OPTIONS);
    } catch {
      keys.forEach(k => dirty.add(k));
      return false;
    }
    nextId = first + toWrite.length;
  }

  // Data, each changed value under a fresh id; the live index still names the old ids.
  const next = new Map(entries);
  const replaced: Entry[] = [];
  const fresh: Entry[] = [];
  let ok = true;
  let id = first;
  for (const key of keys) {
    const value = snap.get(key);
    const old   = next.get(key);
    if (value == null) {
      if (old) { replaced.push(old); next.delete(key); }
      continue;
    }
    const e: Entry = { id: id++, chunks: split(value).length };
    fresh.push(e);
    try {
      e.chunks = await writeChunks(dataBase(e.id), value);
      next.set(key, e);
      if (old) replaced.push(old);
    } catch {
      // Not stored: the old value (if any) stays live; retry this key.
      dirty.add(key);
      trash.set(e.id, e.chunks);
      ok = false;
    }
  }

  // Commit: the full index into the slot that is not live, then switch the pointer to it.
  const pending = new Map(trash);
  for (const e of replaced) pending.set(e.id, e.chunks);
  const target = other(slot);
  try {
    const file: IndexFile = { entries: Array.from(next, ([k, e]) => [k, e.id, e.chunks]), trash: Array.from(pending) };
    const written = await writeChunks(indexBase(target), JSON.stringify(file));
    await SecureStore.setItemAsync(POINTER, `${target}:${written}:${nextId}`, OPTIONS);
    spareChunks = liveChunks; // the slot that was live is now the spare
    liveChunks = written;
  } catch {
    // Not committed: the previous index stays live. The ids just written are not live, so they go to the trash
    // (they are also below the reserved mark, so a restart finds them).
    keys.forEach(k => dirty.add(k));
    for (const e of fresh) trash.set(e.id, e.chunks);
    return false;
  }
  slot = target;
  entries.clear();
  for (const [k, e] of next) entries.set(k, e);
  for (const [tid, chunks] of pending) trash.set(tid, chunks);

  // Now unreferenced: delete old data. An id stays in the trash (and in the next index) until its chunks are
  // confirmed gone.
  for (const [tid, chunks] of Array.from(trash)) {
    try {
      await deleteChunks(dataBase(tid), chunks);
      trash.delete(tid);
    } catch { ok = false; }
  }
  if (legacyChunks) {
    try {
      await deleteChunks(LEGACY_BASE, legacyChunks);
      await SecureStore.deleteItemAsync(`${LEGACY_BASE}.n`, OPTIONS);
      if (SecureStore.getItem(`${LEGACY_BASE}.n`, OPTIONS) != null) throw new Error('legacy count still present');
      legacyChunks = 0;
    } catch { ok = false; }
  }
  // The spare slot holds an older index; clear it so nothing names deleted data.
  try {
    await deleteChunks(indexBase(other(slot)), spareChunks);
    spareChunks = 0;
  } catch { ok = false; }
  return ok;
}

async function flush(): Promise<void> {
  if (!loaded && !load()) { scheduleRetry(); return; }
  while (dirty.size || commitWanted) {
    if (!(await pass())) {
      // Commit again with whatever is still in the trash once the keychain accepts writes.
      if (trash.size || legacyChunks) commitWanted = true;
      scheduleRetry();
      return;
    }
  }
  retries = 0;
}

function scheduleRetry(): void {
  if (retryTimer) return;
  const delay = RETRY_MS[Math.min(retries, RETRY_MS.length - 1)];
  retries++;
  retryTimer = setTimeout(() => { retryTimer = null; runFlush(); }, delay);
}

function runFlush(): void {
  if (scheduled) return;
  scheduled = true;
  // Coalesce a burst of writes (typing, sign-out clearing many keys) into one pass.
  setTimeout(() => {
    scheduled = false;
    const run = (flushing ?? Promise.resolve()).then(flush);
    flushing = run.finally(() => { if (flushing === run) flushing = null; });
  }, 0);
}

function schedule(key: string): void {
  if (loaded) dirty.add(key);
  else touched.add(key);
  runFlush();
}

if (load()) { if (commitWanted) runFlush(); }
else scheduleRetry();
// A locked device refuses keychain access; finish pending work when the app is back in front.
AppState.addEventListener('change', (state: string) => {
  if (state === 'active' && (!loaded || dirty.size || commitWanted || touched.size)) runFlush();
});

const nativeStorage: MessagingStorage = {
  get length() { return memory.size; },
  key(index: number) { return Array.from(memory.keys())[index] ?? null; },
  getItem(key: string) { return memory.has(key) ? (memory.get(key) as string) : null; },
  setItem(key: string, value: string) {
    const v = String(value);
    if (memory.get(key) === v) return;
    memory.set(key, v);
    schedule(key);
  },
  removeItem(key: string) {
    if (!memory.has(key) && !entries.has(key) && loaded) return;
    memory.delete(key);
    schedule(key);
  },
  removeMatchingLater(prefix: string, keepPrefix: string | null) {
    if (loaded) return; // loaded keys were all listed and removed by the caller
    pendingClears.push({ prefix, keep: keepPrefix });
    runFlush();
  },
};

/** The device store for drafts and the outbox (see the note at the top). */
export function messagingStorage(): MessagingStorage | null {
  return nativeStorage;
}
