/** Roles a person can pick when signing in or registering. */
export type Role = 'patient' | 'doctor' | 'proxy';

/**
 * Every role the server can return for a signed-in user. Administrators have no sign-in tab: they use the
 * normal sign-in form and land on the admin area (web only).
 */
export type AppRole = Role | 'admin';

export interface LoginPayload {
  email:    string;
  password: string;
  role:     Role;
  /** Stored with the session and shown in the sessions list: 'Web', 'iOS' or 'Android'. The web sign-in sends 'Web'. */
  deviceType?: string;
  /** Short description of the device ("Chrome on Windows"), at most 255 characters and free of version numbers. */
  deviceInfo?: string;
}

export interface RegisterPayload {
  firstName: string;
  lastName:  string;
  email:     string;
  phone:     string;
  password:  string;
  role:      Role;
  // Clinician details, sent by the web form when the role is doctor (the server ignores them for other roles).
  licenseNo?:      string;
  specialization?: string;
  licensingBody?:  string;
  /** ISO 3166-1 alpha-2 country code of the licence. */
  licenseCountry?: string;
  // Versions of the terms and the privacy policy the person accepted, as returned by GET /api/legal/current.
  acceptedTermsVersion?:   string;
  acceptedPrivacyVersion?: string;
}

export interface AuthTokens {
  accessToken:  string;
  refreshToken: string;
  expiresIn:    number; // seconds
  /** Stable id of this sign-in session (unchanged when the refresh token rotates). Absent on older servers. */
  sessionId?:   string;
}

/**
 * Account state the server attaches to a session (sign-in, refresh and GET /api/users/me).
 * A flag that is absent means "not set".
 */
export interface SessionFlags {
  /** The password was issued by someone else (for example a caregiver) and must be changed before anything else. */
  mustChangePassword?:  boolean;
  emailVerified?:       boolean;
  /** A new version of the terms or privacy notice must be accepted before using the dashboard. */
  legalActionRequired?: boolean;
  mfaEnabled?:          boolean;
  [flag: string]:       unknown;
}

export interface AuthUser {
  user_id:   number;
  system_id: string;
  name:      string;
  email:     string;
  role:      AppRole;
}

export type MfaChallengeType = 'MFA' | 'MFA_SETUP';

/**
 * Sign-in answered with a second step instead of a session: `MFA` asks for a code of an enrolled authenticator,
 * `MFA_SETUP` asks the person to enrol first. The token is sent to the /api/auth/mfa endpoints; it is kept in
 * memory only, never in storage or in a URL.
 */
export interface LoginChallenge {
  type:             MfaChallengeType;
  token:            string;
  expiresInSeconds: number;
}

/**
 * Body of register, login, refresh and the MFA completion endpoints. A member that does not apply is null:
 * registration carries `user` and a `message` but no tokens; a sign-in that needs a second step carries only
 * `challenge`; a session carries `tokens`, `user` and `flags`.
 */
export interface AuthResponse {
  message?:   string | null;
  tokens:     AuthTokens | null;
  user:       AuthUser | null;
  flags?:     SessionFlags | null;
  challenge?: LoginChallenge | null;
}

/** An `AuthResponse` that issued a session. */
export interface AuthSession extends AuthResponse {
  tokens: AuthTokens;
  user:   AuthUser;
}

/** What the app keeps of a session once it is stored: the user and the account flags (never null). */
export interface SignedIn {
  user:  AuthUser;
  flags: SessionFlags;
}

/** True when a response issued a session (tokens and a user), false for a challenge or a registration. */
export function isAuthSession(response: AuthResponse | null | undefined): response is AuthSession {
  return !!response?.tokens?.accessToken && !!response.tokens.refreshToken && !!response.user;
}

/** One entry of GET /api/legal/current. */
export interface LegalDocumentSummary {
  docType:            'TERMS' | 'PRIVACY';
  /** Opaque version label, sent back unchanged when accepting. */
  version:            string;
  title:              string;
  /** UTC instant. */
  effectiveAt:        string;
  pendingLegalReview: boolean;
}

/** GET /api/legal/current: the current terms and privacy policy (without their text). */
export interface LegalCurrent {
  terms:   LegalDocumentSummary;
  privacy: LegalDocumentSummary;
}

/** Route each role maps to after login */
export const ROLE_HOME: Record<AppRole, string> = {
  patient: '/dashboard/patient',
  doctor:  '/dashboard/doctor',
  proxy:   '/dashboard/proxy',
  admin:   '/dashboard/admin',
};

/**
 * Account steps the server can require before any dashboard is shown (`SessionFlags.mustChangePassword` and
 * `SessionFlags.legalActionRequired`). The dashboard guard and the sign-in screen send the person to these
 * routes, and the screens behind them clear their flag with `updateSessionFlags` when the step is done.
 * Kept here so the guard and the screens cannot drift apart.
 */
export const CHANGE_PASSWORD_ROUTE = '/auth/change-password';
export const ACCEPT_TERMS_ROUTE    = '/auth/accept-terms';

/** Public pages the sign-in and registration screens link to. */
export const FORGOT_PASSWORD_ROUTE = '/auth/forgot-password';
export const LEGAL_TERMS_ROUTE     = '/legal/terms';
export const LEGAL_PRIVACY_ROUTE   = '/legal/privacy';

/** The step a session still has to complete before the dashboard, or null when there is none. */
export function requiredAccountStep(flags: SessionFlags | null | undefined): string | null {
  if (flags?.mustChangePassword === true)  return CHANGE_PASSWORD_ROUTE;
  if (flags?.legalActionRequired === true) return ACCEPT_TERMS_ROUTE;
  return null;
}

/** Type guard for a role string that came from storage, a URL or the server. */
export function isAppRole(value: unknown): value is AppRole {
  return value === 'patient' || value === 'doctor' || value === 'proxy' || value === 'admin';
}
