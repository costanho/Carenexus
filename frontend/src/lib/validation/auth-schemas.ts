import { z } from 'zod';

// Sign-in form of the native app. Its behaviour is unchanged; the web screen uses webLoginSchema below.
export const loginSchema = z.object({
  email:    z.string().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required').min(8, 'Minimum 8 characters'),
});

export type LoginFormData = z.infer<typeof loginSchema>;

// ── Web sign-in (mirrors LoginRequest of POST /api/auth/login) ────────────────

/** Email format the API accepts (same expression as the server's). */
export const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const EMAIL_MAX_LENGTH = 150;
/** Longest password the sign-in endpoint reads (longer input is a validation error on the server). */
export const LOGIN_PASSWORD_MAX_LENGTH = 200;

/** An email address as the API stores it: trimmed and lower-cased, at most 150 characters. */
export const emailField = z.string()
  .trim()
  .min(1, 'Email is required')
  .max(EMAIL_MAX_LENGTH, `Email must be at most ${EMAIL_MAX_LENGTH} characters`)
  .regex(EMAIL_REGEX, 'Enter a valid email address')
  .transform(value => value.toLowerCase());

export const webLoginSchema = z.object({
  email:    emailField,
  // The password is sent exactly as typed (never trimmed). Any non-empty password may be a valid one, so the
  // sign-in form does not repeat the rules that apply when a password is chosen.
  password: z.string()
    .min(1, 'Password is required')
    .max(LOGIN_PASSWORD_MAX_LENGTH, `Password must be at most ${LOGIN_PASSWORD_MAX_LENGTH} characters`),
});

export type WebLoginFormInput = z.input<typeof webLoginSchema>;
export type WebLoginFormData  = z.output<typeof webLoginSchema>;

// ── Second step of sign-in (mirrors the /api/auth/mfa request bodies) ─────────

export const MFA_CODE_REGEX      = /^\d{6}$/;
export const RECOVERY_CODE_REGEX = /^[A-Za-z0-9]{5}-?[A-Za-z0-9]{5}$/;

/** The code as the API expects it: the spaces an authenticator app shows between digit groups are removed. */
export function normalizeMfaCode(value: string): string {
  return value.replace(/\s+/g, '');
}

/** A recovery code without surrounding or inner spaces; the server ignores case and the hyphen. */
export function normalizeRecoveryCode(value: string): string {
  return value.replace(/\s+/g, '').toUpperCase();
}

/** A 6-digit code from an authenticator app. Always a string: leading zeros matter. */
export const mfaCodeSchema = z.string()
  .transform(normalizeMfaCode)
  .pipe(z.string().min(1, 'Enter the 6-digit code').regex(MFA_CODE_REGEX, 'The code must be 6 digits'));

/** A one-time recovery code such as ABCDE-FGHJK. */
export const recoveryCodeSchema = z.string()
  .transform(normalizeRecoveryCode)
  .pipe(z.string().min(1, 'Enter a recovery code').regex(RECOVERY_CODE_REGEX, 'Enter a recovery code such as ABCDE-FGHJK'));

/** Validates one code field; returns the value to send, or the message to show under the field. */
export function parseCode(
  schema: typeof mfaCodeSchema | typeof recoveryCodeSchema,
  value: string,
): { ok: true; value: string } | { ok: false; message: string } {
  const result = schema.safeParse(value);
  if (result.success) return { ok: true, value: result.data };
  return { ok: false, message: result.error.issues[0]?.message ?? 'Enter a valid code' };
}
