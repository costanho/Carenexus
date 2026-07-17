import * as SecureStore from 'expo-secure-store';
import { Platform } from 'react-native';
import type { AuthTokens, AuthUser } from './auth.types';

const KEYS = {
  ACCESS_TOKEN:  'carenexus_access_token',
  REFRESH_TOKEN: 'carenexus_refresh_token',
  USER:          'carenexus_user',
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
  ]);
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

export async function clearAuth(): Promise<void> {
  await Promise.all([
    deleteItem(KEYS.ACCESS_TOKEN),
    deleteItem(KEYS.REFRESH_TOKEN),
    deleteItem(KEYS.USER),
  ]);
}

export async function isAuthenticated(): Promise<boolean> {
  const token = await getAccessToken();
  return token !== null && token.length > 0;
}
