import { api } from '@/lib/auth/auth.interceptor';
import { withIdempotencyKey } from '@/lib/api/idempotency';
import { DEFAULT_PAGE_SIZE, fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import type {
  AccessRequestStatus,
  ApproveAccessRequestBody,
  CaregiverAccess,
  CaregiverAccessRequest,
  CaregiverActivityEntry,
  ConsentRecord,
  DeclineAccessRequestBody,
  GrantCaregiverAccessBody,
  UpdateCaregiverAccessBody,
} from './types';

// The patient's side of caregiver access: grants, access requests, the consent ledger and the caregiver
// activity feed. One function per endpoint; every function rejects with the axios error (show it with
// `apiError` / `apiErrorMessage` from '@/lib/api/errors').

const GRANTS   = '/api/patients/me/caregiver-access';
const REQUESTS = `${GRANTS}/requests`;
const CONSENTS = '/api/patients/me/consents';

// ─── Grants ───────────────────────────────────────────────────────────────────

/** The caller's grants that are not revoked (active and expired), newest first. Not paged (capped at 200). */
export async function listCaregiverAccess(): Promise<CaregiverAccess[]> {
  const { data } = await api.get<CaregiverAccess[]>(GRANTS);
  return Array.isArray(data) ? data : [];
}

/**
 * Gives an existing caregiver account access. Communication flags sent `true` are approved in the same save.
 * Pass the form's idempotency key so a retried submit does not create a second grant.
 */
export async function grantCaregiverAccess(
  body: GrantCaregiverAccessBody,
  idempotencyKey?: string,
): Promise<CaregiverAccess> {
  const { data } = await api.post<CaregiverAccess>(
    GRANTS, body, idempotencyKey ? withIdempotencyKey(idempotencyKey) : undefined,
  );
  return data;
}

/**
 * Changes a grant. A communication flag sent `true` approves it, `false` withdraws the request and the approval.
 * To store individual data flags send `accessLevel` CUSTOM (or EDIT_ONLY) and every flag.
 */
export async function updateCaregiverAccess(
  accessId: number,
  body: UpdateCaregiverAccessBody,
): Promise<CaregiverAccess> {
  const { data } = await api.patch<CaregiverAccess>(`${GRANTS}/${accessId}`, body);
  return data;
}

/** Revokes a grant. Idempotent: revoking a revoked grant succeeds again. */
export async function revokeCaregiverAccess(accessId: number): Promise<void> {
  await api.delete(`${GRANTS}/${accessId}`);
}

// ─── Access requests ──────────────────────────────────────────────────────────

/** One page of the requests addressed to the caller, newest first. Without `status` every status is returned. */
export function listAccessRequests(
  status: AccessRequestStatus | null | undefined,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<CaregiverAccessRequest>> {
  return fetchPage<CaregiverAccessRequest>(REQUESTS, page, size, { status });
}

/**
 * Approves a pending request with the permissions the patient chose. Answers the request row (status APPROVED,
 * `accessId` of the grant); read the grant itself from `listCaregiverAccess`.
 */
export async function approveAccessRequest(
  requestId: number,
  body: ApproveAccessRequestBody,
): Promise<CaregiverAccessRequest> {
  const { data } = await api.post<CaregiverAccessRequest>(`${REQUESTS}/${requestId}/approve`, body);
  return data;
}

/** Declines a pending request. The optional reason is shown to the caregiver. */
export async function declineAccessRequest(
  requestId: number,
  body: DeclineAccessRequestBody = {},
): Promise<CaregiverAccessRequest> {
  const { data } = await api.post<CaregiverAccessRequest>(`${REQUESTS}/${requestId}/decline`, body);
  return data;
}

// ─── Consent ledger and caregiver activity ────────────────────────────────────

/** One page of the caller's consent ledger, newest first; with `accessId` only the steps of that grant. */
export function listConsents(
  accessId: number | null | undefined,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<ConsentRecord>> {
  return fetchPage<ConsentRecord>(CONSENTS, page, size, { accessId });
}

/** One page of what the caregiver of a grant viewed or did with the caller's data, newest first. */
export function listCaregiverActivity(
  accessId: number,
  page: number,
  size: number = DEFAULT_PAGE_SIZE,
): Promise<PageResponse<CaregiverActivityEntry>> {
  return fetchPage<CaregiverActivityEntry>(`${GRANTS}/${accessId}/activity`, page, size);
}
