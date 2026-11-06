import axios, { type AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { emitSessionExpired } from './auth.events';
import { clearAuth, getAccessToken, getRefreshToken, saveTokens, saveUser } from './auth.storage';
import type { AuthResponse } from './auth.types';

// Set EXPO_PUBLIC_API_URL per environment (see .env.example). When testing on a
// physical device, put your machine's LAN address in .env.development.local.
export const BASE_URL = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:9000';

// ── Axios instance shared across the whole app ────────────────────────────────
export const api: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000,
  headers: { 'Content-Type': 'application/json' },
});

// ── Request interceptor: attach Bearer token ──────────────────────────────────
api.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
  const token = await getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  } else {
    config.headers.delete('Authorization');
  }
  return config;
});

// ── Single-flight refresh ─────────────────────────────────────────────────────
// Refresh tokens are rotated by the server: each refresh returns a new refresh
// token and invalidates the old one, so concurrent 401s must share one refresh.
let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  const refreshToken = await getRefreshToken();
  if (!refreshToken) throw new Error('No refresh token');

  // Plain axios (not `api`) so this call never re-enters the interceptors.
  const { data } = await axios.post<AuthResponse>(
    `${BASE_URL}/api/auth/refresh`,
    { refreshToken },
    { timeout: 15_000, headers: { 'Content-Type': 'application/json' } },
  );

  if (!data?.tokens?.accessToken || !data.tokens.refreshToken) {
    throw new Error('Malformed refresh response');
  }
  await saveTokens(data.tokens);
  if (data.user) await saveUser(data.user);
  return data.tokens.accessToken;
}

function sharedRefresh(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = refreshAccessToken().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

// ── Response interceptor: handle 401, auto-refresh ───────────────────────────
api.interceptors.response.use(
  response => response,
  async (error: AxiosError) => {
    const original = error.config as RetriableConfig | undefined;

    // Only handle a 401 on a non-auth request that hasn't already been retried.
    if (
      !original ||
      error.response?.status !== 401 ||
      original._retry ||
      original.url?.includes('/api/auth/')
    ) {
      return Promise.reject(error);
    }

    original._retry = true;

    let token: string;
    try {
      // If another request already refreshed while this one was in flight,
      // just retry with the newer token instead of rotating again.
      const current = await getAccessToken();
      const sent    = String(original.headers.Authorization ?? '');
      token = current && sent !== `Bearer ${current}` ? current : await sharedRefresh();
    } catch (refreshError) {
      // End the session only when the server rejected the refresh token (4xx) or there
      // was nothing usable to refresh with. Network errors / 5xx keep the user signed in.
      const isHttpError = axios.isAxiosError(refreshError);
      const status      = isHttpError ? refreshError.response?.status : undefined;
      const rejected    = !isHttpError || (status !== undefined && status >= 400 && status < 500);
      if (rejected) {
        await clearAuth();
        emitSessionExpired();
        return Promise.reject(error);
      }
      return Promise.reject(refreshError);
    }

    original.headers.Authorization = `Bearer ${token}`;
    return api(original);
  },
);
