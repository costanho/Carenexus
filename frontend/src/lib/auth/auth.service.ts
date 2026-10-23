import { api } from './auth.interceptor';
import { clearAuth, saveTokens, saveUser } from './auth.storage';
import type { AuthResponse, LoginPayload, RegisterPayload } from './auth.types';

const ROLE_MAP: Record<string, string> = {
  proxy:     'CAREGIVER',
  caregiver: 'CAREGIVER',
  patient:   'PATIENT',
  doctor:    'DOCTOR',
  admin:     'ADMIN',
};

function resolveRole(role: string): string {
  return ROLE_MAP[role.toLowerCase()] ?? role.toUpperCase();
}

export function parseApiError(err: unknown, fallback: string): string {
  if (!err || typeof err !== 'object') return fallback;
  const raw: string = (err as any)?.response?.data?.message ?? '';
  if (!raw) return fallback;
  const lower = raw.toLowerCase();
  if (lower.includes('email')) return 'An account with this email already exists.';
  if (lower.includes('phone')) return 'An account with this phone number already exists.';
  if (lower.includes('role') || lower.includes('check constraint')) return 'Invalid role selected. Please try again.';
  if (lower.includes('password')) return 'Password does not meet requirements.';
  if (lower.includes('invalid credentials')) return 'Incorrect email or password.';
  if (lower.includes('inactive')) return 'Your account has been deactivated. Please contact support.';
  if (lower.includes('refresh token')) return 'Your session has expired. Please log in again.';
  if (raw.length > 120) return fallback;
  return raw;
}

export async function register(payload: RegisterPayload): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/api/auth/register', {
    ...payload,
    role: resolveRole(payload.role),
  });
  if (data.tokens) {
    await saveTokens(data.tokens);
    await saveUser(data.user);
  }
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
  } catch {
    // Server revocation is best-effort — always clear local tokens
  } finally {
    await clearAuth();
  }
}

export async function fetchCurrentUser(): Promise<AuthResponse['user']> {
  const { data } = await api.get<AuthResponse['user']>('/api/auth/me');
  return data;
}
