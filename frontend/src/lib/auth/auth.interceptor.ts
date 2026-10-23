import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { Platform } from 'react-native';
import { clearAuth, getAccessToken, getRefreshToken, saveTokens } from './auth.storage';
import type { AuthTokens } from './auth.types';

// Web always uses localhost (browser runs on the same Mac as the server).
// Mobile uses EXPO_PUBLIC_API_URL (set to the Mac's LAN IP in .env.development)
// so a physical device on the same network can reach the server.
const BASE_URL =
  Platform.OS === 'web'
    ? 'http://localhost:9000'
    : (process.env.EXPO_PUBLIC_API_URL ?? 'http://10.0.0.47:9000');

console.log('API_BASE_URL:', BASE_URL);

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
  console.log('API Request:', config.method?.toUpperCase(), config.url, '- hasToken:', !!token);
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
  response => {
    console.log('API Response:', response.status, response.config.url);
    console.log('Response Data:', JSON.stringify(response.data, null, 2));
    return response;
  },
  async (error) => {
    const isLogout = (error as any)?.config?.url?.includes('/api/auth/logout');
    if ((error as any)?.response && !isLogout) {
      console.error('API Error:', (error as any).response.status, (error as any).config?.url, (error as any).response.data);
    }
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
