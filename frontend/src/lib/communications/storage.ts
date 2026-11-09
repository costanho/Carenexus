// Where messaging drafts and the unsent outbox are kept (see ./drafts.ts and ./outbox.ts).
// Web: the tab's sessionStorage (cleared when the tab closes). iOS / Android use storage.native.ts.

/** The part of the DOM Storage API that drafts and the outbox use. */
export type MessagingStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'> & {
  /**
   * Native only: also removes matching keys the store has not loaded yet (keychain locked at start), when it
   * loads. key()/length list only loaded keys, so a sign-out in that window would otherwise miss them.
   */
  removeMatchingLater?: (prefix: string, keepPrefix: string | null) => void;
};

/** The tab's sessionStorage, or null where it is missing or blocked. */
export function messagingStorage(): MessagingStorage | null {
  try {
    return typeof window !== 'undefined' && window.sessionStorage ? window.sessionStorage : null;
  } catch {
    return null;
  }
}
