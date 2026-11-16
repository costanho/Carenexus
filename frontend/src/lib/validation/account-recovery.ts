import { z } from 'zod';

// Form rules of the account recovery flows (forgot and reset password, forced password change, resending a
// verification email). They mirror the backend constraints so a value the form accepts is not refused by the
// server for its shape, and they use the server's wording where the server has one.

// ── Email ─────────────────────────────────────────────────────────────────────

/** The server's email rule (the same one registration uses), applied after trimming. */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const EMAIL_MAX_LENGTH = 150;

/** An email address as the account endpoints expect it: trimmed and lower-case. */
export const accountEmailSchema = z.string()
  .trim()
  .min(1, 'Email is required')
  .max(EMAIL_MAX_LENGTH, `Email must be at most ${EMAIL_MAX_LENGTH} characters`)
  .regex(EMAIL_PATTERN, 'Please enter a valid email address')
  .toLowerCase();

// ── Password policy ───────────────────────────────────────────────────────────

export const PASSWORD_MIN_LENGTH = 8;
/** bcrypt reads at most 72 bytes, so the limit is counted in UTF-8 bytes, not characters. */
export const PASSWORD_MAX_BYTES = 72;
export const CURRENT_PASSWORD_MAX_LENGTH = 128;

export const PASSWORD_POLICY_MESSAGE =
  'Password must be at least 8 characters and contain at least one letter and one number';
export const PASSWORD_TOO_LONG_MESSAGE = 'Password must be at most 72 characters';
export const PASSWORD_UNCHANGED_MESSAGE = 'New password must be different from your current password';
export const CURRENT_PASSWORD_INCORRECT_MESSAGE = 'Current password is incorrect';
export const PASSWORDS_DO_NOT_MATCH_MESSAGE = 'Passwords do not match';

/** Length of `value` in UTF-8 bytes (TextEncoder is not available on every native runtime). */
export function utf8ByteLength(value: string): number {
  let bytes = 0;
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return bytes;
}

const hasLetter = (value: string) => /\p{L}/u.test(value);
const hasDigit  = (value: string) => /\d/.test(value);

/** True when `value` satisfies the whole password policy. Passwords are never trimmed. */
export function meetsPasswordPolicy(value: string): boolean {
  return value.length >= PASSWORD_MIN_LENGTH && hasLetter(value) && hasDigit(value)
    && utf8ByteLength(value) <= PASSWORD_MAX_BYTES;
}

export interface PasswordCheck {
  key:   'length' | 'letter' | 'digit';
  label: string;
  met:   boolean;
}

/** The parts of the password policy, each marked as met or not, for the checklist under a new-password field. */
export function passwordChecks(value: string): PasswordCheck[] {
  return [
    { key: 'length', label: `At least ${PASSWORD_MIN_LENGTH} characters`, met: value.length >= PASSWORD_MIN_LENGTH },
    { key: 'letter', label: 'At least one letter',                        met: hasLetter(value) },
    { key: 'digit',  label: 'At least one number',                        met: hasDigit(value) },
  ];
}

/** A new password: the registration rule, checked in the server's order so the same message is reported. */
export const newPasswordSchema = z.string()
  .min(1, 'Password is required')
  .refine(
    value => value.length === 0 || (value.length >= PASSWORD_MIN_LENGTH && hasLetter(value) && hasDigit(value)),
    PASSWORD_POLICY_MESSAGE,
  )
  .refine(value => utf8ByteLength(value) <= PASSWORD_MAX_BYTES, PASSWORD_TOO_LONG_MESSAGE);

const confirmPasswordSchema = z.string().min(1, 'Please confirm your password');

// ── Forms ─────────────────────────────────────────────────────────────────────

/** POST /api/auth/password/forgot. */
export const forgotPasswordSchema = z.object({ email: accountEmailSchema });
export type ForgotPasswordForm = z.infer<typeof forgotPasswordSchema>;

/** POST /api/auth/email/resend. */
export const resendVerificationSchema = z.object({ email: accountEmailSchema });
export type ResendVerificationForm = z.infer<typeof resendVerificationSchema>;

/** POST /api/auth/password/reset. `confirmPassword` exists only in the form and is never sent. */
export const resetPasswordSchema = z
  .object({
    newPassword:     newPasswordSchema,
    confirmPassword: confirmPasswordSchema,
  })
  .refine(form => form.newPassword === form.confirmPassword, {
    message: PASSWORDS_DO_NOT_MATCH_MESSAGE,
    path:    ['confirmPassword'],
  });
export type ResetPasswordForm = z.infer<typeof resetPasswordSchema>;

/** POST /api/users/me/password. `confirmPassword` exists only in the form and is never sent. */
export const changePasswordSchema = z
  .object({
    currentPassword: z.string()
      .min(1, 'Current password is required')
      .max(CURRENT_PASSWORD_MAX_LENGTH, `Current password must be at most ${CURRENT_PASSWORD_MAX_LENGTH} characters`),
    newPassword:     newPasswordSchema,
    confirmPassword: confirmPasswordSchema,
  })
  .refine(form => form.newPassword.length === 0 || form.newPassword !== form.currentPassword, {
    message: PASSWORD_UNCHANGED_MESSAGE,
    path:    ['newPassword'],
  })
  .refine(form => form.newPassword === form.confirmPassword, {
    message: PASSWORDS_DO_NOT_MATCH_MESSAGE,
    path:    ['confirmPassword'],
  });
export type ChangePasswordForm = z.infer<typeof changePasswordSchema>;

// ── Values that are not typed by the person ───────────────────────────────────

/** Longest token POST /api/auth/password/reset accepts. */
export const RESET_TOKEN_MAX_LENGTH = 128;
/** Longest token the email verification and email-change confirmation endpoints accept. */
export const EMAIL_TOKEN_MAX_LENGTH = 200;

/**
 * The `token` of an emailed link as it is sent to the API: trimmed, otherwise unchanged. Null when the link has
 * no token or one the server would refuse for its length, in which case no request is made.
 */
export function linkToken(raw: unknown, maxLength: number): string | null {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== 'string') return null;
  const token = value.trim();
  return token.length > 0 && token.length <= maxLength ? token : null;
}

const SESSION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** True when `value` has the shape of a session id (`keepSessionId` is refused by the server otherwise). */
export function isSessionId(value: unknown): value is string {
  return typeof value === 'string' && SESSION_ID_PATTERN.test(value);
}
