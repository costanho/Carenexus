import { onSignedOut } from '@/lib/auth/auth.events';
import { api } from '@/lib/auth/auth.interceptor';
import { clearAllDrafts } from './drafts';
import { clearAllOutboxes } from './outbox';

// Messaging state that lives outside the server (drafts, the unsent outbox, cached unread counts)
// must not outlast the signed-in user. The auth sign-out event (explicit sign-out or a rejected
// refresh) clears everything, and a successful /api/auth/login on the shared API client clears
// whatever belongs to any other user (e.g. a fresh page load straight at the login screen).

type Resetter = () => void;
const resetters = new Set<Resetter>();

/** Registers in-memory state to drop on sign-out (e.g. the unread-count store). */
export function onMessagingReset(fn: Resetter): () => void {
  resetters.add(fn);
  return () => { resetters.delete(fn); };
}

/**
 * Clears drafts and outboxes in this tab and resets in-memory messaging state.
 * `keepUser` keeps that user's drafts and outbox (a sign-in by the same user).
 */
export function resetMessagingLocalState(keepUser?: string | null): void {
  clearAllDrafts(keepUser);
  clearAllOutboxes(keepUser);
  resetters.forEach(fn => {
    try { fn(); } catch { /* one consumer must not block the others */ }
  });
}

let installed = false;

/** Idempotent; called when the communications API module loads. */
export function installSignOutReset(): void {
  if (installed) return;
  installed = true;
  onSignedOut(() => resetMessagingLocalState());
  api.interceptors.response.use(response => {
    const { config } = response;
    if (config?.method?.toLowerCase() === 'post' && /\/api\/auth\/login$/.test(config.url ?? '')) {
      const systemId = (response.data as any)?.user?.system_id;
      resetMessagingLocalState(typeof systemId === 'string' ? systemId : null);
    }
    return response;
  });
}
