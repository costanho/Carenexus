import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { clearAuth, getAccessToken, getRefreshToken, saveTokens } from './auth.storage';
import type { AuthTokens } from './auth.types';

const BASE_URL = process.env.EXPO_PUBLIC_API_URL ?? 'http://localhost:8080';

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
  }
  return config;
});

// ── Refresh token queue (prevents concurrent refresh calls) ───────────────────
let isRefreshing = false;
let pendingQueue: Array<{
  resolve: (token: string) => void;
  reject:  (err: unknown)  => void;
}> = [];

function drainQueue(token: string | null, error: unknown = null) {
  pendingQueue.forEach(p => (token ? p.resolve(token) : p.reject(error)));
  pendingQueue = [];
}

// ── Response interceptor: handle 401, auto-refresh ───────────────────────────
api.interceptors.response.use(
  response => response,
  async error => {
    const original = error.config;

    // Only handle 401 that hasn't already been retried
    if (error.response?.status !== 401 || original._retry) {
      return Promise.reject(error);
    }

    if (isRefreshing) {
      // Queue this request until the ongoing refresh completes
      return new Promise((resolve, reject) => {
        pendingQueue.push({
          resolve: (token) => {
            original.headers.Authorization = `Bearer ${token}`;
            resolve(api(original));
          },
          reject,
        });
      });
    }

    original._retry  = true;
    isRefreshing     = true;

    try {
      const refreshToken = await getRefreshToken();
      if (!refreshToken) throw new Error('No refresh token');

      const { data } = await axios.post<AuthTokens>(
        `${BASE_URL}/api/auth/refresh`,
        { refreshToken },
      );

      await saveTokens(data);
      api.defaults.headers.common.Authorization = `Bearer ${data.accessToken}`;
      drainQueue(data.accessToken);

      original.headers.Authorization = `Bearer ${data.accessToken}`;
      return api(original);
    } catch (refreshError) {
      drainQueue(null, refreshError);
      await clearAuth();
      // Signal the app to redirect to login — handled by the router guard
      return Promise.reject(refreshError);
    } finally {
      isRefreshing = false;
    }
  },
);
