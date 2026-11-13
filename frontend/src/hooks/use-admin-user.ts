import { useAction, type ActionState } from '@/hooks/use-action';
import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import {
  activateAdminUser, deactivateAdminUser, getAdminUser, getAdminUserMfa, listAdminUserAuditLogs,
  listAdminUserLegalAcceptances, resetAdminUserMfa, revokeAdminUserSessions, sendAdminPasswordReset,
} from '@/lib/admin/api';
import type { AdminUserActionResult, AdminUserDetail, AuditEntry, LegalAcceptance, MfaStatus } from '@/lib/admin/types';

/** One account with its status details (GET /api/admin/users/{systemId}). Nothing is requested without an id. */
export function useAdminUser(systemId: string | null | undefined): ResourceState<AdminUserDetail> {
  const id = systemId?.trim() ?? '';
  return useResource<AdminUserDetail>(() => getAdminUser(id), [id], {
    enabled: id !== '',
    errorMessage: 'The account could not be loaded.',
  });
}

export interface AdminUserActions {
  /** Reactivates a deactivated account or re-opens one its owner closed. */
  activate:          ActionState<[string], AdminUserActionResult>;
  /** `(systemId, reason)`; the reason is required. Ends every session of the account. */
  deactivate:        ActionState<[string, string], AdminUserActionResult>;
  /** Signs the account out everywhere; it stays active. */
  revokeSessions:    ActionState<[string], AdminUserActionResult>;
  /** Emails a link to set a new password. Every call sends a new email. */
  sendPasswordReset: ActionState<[string], AdminUserActionResult>;
}

/**
 * The account actions of an administrator. Each one answers with the account after the action plus a sentence
 * that says what happened; callers replace their copy of the account with the result and show `message`.
 */
export function useAdminUserActions(): AdminUserActions {
  const activate = useAction(activateAdminUser, { errorMessage: 'The account could not be reactivated.' });
  const deactivate = useAction(deactivateAdminUser, { errorMessage: 'The account could not be deactivated.' });
  const revokeSessions = useAction(revokeAdminUserSessions, { errorMessage: 'The sessions could not be ended.' });
  const sendPasswordReset = useAction(sendAdminPasswordReset, { errorMessage: 'The link could not be sent.' });
  return { activate, deactivate, revokeSessions, sendPasswordReset };
}

export interface AdminUserMfaState extends ResourceState<MfaStatus> {
  /** Removes the account's two-factor enrolment; resolves with the state after the reset. */
  reset: ActionState<[], MfaStatus>;
}

/** Two-factor state of an account (GET .../mfa) and the reset action (POST .../mfa/reset). */
export function useAdminUserMfa(systemId: string | null | undefined, options: { enabled?: boolean } = {}): AdminUserMfaState {
  const id = systemId?.trim() ?? '';
  const status = useResource<MfaStatus>(() => getAdminUserMfa(id), [id], {
    enabled: id !== '' && (options.enabled ?? true),
    errorMessage: 'The two-factor authentication status could not be loaded.',
  });
  const reset = useAction(() => resetAdminUserMfa(id), { errorMessage: 'Two-factor authentication could not be reset.' });
  return { ...status, reset };
}

const keyOfAcceptance = (row: LegalAcceptance) => row.acceptanceId;
const keyOfAudit = (row: AuditEntry) => row.auditId;

/** Terms and privacy acceptances of an account, newest first (GET .../legal-acceptances). */
export function useAdminUserLegalAcceptances(
  systemId: string | null | undefined, options: { pageSize?: number; enabled?: boolean } = {},
): PagedState<LegalAcceptance> {
  const id = systemId?.trim() ?? '';
  return usePaged<LegalAcceptance>(
    (page, size) => listAdminUserLegalAcceptances(id, page, size),
    [id],
    {
      pageSize: options.pageSize ?? 10, enabled: id !== '' && (options.enabled ?? true), keyOf: keyOfAcceptance,
      errorMessage: 'The legal acceptances could not be loaded.',
    },
  );
}

/**
 * Audit rows that concern one account, newest first (GET .../audit-logs): the account as actor, as target or as
 * the patient whose data was touched. Every request is itself written to the audit log.
 */
export function useAdminUserAuditLogs(
  systemId: string | null | undefined, options: { pageSize?: number; enabled?: boolean } = {},
): PagedState<AuditEntry> {
  const id = systemId?.trim() ?? '';
  return usePaged<AuditEntry>(
    (page, size) => listAdminUserAuditLogs(id, page, size),
    [id],
    {
      pageSize: options.pageSize ?? 10, enabled: id !== '' && (options.enabled ?? true), keyOf: keyOfAudit,
      errorMessage: 'The recent activity could not be loaded.',
    },
  );
}
