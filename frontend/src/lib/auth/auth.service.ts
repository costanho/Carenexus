import { api } from './auth.interceptor';
import { clearAuth, saveTokens, saveUser } from './auth.storage';
import type { AuthResponse, LoginPayload, RegisterPayload } from './auth.types';

export async function register(payload: RegisterPayload): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/api/auth/register', payload);
  await saveTokens(data.tokens);
  await saveUser(data.user);
  return data;
}

export async function login(payload: LoginPayload): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/api/auth/login', payload);
  await saveTokens(data.tokens);
  await saveUser(data.user);
  return data;
}

export async function logout(): Promise<void> {
  try {
    await api.post('/api/auth/logout');
  } finally {
    await clearAuth();
  }
}

export async function fetchCurrentUser(): Promise<AuthResponse['user']> {
  const { data } = await api.get<AuthResponse['user']>('/api/auth/me');
  return data;
}
