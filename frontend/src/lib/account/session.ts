import { Platform } from 'react-native';
import { emitSignedOut } from '@/lib/auth/auth.events';
import { clearAuth, getSessionId } from '@/lib/auth/auth.storage';

// Session helpers shared by the account hooks.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The id of this sign-in session (the UUID the server returned as `tokens.sessionId`, stable across token
 * refreshes), or null when none is stored or the stored value is not a UUID. The account endpoints refuse a
 * malformed id, so a value that is not a UUID is treated as "unknown".
 */
export async function currentSessionId(): Promise<string | null> {
  const stored = (await getSessionId())?.trim();
  return stored && UUID.test(stored) ? stored : null;
}

/** The sign-in screen, as the root layout and the logout hook address it. */
export const SIGN_IN_ROUTE = Platform.OS === 'web'
  ? '/auth/login/web/login-web'
  : '/auth/login/mobile/login-mobile';

/**
 * Drops the stored session without calling the server. Used after the account was closed: the access token
 * is already refused and every refresh token is revoked, so there is nothing left to log out of.
 */
export async function endLocalSession(): Promise<void> {
  await clearAuth();
  emitSignedOut();
}
