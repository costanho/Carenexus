import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type {
  AdminDoctor, AdminUser, AdminUserActionResult, AdminUserDetail, AdminUserFilters, AuditEntry, AuditFilters,
  CreateAdminRequest, LegalAcceptance, LicenceRequest, MfaStatus, VerificationStatus,
} from './types';

// One function per administrator endpoint (api/be-admin-users.md, api/be-account-mfa.md 1.9 and 1.10,
// api/be-account-legal.md 1.5). All of them need role ADMIN; every other role gets 403.

const USERS   = '/api/admin/users';
const DOCTORS = '/api/admin/doctors';
const AUDIT   = '/api/admin/audit-logs';

/** Default page size of the audit search (the server's default; every other list uses 20). */
export const AUDIT_PAGE_SIZE = 50;

function userPath(systemId: string, rest = ''): string {
  return `${USERS}/${encodeURIComponent(systemId)}${rest}`;
}

function doctorPath(systemId: string, rest: string): string {
  return `${DOCTORS}/${encodeURIComponent(systemId)}${rest}`;
}

// ─── Accounts ─────────────────────────────────────────────────────────────────

/** GET /api/admin/users: newest account first. */
export function listAdminUsers(filters: AdminUserFilters, page: number, size?: number): Promise<PageResponse<AdminUser>> {
  return fetchPage<AdminUser>(USERS, page, size, {
    q:      filters.q?.trim(),
    role:   filters.role,
    active: filters.active,
  });
}

/** GET /api/admin/users/{systemId}. 404 `User not found`. */
export async function getAdminUser(systemId: string): Promise<AdminUserDetail> {
  const { data } = await api.get<AdminUserDetail>(userPath(systemId));
  return data;
}

/**
 * POST /api/admin/users: creates another administrator with a temporary password. `idempotencyKey` is the key
 * of the open dialog, so a retried submit cannot create a second account.
 */
export async function createAdminUser(body: CreateAdminRequest, idempotencyKey: string): Promise<AdminUserDetail> {
  const phone = body.phone?.trim();
  const { data } = await api.post<AdminUserDetail>(
    USERS,
    {
      firstName: body.firstName.trim(),
      lastName:  body.lastName.trim(),
      email:     body.email.trim(),
      password:  body.password,
      phone:     phone ? phone : null,
    },
    withIdempotencyKey(idempotencyKey),
  );
  return data;
}

/** POST .../activate: reactivates a deactivated account or re-opens one its owner closed. No body. */
export async function activateAdminUser(systemId: string): Promise<AdminUserActionResult> {
  const { data } = await api.post<AdminUserActionResult>(userPath(systemId, '/activate'));
  return data;
}

/** POST .../deactivate: needs a reason (1 to 500 characters); ends every session of the account. */
export async function deactivateAdminUser(systemId: string, reason: string): Promise<AdminUserActionResult> {
  const { data } = await api.post<AdminUserActionResult>(userPath(systemId, '/deactivate'), { reason: reason.trim() });
  return data;
}

/** POST .../sessions/revoke: signs the account out everywhere; it stays active. */
export async function revokeAdminUserSessions(systemId: string): Promise<AdminUserActionResult> {
  const { data } = await api.post<AdminUserActionResult>(userPath(systemId, '/sessions/revoke'));
  return data;
}

/** POST .../password-reset: emails a link to set a new password (an invitation when there is no password yet). */
export async function sendAdminPasswordReset(systemId: string): Promise<AdminUserActionResult> {
  const { data } = await api.post<AdminUserActionResult>(userPath(systemId, '/password-reset'));
  return data;
}

/** GET .../audit-logs: rows where the account is the actor, the target or the patient. Each read is audited. */
export function listAdminUserAuditLogs(systemId: string, page: number, size?: number): Promise<PageResponse<AuditEntry>> {
  return fetchPage<AuditEntry>(userPath(systemId, '/audit-logs'), page, size);
}

/** GET .../mfa: two-factor state of the account. */
export async function getAdminUserMfa(systemId: string): Promise<MfaStatus> {
  const { data } = await api.get<MfaStatus>(userPath(systemId, '/mfa'));
  return data;
}

/** POST .../mfa/reset: removes the enrolment; answers the state after the reset. Sessions are not ended. */
export async function resetAdminUserMfa(systemId: string): Promise<MfaStatus> {
  const { data } = await api.post<MfaStatus>(userPath(systemId, '/mfa/reset'));
  return data;
}

/** GET .../legal-acceptances: newest acceptance first. */
export function listAdminUserLegalAcceptances(systemId: string, page: number, size?: number): Promise<PageResponse<LegalAcceptance>> {
  return fetchPage<LegalAcceptance>(userPath(systemId, '/legal-acceptances'), page, size);
}

// ─── Clinician verification ───────────────────────────────────────────────────

/** GET /api/admin/doctors: oldest registration first. No status = every status. */
export function listAdminDoctors(status: VerificationStatus | null | undefined, page: number, size?: number): Promise<PageResponse<AdminDoctor>> {
  return fetchPage<AdminDoctor>(DOCTORS, page, size, { status: status ?? undefined });
}

/** GET /api/admin/doctors/pending: the verification queue. `totalElements` of `size = 1` is the queue length. */
export function listPendingDoctors(page: number, size?: number): Promise<PageResponse<AdminDoctor>> {
  return fetchPage<AdminDoctor>(`${DOCTORS}/pending`, page, size);
}

/** POST .../verify: approves from PENDING, REJECTED or SUSPENDED. 409 `LICENCE_REQUIRED` without a licence number. */
export async function verifyAdminDoctor(systemId: string): Promise<AdminDoctor> {
  const { data } = await api.post<AdminDoctor>(doctorPath(systemId, '/verify'));
  return data;
}

/** POST .../reject: only from PENDING. The reason is emailed to the clinician. */
export async function rejectAdminDoctor(systemId: string, reason: string): Promise<AdminDoctor> {
  const { data } = await api.post<AdminDoctor>(doctorPath(systemId, '/reject'), { reason: reason.trim() });
  return data;
}

/** PUT .../licence: replaces the four licence fields. 409 `Licence number already registered` on a duplicate. */
export async function updateAdminDoctorLicence(systemId: string, body: LicenceRequest): Promise<AdminDoctor> {
  const { data } = await api.put<AdminDoctor>(doctorPath(systemId, '/licence'), body);
  return data;
}

// ─── Audit log ────────────────────────────────────────────────────────────────

/** GET /api/admin/audit-logs: newest first. System ids, action and resource type are matched in upper case. */
export function listAuditLogs(filters: AuditFilters, page: number, size: number = AUDIT_PAGE_SIZE): Promise<PageResponse<AuditEntry>> {
  return fetchPage<AuditEntry>(AUDIT, page, size, {
    actor:        filters.actor?.trim().toUpperCase(),
    patient:      filters.patient?.trim().toUpperCase(),
    action:       filters.action?.trim().toUpperCase(),
    resourceType: filters.resourceType?.trim().toUpperCase(),
    status:       filters.status,
    from:         filters.from,
    to:           filters.to,
  });
}

/** GET /api/admin/audit-logs/{auditId}. 404 `Audit log entry not found`. The read is audited. */
export async function getAuditEntry(auditId: number): Promise<AuditEntry> {
  const { data } = await api.get<AuditEntry>(`${AUDIT}/${auditId}`);
  return data;
}
