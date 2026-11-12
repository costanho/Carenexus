import { useCallback } from 'react';
import { apiError } from '@/lib/api/errors';
import {
  listAccessRequests,
  listCaregiverAccess,
  listCaregiverActivity,
  listConsents,
} from '@/lib/caregiver-access/api';
import type {
  AccessFlags,
  AccessRequestStatus,
  CaregiverAccess,
  CaregiverAccessRequest,
  CaregiverActivityEntry,
  ConsentRecord,
} from '@/lib/caregiver-access/types';
import { usePaged, type PagedState } from './use-paged';
import { useResource } from './use-resource';

// The patient's caregiver access (My Proxies): grants, access requests, a caregiver's activity and the consent
// ledger. The API client and the types live in '@/lib/caregiver-access'; they are re-exported here because the
// screen and older imports take them from this hook file.

export {
  approveAccessRequest,
  declineAccessRequest,
  grantCaregiverAccess,
  revokeCaregiverAccess,
  updateCaregiverAccess,
} from '@/lib/caregiver-access/api';
export type {
  AccessFlags,
  AccessLevel,
  AccessRequestStatus,
  ApproveAccessRequestBody,
  CaregiverAccess,
  CaregiverAccessRequest,
  CaregiverActivityEntry,
  ConsentRecord,
  ConsentScope,
  DeclineAccessRequestBody,
  GrantCaregiverAccessBody,
  UpdateCaregiverAccessBody,
} from '@/lib/caregiver-access/types';

/** Any subset of the twelve permission flags, as sent in a grant or an update. */
export type CaregiverPermissionFlags = Partial<AccessFlags>;

/**
 * Error text for this screen: the backend message for a 4xx, the rate-limit wording for a 429, a connection
 * hint when the server could not be reached, and `fallback` for a server fault.
 */
export function caregiverAccessErrorMessage(err: unknown, fallback: string): string {
  const failure = apiError(err, fallback);
  return failure.status >= 500 ? fallback : failure.message;
}

/**
 * True when a failed change means the row on screen is out of date: the grant or request no longer exists, is no
 * longer the caller's, or is in another state (revoked, already answered, cancelled, expired). The screen then
 * loads its lists again instead of leaving a row that cannot be acted on.
 */
export function isStaleAccessError(status: number | null | undefined): boolean {
  return status === 403 || status === 404 || status === 409;
}

export interface CaregiverAccessState {
  grants:      CaregiverAccess[];
  /** True until the first load finishes. */
  loading:     boolean;
  /** True while `refetch` reloads grants that are already on screen. */
  refreshing:  boolean;
  error:       string | null;
  /** HTTP status of the failure (0 = no response), or null. */
  errorStatus: number | null;
  refetch:     () => void;
  /** Applies the result of a change (an updated or removed grant) without another request. */
  setGrants:   (next: (prev: CaregiverAccess[]) => CaregiverAccess[]) => void;
}

/** The signed-in patient's caregiver grants (active and expired, not revoked), newest first. */
export function useCaregiverAccess(): CaregiverAccessState {
  const { data, loading, refreshing, error, errorStatus, refetch, setData } = useResource(
    listCaregiverAccess, [], { errorMessage: 'Failed to load your caregivers.' },
  );
  const setGrants = useCallback(
    (next: (prev: CaregiverAccess[]) => CaregiverAccess[]) => setData(prev => next(prev ?? [])),
    [setData],
  );
  return { grants: data ?? [], loading, refreshing, error, errorStatus, refetch, setGrants };
}

/**
 * Access requests addressed to the signed-in patient, newest first. The screen lists the pending ones;
 * pass null to list every status.
 */
export function useAccessRequests(
  status: AccessRequestStatus | null = 'PENDING',
): PagedState<CaregiverAccessRequest> {
  return usePaged<CaregiverAccessRequest>(
    (page, size) => listAccessRequests(status, page, size),
    [status],
    { keyOf: r => r.requestId, errorMessage: 'Failed to load access requests.' },
  );
}

/** What the caregiver of one grant viewed or did, newest first. Nothing is requested until `enabled`. */
export function useCaregiverActivity(accessId: number, enabled = true): PagedState<CaregiverActivityEntry> {
  return usePaged<CaregiverActivityEntry>(
    (page, size) => listCaregiverActivity(accessId, page, size),
    [accessId],
    { enabled, pageSize: 10, keyOf: e => e.auditId, errorMessage: 'Failed to load this caregiver\'s activity.' },
  );
}

/**
 * The consent ledger, newest first: every recorded step of one grant, or of all grants (revoked ones included)
 * when `accessId` is null. Nothing is requested until `enabled`.
 */
export function useConsentHistory(accessId: number | null, enabled = true): PagedState<ConsentRecord> {
  return usePaged<ConsentRecord>(
    (page, size) => listConsents(accessId, page, size),
    [accessId],
    { enabled, pageSize: 10, keyOf: c => c.consentId, errorMessage: 'Failed to load the consent history.' },
  );
}
