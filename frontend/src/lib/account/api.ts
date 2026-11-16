import { api } from '@/lib/auth/auth.interceptor';
import type {
  AcceptLegalRequest, AccountClosure, AccountRequest, ChangePasswordRequest, EmailChangeRequest, EmailChangeResponse,
  LegalDocType, LegalDocument, LegalStatus, MessageResponse, MfaDisableRequest, MfaRecoveryCodes, MfaSetup, MfaStatus,
  PasswordReasonRequest, RevokeOtherSessionsResponse, SessionResponse,
} from './types';

// Typed client of the account endpoints: one function per endpoint, all acting on the signed-in account.
// A wrong password or code on these paths is a 400 (never a 401), so the interceptor does not treat it as an
// expired session.

/** Building an export and downloading it can take longer than the shared 15 s request timeout. */
const EXPORT_TIMEOUT_MS = 60_000;

// ─── Password ────────────────────────────────────────────────────────────────

/** POST /api/users/me/password. Passwords are sent exactly as typed (never trimmed). */
export async function changePassword(body: ChangePasswordRequest): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>('/api/users/me/password', body);
  return data;
}

// ─── Email change ────────────────────────────────────────────────────────────

/** POST /api/users/me/email-change (201). A new request replaces an earlier pending one. */
export async function requestEmailChange(body: EmailChangeRequest): Promise<EmailChangeResponse> {
  const { data } = await api.post<EmailChangeResponse>('/api/users/me/email-change', body);
  return data;
}

/** GET /api/users/me/email-change: the pending request, or null (the server answers 204 with no body). */
export async function getPendingEmailChange(): Promise<EmailChangeResponse | null> {
  const { status, data } = await api.get<EmailChangeResponse | ''>('/api/users/me/email-change');
  if (status === 204 || !data || typeof data !== 'object') return null;
  return data;
}

/** DELETE /api/users/me/email-change (204 whether or not a request was pending). */
export async function cancelEmailChange(): Promise<void> {
  await api.delete('/api/users/me/email-change');
}

// ─── Two-factor authentication ───────────────────────────────────────────────

/** GET /api/users/me/mfa: the source of truth for the card (the session flag is only a hint). */
export async function getMfaStatus(): Promise<MfaStatus> {
  const { data } = await api.get<MfaStatus>('/api/users/me/mfa');
  return data;
}

/** POST /api/users/me/mfa/setup. Repeating it while setup is pending returns the same secret. */
export async function startMfaSetup(): Promise<MfaSetup> {
  const { data } = await api.post<MfaSetup>('/api/users/me/mfa/setup', {});
  return data;
}

/** POST /api/users/me/mfa/enable: confirms the pending setup and returns the recovery codes, once. */
export async function enableMfa(code: string): Promise<MfaRecoveryCodes> {
  const { data } = await api.post<MfaRecoveryCodes>('/api/users/me/mfa/enable', { code });
  return data;
}

/** POST /api/users/me/mfa/disable. */
export async function disableMfa(body: MfaDisableRequest): Promise<MfaStatus> {
  const { data } = await api.post<MfaStatus>('/api/users/me/mfa/disable', body);
  return data;
}

/** POST /api/users/me/mfa/recovery-codes/regenerate: replaces every recovery code; needs a fresh app code. */
export async function regenerateRecoveryCodes(code: string): Promise<MfaRecoveryCodes> {
  const { data } = await api.post<MfaRecoveryCodes>('/api/users/me/mfa/recovery-codes/regenerate', { code });
  return data;
}

// ─── Sessions ────────────────────────────────────────────────────────────────

/**
 * GET /api/users/me/sessions: a plain array (at most 50), this device first when `currentSessionId` matches.
 * Without an id no entry is marked as current.
 */
export async function listSessions(currentSessionId?: string | null): Promise<SessionResponse[]> {
  const { data } = await api.get<SessionResponse[]>('/api/users/me/sessions', {
    params: currentSessionId ? { current: currentSessionId } : undefined,
  });
  return Array.isArray(data) ? data : [];
}

/** DELETE /api/users/me/sessions/{sessionId} (204). A session that already ended answers 404. */
export async function revokeSession(sessionId: string): Promise<void> {
  await api.delete(`/api/users/me/sessions/${encodeURIComponent(sessionId)}`);
}

