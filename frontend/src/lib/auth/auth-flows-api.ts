import { apiErrorStatus } from '@/lib/api/errors';
import { isSessionId } from '@/lib/validation/account-recovery';
import { emitSignedOut } from './auth.events';
import { api } from './auth.interceptor';
import { clearAuth, getSessionId } from './auth.storage';

// Typed client for the account flows that live outside the dashboard: password recovery and change, email
// verification, confirming an email change, and the terms and privacy documents with their acceptance.
// One function per endpoint; the screens under app/auth and app/legal are the callers (the sign-in screen
// also uses resendVerification).

// ── Shapes ────────────────────────────────────────────────────────────────────

export interface MessageResponse {
  message: string;
}

export interface EmailChangeConfirmResponse {
  message: string;
  /** The account's new sign-in email, lower case. */
  email:   string;
}

export type LegalDocType = 'TERMS' | 'PRIVACY';

export interface LegalDocumentSummary {
  docType:            LegalDocType;
  /** Opaque version label; sent back unchanged when accepting. */
  version:            string;
  title:              string;
  /** ISO-8601 instant. */
  effectiveAt:        string;
  /** True while the text is placeholder copy that has not been through legal review. */
  pendingLegalReview: boolean;
}

export interface LegalDocument extends LegalDocumentSummary {
  /**
   * The text in a restricted Markdown subset: #, ## and ### headings, paragraphs separated by a blank line,
   * "- " bullets, **bold** and [text](https://...) links. Anything else is shown literally.
   */
  contentMd: string;
}

export interface LegalAcceptance {
  acceptanceId: number;
  docType:      LegalDocType;
  version:      string;
  /** ISO-8601 instant. */
  acceptedAt:   string;
  /** True when `version` is the current version of `docType`. */
  current:      boolean;
}

export interface LegalStatus {
  /** True when `pending` is not empty; the same value as the session flag `legalActionRequired`. */
  actionRequired: boolean;
  accepted:       LegalAcceptance[];
  /** Current documents the signed-in person has not accepted, terms before privacy. */
  pending:        LegalDocumentSummary[];
}

// ── Password ──────────────────────────────────────────────────────────────────

/**
 * POST /api/auth/password/forgot. Answers the same way whether or not an account has that address, so the
 * caller shows a neutral confirmation and never repeats the address as "sent to".
 */
export async function requestPasswordReset(email: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>('/api/auth/password/forgot', { email });
  return data;
}

/**
 * POST /api/auth/password/reset with the token of an emailed link. Also sets the first password of an invited
 * account. The password is sent exactly as typed. Every session of the account ends; no tokens come back.
 */
export async function resetPassword(token: string, newPassword: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>('/api/auth/password/reset', { token, newPassword });
  return data;
}

/**
 * POST /api/users/me/password for the signed-in person. This device's session is named as the one to keep, so
 * the person stays signed in here while every other session ends; without it the server would end this one too.
 * A wrong current password is a 400 with code CURRENT_PASSWORD_INCORRECT, an unchanged one PASSWORD_UNCHANGED.
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<MessageResponse> {
  const sessionId = await getSessionId();
  const { data } = await api.post<MessageResponse>('/api/users/me/password', {
    currentPassword,
    newPassword,
    // Left out when no usable id is stored: a malformed value would be refused and block the change.
    ...(isSessionId(sessionId) ? { keepSessionId: sessionId } : {}),
  });
  return data;
}

// ── Email verification and email change ───────────────────────────────────────

/** POST /api/auth/email/verify. The token is single use: a second call with it answers 400 TOKEN_INVALID. */
export async function verifyEmail(token: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>('/api/auth/email/verify', { token });
  return data;
}

/** POST /api/auth/email/resend. Answers the same way whether or not the address belongs to an account. */
export async function resendVerification(email: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>('/api/auth/email/resend', { email });
  return data;
}

/**
 * POST /api/auth/email-change/confirm. Single use; 409 means another account took the address meanwhile.
 * A success ends every session of the account, the one in this browser included (see confirmEmailChangeOnce).
 */
export async function confirmEmailChange(token: string): Promise<EmailChangeConfirmResponse> {
  const { data } = await api.post<EmailChangeConfirmResponse>('/api/auth/email-change/confirm', { token });
  return data;
}

/**
 * Forgets the session stored on this device without calling the server, for when the server has already ended
 * it. Listeners of the sign-out event (drafts, unread counts) are told as on a normal sign-out.
 */
export async function discardLocalSession(): Promise<void> {
  await clearAuth();
  emitSignedOut();
}

/**
 * Wraps a single-use token call so one page load sends it once per token, however often the screen mounts or
 * its effect runs: later callers get the first call's result. A second request would be refused (the token is
 * spent) and would replace a success with "invalid link". Only an answer that says nothing about the token
 * (no response, a server error, a rate limit) is forgotten, so Retry can send the request again.
 */
function oncePerToken<T>(call: (token: string) => Promise<T>): (token: string) => Promise<T> {
  const attempts = new Map<string, Promise<T>>();
  return token => {
    const earlier = attempts.get(token);
    if (earlier) return earlier;
    const attempt = call(token);
    attempts.set(token, attempt);
    attempt.catch(e => {
      const status = apiErrorStatus(e);
      const final = status === 400 || status === 409;
      if (!final && attempts.get(token) === attempt) attempts.delete(token);
    });
    return attempt;
  };
}

/** verifyEmail, sent at most once per token per page load. */
export const verifyEmailOnce = oncePerToken(verifyEmail);

/**
 * confirmEmailChange, sent at most once per token per page load. When it succeeds the session stored in this
 * browser is discarded as part of the same step (never again on a later mount, which could sign out a session
 * started afterwards): the server has revoked it, so it could not be refreshed.
 */
export const confirmEmailChangeOnce = oncePerToken(async (token: string) => {
  const result = await confirmEmailChange(token);
  await discardLocalSession();
  return result;
});

// ── Terms and privacy ─────────────────────────────────────────────────────────

/** GET /api/legal/documents/{docType}: the current text of one document. Public. */
export async function getLegalDocument(docType: LegalDocType): Promise<LegalDocument> {
  const { data } = await api.get<LegalDocument>(`/api/legal/documents/${docType.toLowerCase()}`);
  return data;
}

/** GET /api/users/me/legal: what the signed-in person has accepted and what is still outstanding. */
export async function getLegalStatus(): Promise<LegalStatus> {
  const { data } = await api.get<LegalStatus>('/api/users/me/legal');
  return normaliseStatus(data);
}

/**
 * POST /api/users/me/legal/accept for the version of the document that was shown. Answers with the status
 * after the acceptance (201 for a new record, 200 for a repeat; both mean accepted). A version that is no
 * longer current is a 400 with code LEGAL_ACCEPTANCE_REQUIRED: load the document again and ask again.
 */
export async function acceptLegalDocument(docType: LegalDocType, version: string): Promise<LegalStatus> {
  const { data } = await api.post<LegalStatus>('/api/users/me/legal/accept', { docType, version });
  return normaliseStatus(data);
}

/** Guards the two lists against a missing value; `pending` decides `actionRequired` (the server derives it the same way). */
function normaliseStatus(data: LegalStatus): LegalStatus {
  const pending = Array.isArray(data?.pending) ? data.pending : [];
  return {
    actionRequired: pending.length > 0,
    accepted:       Array.isArray(data?.accepted) ? data.accepted : [],
    pending,
  };
}
