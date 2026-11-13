import { Platform } from 'react-native';
import { apiError, rateLimitMessage, type ApiError } from '@/lib/api/errors';
import { api } from './auth.interceptor';
import { emitSignedOut } from './auth.events';
import { clearAuth, getRefreshToken, getUser, saveSessionFlags, saveTokens, saveUser } from './auth.storage';
import {
  confirmMfaSetup, MFA_CHALLENGE_INVALID, MFA_CODE_INVALID, verifyMfaChallenge, type MfaCredential,
} from './mfa-api';
import {
  isAuthSession,
  type AuthResponse, type AuthUser, type LegalCurrent, type LoginPayload, type RegisterPayload, type SessionFlags,
  type SignedIn,
} from './auth.types';

const ROLE_MAP: Record<string, string> = {
  proxy:     'CAREGIVER',
  caregiver: 'CAREGIVER',
  patient:   'PATIENT',
  doctor:    'DOCTOR',
};

function resolveRole(role: string): string {
  return ROLE_MAP[role.toLowerCase()] ?? role.toUpperCase();
}

const DUPLICATE_EMAIL_MESSAGE   = 'An account with this email already exists.';
const DUPLICATE_PHONE_MESSAGE   = 'An account with this phone number already exists.';
const DUPLICATE_LICENCE_MESSAGE = 'A clinician account with this licence number already exists.';

/** Which registration field a "... already registered" answer of the server is about, or null. */
function duplicateField(message: string): 'email' | 'phone' | 'licenseNo' | null {
  const lower = message.toLowerCase();
  if (!lower.includes('already registered') && !lower.includes('already exists')) return null;
  if (lower.includes('email')) return 'email';
  if (lower.includes('phone')) return 'phone';
  if (lower.includes('licence') || lower.includes('license')) return 'licenseNo';
  return null;
}

const DUPLICATE_MESSAGES = {
  email:     DUPLICATE_EMAIL_MESSAGE,
  phone:     DUPLICATE_PHONE_MESSAGE,
  licenseNo: DUPLICATE_LICENCE_MESSAGE,
} as const;

export function parseApiError(err: unknown, fallback: string): string {
  if (!err || typeof err !== 'object') return fallback;
  const response = (err as any)?.response;
  // The wait comes from the Retry-After header; the 429 text of the server is not meant to be shown.
  if (response?.status === 429) return apiError(err, fallback).message;
  const raw: string = response?.data?.message ?? '';
  if (!raw) return fallback;
  const lower = raw.toLowerCase();
  // Clear, user-facing backend messages (e.g. pending clinician verification) pass through as-is.
  if (lower.includes('pending verification')) return raw;
  const duplicate = duplicateField(raw);
  if (duplicate) return DUPLICATE_MESSAGES[duplicate];
  if (lower.includes('check constraint')) return 'Invalid role selected. Please try again.';
  if (lower.includes('invalid credentials')) return 'Incorrect email or password.';
  if (lower.includes('account is inactive')) return 'Your account has been deactivated. Please contact support.';
  if (lower.includes('refresh token')) return 'Your session has expired. Please log in again.';
  if (raw.length > 120) return fallback;
  return raw;
}

// ── Session storage ───────────────────────────────────────────────────────────

/**
 * Stores a response that issued a session: tokens (with the session id), user and account flags. A session
 * without flags clears whatever an earlier session left behind. Throws when the response carries no session,
 * so a challenge or a malformed answer can never be saved as one.
 */
export async function storeSession(response: AuthResponse): Promise<SignedIn> {
  if (!isAuthSession(response)) throw new Error('The sign-in response carried no session');
  const flags = response.flags && typeof response.flags === 'object' ? response.flags : {};
  await saveTokens(response.tokens);
  await saveUser(response.user);
  await saveSessionFlags(flags);
  return { user: response.user, flags };
}

// GET /api/users/me returns the current account flags beside the user. Whoever asks for it (useCurrentUser
// today), the stored flags follow, the same way a token refresh updates them in auth.interceptor.ts.
function isCurrentUserRequest(method: string | undefined, url: string | undefined): boolean {
  if ((method ?? 'get').toLowerCase() !== 'get' || !url) return false;
  return url.split('?')[0].replace(/\/+$/, '').endsWith('/api/users/me');
}

api.interceptors.response.use(async response => {
  if (isCurrentUserRequest(response.config?.method, response.config?.url)) {
    try {
      await saveSessionFlags((response.data as { flags?: SessionFlags | null } | null)?.flags);
    } catch {
      // storage unavailable: the flags of the last sign-in or refresh stay in place
    }
  }
  return response;
});

// ── Registration ──────────────────────────────────────────────────────────────