/** POST /api/users/me/sessions/revoke-others. 404 when `keepSessionId` is not an active session of the caller. */
export async function revokeOtherSessions(keepSessionId: string): Promise<RevokeOtherSessionsResponse> {
  const { data } = await api.post<RevokeOtherSessionsResponse>('/api/users/me/sessions/revoke-others', { keepSessionId });
  return data;
}

// ─── Data rights ─────────────────────────────────────────────────────────────

/** POST /api/users/me/data-export (201). The bundle is built inside the request, hence the longer timeout. */
export async function requestDataExport(password: string): Promise<AccountRequest> {
  const { data } = await api.post<AccountRequest>('/api/users/me/data-export', { password }, { timeout: EXPORT_TIMEOUT_MS });
  return data;
}

/** GET /api/users/me/data-export: a plain array (at most 20), newest first. */
export async function listDataExports(): Promise<AccountRequest[]> {
  const { data } = await api.get<AccountRequest[]>('/api/users/me/data-export');
  return Array.isArray(data) ? data : [];
}

/**
 * GET /api/users/me/data-export/{id}/download. The file needs the bearer token, so it is fetched as a Blob
 * instead of being opened as a link. With a Blob response an error body is a Blob too; it is parsed back into
 * the JSON envelope here so `apiError` reads the server's message as for any other request.
 */
export async function downloadDataExport(requestId: number): Promise<Blob> {
  try {
    const { data } = await api.get<Blob>(`/api/users/me/data-export/${requestId}/download`, {
      responseType: 'blob',
      timeout: EXPORT_TIMEOUT_MS,
    });
    return data;
  } catch (e) {
    const response = (e as { response?: { data?: unknown } } | null)?.response;
    const body = response?.data;
    if (response && body && typeof body === 'object' && typeof (body as Blob).text === 'function') {
      try {
        response.data = JSON.parse(await (body as Blob).text());
      } catch {
        response.data = undefined; // not JSON: the caller falls back to its own wording
      }
    }
    throw e;
  }
}

/** POST /api/users/me/close. After a 200 the access token is dead: call nothing else, clear the session. */
export async function closeAccount(body: PasswordReasonRequest): Promise<AccountClosure> {
  const { data } = await api.post<AccountClosure>('/api/users/me/close', body);
  return data;
}

/** POST /api/users/me/deletion-request (201). Nothing is deleted until an administrator approves. */
export async function requestAccountDeletion(body: PasswordReasonRequest): Promise<AccountRequest> {
  const { data } = await api.post<AccountRequest>('/api/users/me/deletion-request', body);
  return data;
}

/** GET /api/users/me/deletion-request: a plain array, newest first; element 0 is the current state. */
export async function listDeletionRequests(): Promise<AccountRequest[]> {
  const { data } = await api.get<AccountRequest[]>('/api/users/me/deletion-request');
  return Array.isArray(data) ? data : [];
}

/** POST /api/users/me/deletion-request/{id}/cancel: withdraws a request that is awaiting review. */
export async function cancelAccountDeletion(requestId: number): Promise<AccountRequest> {
  const { data } = await api.post<AccountRequest>(`/api/users/me/deletion-request/${requestId}/cancel`);
  return data;
}

// ─── Legal ───────────────────────────────────────────────────────────────────

/** GET /api/users/me/legal. */
export async function getLegalStatus(): Promise<LegalStatus> {
  const { data } = await api.get<LegalStatus>('/api/users/me/legal');
  return data;
}

/** GET /api/legal/documents/{docType}: the current text of one document (public). */
export async function getLegalDocument(docType: LegalDocType): Promise<LegalDocument> {
  const { data } = await api.get<LegalDocument>(`/api/legal/documents/${docType.toLowerCase()}`);
  return data;
}

/** POST /api/users/me/legal/accept (201 for a new acceptance, 200 for a repeat; both return the new status). */
export async function acceptLegalDocument(body: AcceptLegalRequest): Promise<LegalStatus> {
  const { data } = await api.post<LegalStatus>('/api/users/me/legal/accept', body);
  return data;
}
