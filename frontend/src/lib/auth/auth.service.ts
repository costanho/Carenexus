import { api } from './auth.interceptor';
import { clearAuth, getRefreshToken, getUser, saveTokens, saveUser } from './auth.storage';
import type { AuthResponse, AuthUser, LoginPayload, RegisterPayload } from './auth.types';

const ROLE_MAP: Record<string, string> = {
  proxy:     'CAREGIVER',
  caregiver: 'CAREGIVER',
  patient:   'PATIENT',
  doctor:    'DOCTOR',
};

function resolveRole(role: string): string {
  return ROLE_MAP[role.toLowerCase()] ?? role.toUpperCase();
}

export function parseApiError(err: unknown, fallback: string): string {
  if (!err || typeof err !== 'object') return fallback;
  const raw: string = (err as any)?.response?.data?.message ?? '';
  if (!raw) return fallback;
  const lower = raw.toLowerCase();
  // Clear, user-facing backend messages (e.g. pending clinician verification) pass through as-is.
  if (lower.includes('pending verification')) return raw;
  const duplicate = lower.includes('already registered') || lower.includes('already exists');
  if (duplicate && lower.includes('email')) return 'An account with this email already exists.';
  if (duplicate && lower.includes('phone')) return 'An account with this phone number already exists.';
  if (lower.includes('check constraint')) return 'Invalid role selected. Please try again.';
  if (lower.includes('invalid credentials')) return 'Incorrect email or password.';
  if (lower.includes('account is inactive')) return 'Your account has been deactivated. Please contact support.';
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

/** Revokes this device's refresh token server-side, then clears local auth. */
export async function logout(): Promise<void> {
  try {
    const refreshToken = await getRefreshToken();
    await api.post('/api/auth/logout', refreshToken ? { refreshToken } : {});
  } catch {
    // Server revocation is best-effort — always clear local tokens
  } finally {
    await clearAuth();
  }
}

/** The signed-in user as returned at login/refresh (there is no /api/auth/me endpoint). */
export async function getStoredUser(): Promise<AuthUser | null> {
  return getUser();
}
