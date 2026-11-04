// Per-thread message drafts, kept in sessionStorage on web (cleared when the tab closes).
// Key: carenexus.draft.v1.{currentUserSystemId}.{threadId}  (new conversation: ...new.{patientSystemId})

const PREFIX = 'carenexus.draft.v1';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' && window.sessionStorage ? window.sessionStorage : null;
  } catch {
    return null;
  }
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
