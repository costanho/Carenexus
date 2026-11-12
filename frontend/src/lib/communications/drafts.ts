import { type MessagingStorage, messagingStorage } from './storage';

// Per-thread message drafts, kept in sessionStorage on web (cleared when the tab closes) and in the
// device's secure store on iOS / Android (kept across app restarts; see ./storage.native.ts).
// Key: carenexus.draft.v1.{currentUserSystemId}.{threadId}  (new conversation: ...new.{patientSystemId})
// Drafts are cleared on sign-out (see ./session.ts).

const PREFIX = 'carenexus.draft.v1';

/** Web: the tab's sessionStorage (null where missing or blocked). Native: the device store. Shared with the outbox. */
export function storage(): MessagingStorage | null {
  return messagingStorage();
}

export function threadDraftKey(userSystemId: string, threadId: number): string {
  return `${PREFIX}.${userSystemId}.${threadId}`;
}

export function newConversationDraftKey(userSystemId: string, patientSystemId: string): string {
  return `${PREFIX}.${userSystemId}.new.${patientSystemId}`;
}

export function loadDraft(key: string): string {
  try {
    return storage()?.getItem(key) ?? '';
  } catch {
    return '';
  }
}

export function saveDraft(key: string, value: string): void {
  try {
    const s = storage();
    if (!s) return;
    if (value.trim()) s.setItem(key, value);
    else s.removeItem(key);
  } catch {
    // storage full or blocked: drafts are a convenience only
  }
}

export function clearDraft(key: string): void {
  try {
    storage()?.removeItem(key);
  } catch {
    // ignore
  }
}

/** Removes every stored key that starts with `prefix` and, when `keepUser` is given, is not that user's. */
export function removeStoredKeys(prefix: string, keepUser?: string | null): void {
  try {
    const s = storage();
    if (!s) return;
    const keep = keepUser ? `${prefix}.${keepUser}.` : null;
    const doomed: string[] = [];
    for (let i = 0; i < s.length; i++) {
      const k = s.key(i);
      if (k && k.startsWith(`${prefix}.`) && !(keep && k.startsWith(keep))) doomed.push(k);
    }
    doomed.forEach(k => s.removeItem(k));
    s.removeMatchingLater?.(`${prefix}.`, keep);
  } catch {
    // ignore
  }
}

/** Drops all drafts in this tab (sign-out), or all but `keepUser`'s (a different user signed in). */
export function clearAllDrafts(keepUser?: string | null): void {
  removeStoredKeys(PREFIX, keepUser);
}
