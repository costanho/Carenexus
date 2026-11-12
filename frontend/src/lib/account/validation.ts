import { z } from 'zod';

// Form schemas of the account cards. Each rule mirrors the backend constraint of the endpoint the form posts
// to, so a value the server would refuse is caught before the request (and before it counts against a rate
// limit). Passwords are never trimmed: the server uses the exact string it receives.

// ─── Password policy (same rule as registration and password reset) ──────────

export const PASSWORD_MIN_LENGTH = 8;
/** bcrypt reads at most 72 bytes; the limit is on the UTF-8 length, not the character count. */
export const PASSWORD_MAX_BYTES = 72;

export function utf8Length(value: string): number {
  return new TextEncoder().encode(value).length;
}

export interface PasswordRule {
  key:   'length' | 'letter' | 'digit' | 'bytes';
  label: string;
  test:  (password: string) => boolean;
}

/** The rules shown as a checklist under the new-password field. */
export const PASSWORD_RULES: readonly PasswordRule[] = [
  { key: 'length', label: `At least ${PASSWORD_MIN_LENGTH} characters`, test: p => p.length >= PASSWORD_MIN_LENGTH },
  { key: 'letter', label: 'At least one letter',                       test: p => /\p{L}/u.test(p) },
  { key: 'digit',  label: 'At least one number',                       test: p => /\d/.test(p) },
  { key: 'bytes',  label: `No longer than ${PASSWORD_MAX_BYTES} characters`, test: p => p.length > 0 && utf8Length(p) <= PASSWORD_MAX_BYTES },
];

const newPassword = z.string()
  .min(1, 'Password is required')
  .refine(
    p => p.length >= PASSWORD_MIN_LENGTH && /\p{L}/u.test(p) && /\d/.test(p),
    'Password must be at least 8 characters and contain at least one letter and one number',
  )
  .refine(p => utf8Length(p) <= PASSWORD_MAX_BYTES, 'Password must be at most 72 characters');

export const changePasswordSchema = z.object({
  currentPassword: z.string()
    .min(1, 'Current password is required')
    .max(128, 'Current password must be at most 128 characters'),
  newPassword,
  /** Web only; never sent. */
  confirmPassword: z.string().min(1, 'Enter the new password again'),
}).superRefine((values, ctx) => {
  if (values.newPassword && values.currentPassword && values.newPassword === values.currentPassword) {
    ctx.addIssue({ code: 'custom', path: ['newPassword'], message: 'New password must be different from your current password' });
  }
  if (values.confirmPassword && values.confirmPassword !== values.newPassword) {
    ctx.addIssue({ code: 'custom', path: ['confirmPassword'], message: 'The two passwords do not match' });
  }
});
export type ChangePasswordValues = z.infer<typeof changePasswordSchema>;

// ─── Email change ────────────────────────────────────────────────────────────

/** The server's email rule (applied after trimming). */
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
export const EMAIL_MAX_LENGTH = 150;

/** The value as the server stores it: trimmed and lower-cased. */
export function normaliseEmail(value: string): string {
  return value.trim().toLowerCase();
}

export const emailChangeSchema = z.object({
  newEmail: z.string()
    .refine(v => v.trim().length > 0, 'New email is required')
    .refine(v => v.trim().length <= EMAIL_MAX_LENGTH, `Email must be at most ${EMAIL_MAX_LENGTH} characters`)
    .refine(v => v.trim().length === 0 || EMAIL_PATTERN.test(v.trim()), 'Enter a valid email address'),
  currentPassword: z.string()
    .min(1, 'Current password is required')
    .max(128, 'Current password must be at most 128 characters'),
});
export type EmailChangeValues = z.infer<typeof emailChangeSchema>;

// ─── Two-factor authentication ───────────────────────────────────────────────

/** Authenticator apps show codes as "123 456"; the server wants the six digits as a string (leading zeros kept). */
export function normaliseTotpCode(value: string): string {
  return value.replace(/\s+/g, '');
}

/** Recovery codes are accepted with or without the hyphen, in either case. */
export function normaliseRecoveryCode(value: string): string {
  return value.trim().toUpperCase();
}

export const TOTP_PATTERN = /^\d{6}$/;
export const RECOVERY_CODE_PATTERN = /^[A-Za-z0-9]{5}-?[A-Za-z0-9]{5}$/;

const totpCode = z.string()
  .refine(v => normaliseTotpCode(v).length > 0, 'Enter the 6-digit code from your authenticator app')
  .refine(v => normaliseTotpCode(v).length === 0 || TOTP_PATTERN.test(normaliseTotpCode(v)), 'The code must be 6 digits');

export const mfaCodeSchema = z.object({ code: totpCode });
export type MfaCodeValues = z.infer<typeof mfaCodeSchema>;

export const mfaDisableSchema = z.object({
  /** bcrypt limit of the account password; the server refuses a longer value. */
  password: z.string().min(1, 'Password is required').max(72, 'Password must be at most 72 characters'),
  method:   z.enum(['code', 'recoveryCode']),
  code:     z.string(),
  recoveryCode: z.string(),
}).superRefine((values, ctx) => {
  if (values.method === 'code') {
    const code = normaliseTotpCode(values.code);
    if (!code) ctx.addIssue({ code: 'custom', path: ['code'], message: 'Enter the 6-digit code from your authenticator app' });
    else if (!TOTP_PATTERN.test(code)) ctx.addIssue({ code: 'custom', path: ['code'], message: 'The code must be 6 digits' });
  } else {
    const recovery = values.recoveryCode.trim();
    if (!recovery) ctx.addIssue({ code: 'custom', path: ['recoveryCode'], message: 'Enter one of your recovery codes' });
    else if (!RECOVERY_CODE_PATTERN.test(recovery)) {
      ctx.addIssue({ code: 'custom', path: ['recoveryCode'], message: 'Enter a recovery code such as ABCDE-FGHJK' });
    }
  }
});
export type MfaDisableValues = z.infer<typeof mfaDisableSchema>;

// ─── Data rights ─────────────────────────────────────────────────────────────

export const REASON_MAX_LENGTH = 500;
/** What the person types to confirm closing the account (a client-side rule; the server needs only the password). */
export const CLOSE_CONFIRMATION_WORD = 'CLOSE';

const confirmPassword = z.string()
  .refine(v => v.trim().length > 0, 'Password is required')
  .max(200, 'Password must be at most 200 characters');

const reason = z.string().refine(v => v.trim().length <= REASON_MAX_LENGTH, `Reason must be at most ${REASON_MAX_LENGTH} characters`);

export const passwordConfirmSchema = z.object({ password: confirmPassword });
export type PasswordConfirmValues = z.infer<typeof passwordConfirmSchema>;

export const deletionRequestSchema = z.object({ password: confirmPassword, reason });
export type DeletionRequestValues = z.infer<typeof deletionRequestSchema>;

export const closeAccountSchema = z.object({
  password: confirmPassword,
  reason,
  confirmation: z.string().refine(v => v.trim() === CLOSE_CONFIRMATION_WORD, `Type ${CLOSE_CONFIRMATION_WORD} to confirm`),
});
export type CloseAccountValues = z.infer<typeof closeAccountSchema>;

/** A reason as the API wants it: trimmed, and left out when blank. */
export function optionalReason(value: string): { reason?: string } {
  const trimmed = value.trim();
  return trimmed ? { reason: trimmed } : {};
}