function clean(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

export async function register(payload: RegisterPayload): Promise<AuthResponse> {
  const isDoctor = resolveRole(payload.role) === 'DOCTOR';
  const { data } = await api.post<AuthResponse>('/api/auth/register', {
    firstName: payload.firstName,
    lastName:  payload.lastName,
    email:     payload.email,
    phone:     payload.phone,
    password:  payload.password,
    role:      resolveRole(payload.role),
    // Clinician details only travel with a doctor registration; blank optional values are left out.
    licenseNo:      isDoctor ? clean(payload.licenseNo) : undefined,
    specialization: isDoctor ? clean(payload.specialization) : undefined,
    licensingBody:  isDoctor ? clean(payload.licensingBody) : undefined,
    licenseCountry: isDoctor ? clean(payload.licenseCountry)?.toUpperCase() : undefined,
    acceptedTermsVersion:   clean(payload.acceptedTermsVersion),
    acceptedPrivacyVersion: clean(payload.acceptedPrivacyVersion),
  });
  // Registration issues no session today; a server that does is still honoured.
  if (isAuthSession(data)) await storeSession(data);
  return data;
}

/** What went wrong with a registration, in the terms the form needs. */
export interface RegisterFailure extends ApiError {
  /** The field a duplicate (409) is about; its message is already the user-facing sentence. */
  duplicateField?: 'email' | 'phone' | 'licenseNo';
  /** The accepted terms or privacy version is no longer the current one: reload the versions and ask again. */
  legalChanged: boolean;
}

const REGISTER_FALLBACK = 'Registration failed. Please try again.';

/** Turns a failed POST /api/auth/register into the message and field the registration form shows. */
export function registerFailure(err: unknown): RegisterFailure {
  const failure = apiError(err, REGISTER_FALLBACK);
  const legalChanged = failure.code === 'LEGAL_ACCEPTANCE_REQUIRED';
  if (failure.status === 409 || failure.status === 400) {
    const field = duplicateField(failure.message);
    if (field) return { ...failure, message: DUPLICATE_MESSAGES[field], duplicateField: field, legalChanged };
  }
  if (failure.status >= 500 || failure.message.length > 160) {
    return { ...failure, message: REGISTER_FALLBACK, legalChanged };
  }
  return { ...failure, legalChanged };
}

/** GET /api/legal/current: the versions of the terms and the privacy policy a new account accepts. */
export async function getCurrentLegal(): Promise<LegalCurrent> {
  const { data } = await api.get<LegalCurrent>('/api/legal/current');
  if (!data?.terms?.version || !data?.privacy?.version) throw new Error('The current terms could not be read');
  return data;
}

/**
 * POST /api/auth/email/resend: asks for a new verification link. The answer is the same whether or not the
 * address belongs to an account, so the returned sentence is safe to show as it is.
 */
export async function resendVerificationEmail(email: string): Promise<string> {
  const { data } = await api.post<{ message?: string }>('/api/auth/email/resend', { email: email.trim().toLowerCase() });
  return data?.message?.trim()
    || 'If that address belongs to an account that still needs verification, we have sent a new link.';
}

// ── Sign-in ───────────────────────────────────────────────────────────────────

/**
 * Signs in. The answer is either a session, which is stored before this returns (`tokens` and `user` set), or a
 * challenge (`challenge` set, `tokens` and `user` null) that completeMfaLogin / completeMfaSetup finish.
 * Nothing is stored for a challenge.
 */
export async function login(payload: LoginPayload): Promise<AuthResponse> {
  const { data } = await api.post<AuthResponse>('/api/auth/login', payload);
  if (data?.challenge) return { ...data, tokens: null, user: null };
  await storeSession(data);
  return data;
}

/** Second step of sign-in with an authenticator or recovery code; stores the session it returns. */
export async function completeMfaLogin(challengeToken: string, credential: MfaCredential): Promise<SignedIn> {
  return storeSession(await verifyMfaChallenge(challengeToken, credential));
}

/**
 * Finishes a forced enrolment: two-factor authentication is on, the session is stored, and the recovery codes
 * (which the server can never show again) are returned for the screen to display.
 */
export async function completeMfaSetup(
  challengeToken: string,
  code: string,
): Promise<{ recoveryCodes: string[]; session: SignedIn }> {
  const result = await confirmMfaSetup(challengeToken, code);
  const session = await storeSession(result.session);
  return { recoveryCodes: Array.isArray(result.recoveryCodes) ? result.recoveryCodes : [], session };
}

function matchFirst(text: string, table: readonly (readonly [RegExp, string])[]): string | null {
  for (const [pattern, name] of table) if (pattern.test(text)) return name;
  return null;
}

// Order matters: Edge, Opera and Samsung Internet also announce Chrome, and Chrome also announces Safari.
const BROWSERS = [
  [/Edg(e|A|iOS)?\//, 'Edge'],
  [/OPR\/|Opera/, 'Opera'],
  [/SamsungBrowser/, 'Samsung Internet'],
  [/Firefox\/|FxiOS/, 'Firefox'],
  [/Chrome\/|CriOS|Chromium/, 'Chrome'],
  [/Safari\//, 'Safari'],
] as const;

const SYSTEMS = [
  [/Windows/, 'Windows'],
  [/Android/, 'Android'],
  [/iPhone|iPad|iPod/, 'iOS'],
  [/CrOS/, 'ChromeOS'],
  [/Mac OS X|Macintosh/, 'macOS'],
  [/Linux|X11/, 'Linux'],
] as const;

/**
 * Device fields the web sign-in sends: type "Web" and a short description such as "Chrome on Windows".
 * The description has no version numbers on purpose: the server compares it with earlier sign-ins to decide
 * whether to send a new-device alert, and a browser update must not look like a new device.
 * Empty on native, whose sign-in payload stays as it is.
 */
export function describeWebDevice(): Pick<LoginPayload, 'deviceType' | 'deviceInfo'> {
  if (Platform.OS !== 'web' || typeof navigator === 'undefined') return {};
  const agent   = typeof navigator.userAgent === 'string' ? navigator.userAgent : '';
  const browser = matchFirst(agent, BROWSERS);
  const system  = matchFirst(agent, SYSTEMS);
  const info = browser && system ? `${browser} on ${system}` : browser ?? system ?? 'Web browser';
  return { deviceType: 'Web', deviceInfo: info.slice(0, 255) };
}

export type SignInFailureKind =
  | 'invalid-credentials'
  | 'email-not-verified'
  | 'pending-verification'
  | 'rejected'
  | 'closed'
  | 'inactive'
  | 'rate-limited'
  | 'validation'
  /** The 6-digit or recovery code was wrong or already used: stay on the step and ask again. */
  | 'code-invalid'
  /** The second step expired, was used up or locked: start again with email and password. */
  | 'challenge-invalid'
  | 'network'
  | 'other';

export interface SignInFailure extends ApiError {
  kind: SignInFailureKind;
}

const SIGN_IN_FALLBACK = 'Sign-in failed. Please try again.';

const ACCOUNT_STATE: Record<string, { kind: SignInFailureKind; message: string }> = {
  EMAIL_NOT_VERIFIED: {
    kind: 'email-not-verified',
    message: 'Please verify your email address before signing in.',
  },
  ACCOUNT_PENDING_VERIFICATION: {
    kind: 'pending-verification',
    message: 'Your clinician account is pending verification. You can sign in once an administrator approves it.',
  },
  ACCOUNT_REJECTED: {
    kind: 'rejected',
    message: 'Your clinician registration was not approved. Please contact support.',
  },
  ACCOUNT_CLOSED: {
    kind: 'closed',
    message: 'This account has been closed. Please contact support.',
  },
};

/**
 * Turns a failed sign-in request (POST /api/auth/login or one of the /api/auth/mfa endpoints) into what the
 * screen shows. The decision is made on the status and the error code, never on the wording of the message.
 */
export function signInFailure(err: unknown): SignInFailure {
  const failure = apiError(err, SIGN_IN_FALLBACK);
  const of = (kind: SignInFailureKind, message: string = failure.message): SignInFailure => ({ ...failure, kind, message });

  if (failure.status === 0) {
    // No HTTP response. A request that got no answer is a connection problem; anything else failed in the app.
    return of(failure.message === SIGN_IN_FALLBACK ? 'other' : 'network');
  }
  if (failure.status === 429) return of('rate-limited', rateLimitMessage(failure.retryAfterSeconds));

  const state = failure.code ? ACCOUNT_STATE[failure.code] : undefined;
  if (state) return of(state.kind, failure.message === SIGN_IN_FALLBACK ? state.message : failure.message);

  if (failure.code === MFA_CODE_INVALID) return of('code-invalid');
  if (failure.code === MFA_CHALLENGE_INVALID) return of('challenge-invalid');
  if (failure.code === 'VALIDATION_FAILED') return of('validation');

  if (failure.status === 401) {
    return of('invalid-credentials', failure.message === SIGN_IN_FALLBACK ? 'Invalid email or password' : failure.message);
  }
  // An account that an administrator deactivated (or a suspended clinician) answers 400 without a code.
  if (failure.status === 400 && /account is inactive/i.test(failure.message)) {
    return of('inactive', 'Your account has been deactivated. Please contact support.');
  }
  if (failure.status >= 500 || failure.message.length > 160) return of('other', SIGN_IN_FALLBACK);
  return of('other');
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
    emitSignedOut();
  }
}

/** The signed-in user as returned at login/refresh (there is no /api/auth/me endpoint). */
export async function getStoredUser(): Promise<AuthUser | null> {
  return getUser();
}
