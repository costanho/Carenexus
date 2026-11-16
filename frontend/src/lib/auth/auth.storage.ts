import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import type { AuthTokens, AuthUser, SessionFlags } from './auth.types';

const KEYS = {
  ACCESS_TOKEN:  'carenexus_access_token',
  REFRESH_TOKEN: 'carenexus_refresh_token',
  USER:          'carenexus_user',
  SESSION_ID:    'carenexus_session_id',
  SESSION_FLAGS: 'carenexus_session_flags',
} as const;

// ── Platform-aware read / write / delete ─────────────────────────────────────
// expo-secure-store works on iOS & Android but is unavailable on web.
// On web we fall back to sessionStorage (cleared when tab closes).

async function setItem(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    sessionStorage.setItem(key, value);
  } else {
    await SecureStore.setItemAsync(key, value);
  }
}

async function getItem(key: string): Promise<string | null> {
  if (Platform.OS === 'web') {
    return sessionStorage.getItem(key);
  }
  return SecureStore.getItemAsync(key);
}

async function deleteItem(key: string): Promise<void> {
  if (Platform.OS === 'web') {
    sessionStorage.removeItem(key);
  } else {
    await SecureStore.deleteItemAsync(key);
  }
}

// ── Public API ────────────────────────────────────────────────────────────────

export async function saveTokens(tokens: AuthTokens): Promise<void> {
  await Promise.all([
    setItem(KEYS.ACCESS_TOKEN,  tokens.accessToken),
    setItem(KEYS.REFRESH_TOKEN, tokens.refreshToken),
    // The session id stays the same across refresh-token rotation; a response without one keeps the stored id.
    tokens.sessionId ? setItem(KEYS.SESSION_ID, String(tokens.sessionId)) : Promise.resolve(),
  ]);
}

/** Id of this sign-in session (used to mark "this device" in the sessions list); null when the server sent none. */
export async function getSessionId(): Promise<string | null> {
  return getItem(KEYS.SESSION_ID);
}

/**
 * Stores the account flags returned with a session (sign-in, refresh, GET /api/users/me). Passing nothing keeps
 * what is stored, so callers can forward `response.flags` without checking it.
 */
export async function saveSessionFlags(flags: SessionFlags | null | undefined): Promise<void> {
  if (!flags || typeof flags !== 'object') return;
  await setItem(KEYS.SESSION_FLAGS, JSON.stringify(flags));
}

/** Account flags of the current session; an empty object when none were stored. */
export async function getSessionFlags(): Promise<SessionFlags> {
  const raw = await getItem(KEYS.SESSION_FLAGS);
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as SessionFlags) : {};
  } catch {
    return {};
  }
}

export async function saveUser(user: AuthUser): Promise<void> {
  await setItem(KEYS.USER, JSON.stringify(user));
}

export async function getAccessToken(): Promise<string | null> {
  return getItem(KEYS.ACCESS_TOKEN);
}

export async function getRefreshToken(): Promise<string | null> {
  return getItem(KEYS.REFRESH_TOKEN);
}

export async function getUser(): Promise<AuthUser | null> {
  const raw = await getItem(KEYS.USER);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthUser;
  } catch {
    return null;
  }
}

/**
 * Changes some flags of the current session and keeps the rest, for example
 * `updateSessionFlags({ mustChangePassword: false })` once the password was changed.
 */
export async function updateSessionFlags(patch: SessionFlags): Promise<SessionFlags> {
  const next = { ...(await getSessionFlags()), ...patch };
  await setItem(KEYS.SESSION_FLAGS, JSON.stringify(next));
  return next;
}

export async function clearAuth(): Promise<void> {
  await Promise.all([
    deleteItem(KEYS.ACCESS_TOKEN),
    deleteItem(KEYS.REFRESH_TOKEN),
    deleteItem(KEYS.USER),
    deleteItem(KEYS.SESSION_ID),
    deleteItem(KEYS.SESSION_FLAGS),
  ]);
}

export async function isAuthenticated(): Promise<boolean> {
  const token = await getAccessToken();
  return token !== null && token.length > 0;
}
