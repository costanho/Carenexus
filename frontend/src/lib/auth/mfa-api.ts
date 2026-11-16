import { api } from './auth.interceptor';
import type { AuthResponse } from './auth.types';

// Second step of sign-in (be-account-mfa): the public /api/auth/mfa endpoints, authenticated by the challenge
// token that POST /api/auth/login returned. These functions only talk to the API; storing the session they
// return is done by completeMfaLogin / completeMfaSetup in auth.service.ts.
//
// Device type and device info are not sent here: the server copies them from the login request that created the
// challenge. The 401 answers of these endpoints are never retried by the interceptor (the URL contains /api/auth/).

/** Secret of a pending enrolment: `otpauthUri` for the QR code, `secret` (32 Base32 characters) for manual entry. */
export interface MfaSetup {
  secret:     string;
  otpauthUri: string;
}

/** Result of a forced enrolment: the one-time recovery codes (shown once) and the signed-in session. */
export interface MfaSetupConfirmResult {
  recoveryCodes: string[];
  session:       AuthResponse;
}

/** Either a 6-digit authenticator code or a recovery code, never both. */
export type MfaCredential = { code: string } | { recoveryCode: string };

/** Error codes of the MFA endpoints the sign-in screen reacts to. */
export const MFA_CODE_INVALID      = 'MFA_CODE_INVALID';
export const MFA_CHALLENGE_INVALID = 'MFA_CHALLENGE_INVALID';

/** POST /api/auth/mfa/verify: completes a sign-in for an account with two-factor authentication turned on. */
export async function verifyMfaChallenge(challengeToken: string, credential: MfaCredential): Promise<AuthResponse> {
  const body = 'code' in credential
    ? { challengeToken, code: credential.code }
    : { challengeToken, recoveryCode: credential.recoveryCode };
  const { data } = await api.post<AuthResponse>('/api/auth/mfa/verify', body);
  return data;
}

/**
 * POST /api/auth/mfa/setup/start: the secret to add to an authenticator app during a forced enrolment.
 * Repeating the call for the same challenge returns the same secret, so it is safe to call again on retry.
 */
export async function startMfaSetup(challengeToken: string): Promise<MfaSetup> {
  const { data } = await api.post<MfaSetup>('/api/auth/mfa/setup/start', { challengeToken });
  return data;
}

/** POST /api/auth/mfa/setup/confirm: turns two-factor authentication on and signs the person in. */
export async function confirmMfaSetup(challengeToken: string, code: string): Promise<MfaSetupConfirmResult> {
  const { data } = await api.post<MfaSetupConfirmResult>('/api/auth/mfa/setup/confirm', { challengeToken, code });
  return data;
}

/** The Base32 secret in groups of four ("JBSW Y3DP ..."), which is easier to type into an authenticator app. */
export function formatMfaSecret(secret: string): string {
  return (secret.replace(/\s+/g, '').match(/.{1,4}/g) ?? []).join(' ');
}

/** A QR code as an image: a data URL and the side length in pixels it was drawn at. */
export interface QrImage {
  uri:  string;
  size: number;
}

const QR_CELL_PX    = 4;
const QR_QUIET_ZONE = 4; // modules of white border the QR standard asks for

type QrFactory = typeof import('qrcode-generator');

/**
 * Draws `text` (an otpauth URI) as a QR code image. The QR package is loaded only when a code is needed, so it
 * is not part of the sign-in screen's own code (auth.service.ts, which every dashboard loads, imports this
 * file). Resolves to null when the package cannot be loaded or the code cannot be generated; the screen then
 * still offers the secret for manual entry.
 */
export async function qrImage(text: string): Promise<QrImage | null> {
  if (!text) return null;
  try {
    const loaded = await import('qrcode-generator');
    const qrcode: QrFactory = (loaded as { default?: QrFactory }).default ?? (loaded as unknown as QrFactory);
    const qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    const margin = QR_CELL_PX * QR_QUIET_ZONE;
    const uri = qr.createDataURL(QR_CELL_PX, margin);
    if (!uri) return null;
    return { uri, size: qr.getModuleCount() * QR_CELL_PX + margin * 2 };
  } catch {
    return null;
  }
}

/** Seconds left of a challenge that ends at `expiresAt` (epoch milliseconds); never negative. */
export function challengeSecondsLeft(expiresAt: number, now: number = Date.now()): number {
  return Math.max(0, Math.ceil((expiresAt - now) / 1000));
}

/** "4:05" for 245 seconds. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
