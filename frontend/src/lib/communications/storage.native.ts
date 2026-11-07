import * as SecureStore from 'expo-secure-store';
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

const NS          = 'carenexus.msgstore.v1';
const INDEX_BASE  = `${NS}.index`;
// UTF-16 code units per chunk. At most 3 bytes each in UTF-8, so a chunk stays under 2048 bytes.
const CHUNK_UNITS = 600;
const OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
};

/** Where a key's value is stored: data chunks `${NS}.d${id}.${i}`, i < chunks. */
interface Entry { id: number; chunks: number }

const memory  = new Map<string, string>();
const entries = new Map<string, Entry>();
let indexChunks = 0;
let nextId = 1;

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

async function writeChunks(base: string, value: string, previousCount: number): Promise<number> {
  const parts = split(value);
  for (let i = 0; i < parts.length; i++) await SecureStore.setItemAsync(`${base}.${i}`, parts[i], OPTIONS);
  for (let i = parts.length; i < previousCount; i++) await SecureStore.deleteItemAsync(`${base}.${i}`, OPTIONS);
  return parts.length;
}

async function deleteChunks(base: string, count: number): Promise<void> {
  for (let i = 0; i < count; i++) await SecureStore.deleteItemAsync(`${base}.${i}`, OPTIONS);
}

// ─── Load once ────────────────────────────────────────────────────────────────

function hydrate(): void {
  try {
    const count = Number(SecureStore.getItem(`${INDEX_BASE}.n`, OPTIONS) ?? 0);
    if (!count) return;
    const raw = readChunksSync(INDEX_BASE, count);
    if (raw == null) return;
    indexChunks = count;
    const list = JSON.parse(raw) as [string, number, number][];
    for (const [key, id, chunks] of Array.isArray(list) ? list : []) {
      if (typeof key !== 'string' || typeof id !== 'number' || typeof chunks !== 'number') continue;
      nextId = Math.max(nextId, id + 1);
      const value = readChunksSync(`${NS}.d${id}`, chunks);
      // A value whose chunks are incomplete is dropped (and cleaned up by the next write).
      entries.set(key, { id, chunks });
      if (value != null) memory.set(key, value);
      else dirty.add(key);
    }
  } catch {
    // Unreadable (e.g. keychain locked or corrupted): start empty; drafts are a convenience.
    memory.clear();
  }
}

// ─── Background writes ────────────────────────────────────────────────────────

const dirty = new Set<string>();
let flushing: Promise<void> | null = null;
let scheduled = false;

async function flush(): Promise<void> {
  while (dirty.size) {
    const keys = Array.from(dirty);
    dirty.clear();
    for (const key of keys) {
      const value = memory.get(key);
      const entry = entries.get(key);
      try {
        if (value == null) {
          if (entry) await deleteChunks(`${NS}.d${entry.id}`, entry.chunks);
          entries.delete(key);
        } else {
          const e = entry ?? { id: nextId++, chunks: 0 };
          e.chunks = await writeChunks(`${NS}.d${e.id}`, value, e.chunks);
          entries.set(key, e);
        }
      } catch {
        // Keychain unavailable for now: the in-memory copy still serves this run of the app.
      }
    }
    try {
      const index = JSON.stringify(Array.from(entries, ([k, e]) => [k, e.id, e.chunks]));
      if (entries.size === 0) {
        await deleteChunks(INDEX_BASE, indexChunks);
        await SecureStore.deleteItemAsync(`${INDEX_BASE}.n`, OPTIONS);
        indexChunks = 0;
      } else {
        indexChunks = await writeChunks(INDEX_BASE, index, indexChunks);
        await SecureStore.setItemAsync(`${INDEX_BASE}.n`, String(indexChunks), OPTIONS);
      }
    } catch {
      // as above
    }
  }
}

function schedule(key: string): void {
  dirty.add(key);
  if (scheduled) return;
  scheduled = true;
  // Coalesce a burst of writes (typing, sign-out clearing many keys) into one pass.
  setTimeout(() => {
    scheduled = false;
    const run = (flushing ?? Promise.resolve()).then(flush);
    flushing = run.finally(() => { if (flushing === run) flushing = null; });
  }, 0);
}

hydrate();

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
    if (!memory.has(key) && !entries.has(key)) return;
    memory.delete(key);
    schedule(key);
  },
};

/** The device store for drafts and the outbox (see the note at the top). */
export function messagingStorage(): MessagingStorage | null {
  return nativeStorage;
}
