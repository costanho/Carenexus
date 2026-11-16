import { useState } from 'react';
import { useAction, type ActionState } from '@/hooks/use-action';
import { useResource } from '@/hooks/use-resource';
import { listSessions, revokeOtherSessions, revokeSession } from '@/lib/account/api';
import { currentSessionId } from '@/lib/account/session';
import type { SessionResponse } from '@/lib/account/types';
import { isNotFoundError } from '@/lib/api/errors';

interface SessionsData {
  sessions:  SessionResponse[];
  /** This device's session id, or null when none is stored (then no row is marked and nothing can be kept). */
  currentId: string | null;
}

export interface SessionsState {
  /** Active sessions, this device first. Empty until loaded. */
  sessions: SessionResponse[];
  /** True when one of the rows is this device (`current: true`). */
  hasCurrent: boolean;
  loading:  boolean;
  error:    string | null;
  refetch:  () => void;
  /** Ends one other session. A session that had already ended counts as success. */
  revoke:   ActionState<[string], void>;
  /** The session id `revoke` is working on, for the row's spinner. */
  revokingId: string | null;
  /** Ends every session except this device's. Resolves with how many were ended. */
  revokeOthers: ActionState<[], number>;
}

/**
 * Active sign-in sessions of the account (GET /api/users/me/sessions?current=<session id>), with the two ways
 * of ending them. The current session is identified by the stored session UUID, which stays the same across
 * token refreshes.
 */
export function useSessions(): SessionsState {
  const list = useResource<SessionsData>(async () => {
    const currentId = await currentSessionId();
    return { sessions: await listSessions(currentId), currentId };
  }, [], { errorMessage: 'Your sessions could not be loaded.' });
  const { refetch, setData } = list;
  const [revokingId, setRevokingId] = useState<string | null>(null);

  const revoke = useAction(async (sessionId: string) => {
    setRevokingId(sessionId);
    try {
      await revokeSession(sessionId);
    } catch (e) {
      // 404 "Session not found": the session ended in the meantime, which is what was asked for.
      if (!isNotFoundError(e)) throw e;
    } finally {
      setRevokingId(null);
      refetch();
    }
    setData(prev => (prev ? { ...prev, sessions: prev.sessions.filter(s => s.sessionId !== sessionId) } : prev));
  }, { errorMessage: 'That session could not be signed out. Please try again.' });

  const revokeOthers = useAction(async () => {
    const keepId = await currentSessionId();
    if (!keepId) throw new Error('This device has no session id.');
    try {
      const { revokedCount } = await revokeOtherSessions(keepId);
      return revokedCount;
    } finally {
      refetch();
    }
  }, { errorMessage: 'The other sessions could not be signed out. Please try again.' });

  const sessions = list.data?.sessions ?? [];
  return {
    sessions,
    hasCurrent: sessions.some(s => s.current),
    loading: list.loading,
    error:   list.error,
    refetch,
    revoke,
    revokingId,
    revokeOthers,
  };
}
