import { cancelAccessRequest, createAccessRequest, fetchAccessRequests } from '@/lib/caregiver/api';
import type { AccessRequestStatus, CaregiverAccessRequest, CreateAccessRequestBody } from '@/lib/caregiver/types';
import { type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { type ResourceState, useResource } from './use-resource';

// A caregiver's requests for access to an existing patient (US-14): the list, asking and cancelling.
// The server never says whether an email belongs to a patient: a request for an unknown email looks like one
// that has not been answered, and expires after 30 days.

export type { AccessRequestStatus, CaregiverAccessRequest, CreateAccessRequestBody } from '@/lib/caregiver/types';

/** Shown after a request was sent when the server's own sentence is missing. */
export const ACCESS_REQUEST_SENT_MESSAGE =
  'If a patient account with this email exists, they will be asked to approve your request.';

export interface AccessRequestsOptions {
  /** Rows per page, 1 to 100. Default 20. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

/**
 * The signed-in caregiver's own requests, newest first, page by page (GET /api/caregiver/access-requests).
 * Pass a status to list only those; without one every request is listed.
 */
export function useCaregiverAccessRequests(
  status?: AccessRequestStatus,
  options: AccessRequestsOptions = {},
): PagedState<CaregiverAccessRequest> {
  return usePaged<CaregiverAccessRequest>(
    (page, size) => fetchAccessRequests(page, size, status),
    [status ?? ''],
    {
      pageSize:     options.pageSize,
      enabled:      options.enabled,
      keyOf:        request => request.requestId,
      errorMessage: 'Could not load your access requests.',
    },
  );
}

/** How many of the caregiver's requests are still waiting for an answer (for a count beside a link). */
export function usePendingAccessRequestCount(enabled = true): ResourceState<number> {
  return useResource<number>(
    async () => (await fetchAccessRequests(0, 1, 'PENDING')).totalElements,
    [],
    { enabled, errorMessage: 'Could not load your access requests.' },
  );
}

/**
 * Sends a request. `run(body)` resolves with the neutral confirmation to show (the same sentence whether or not
 * the email belongs to a patient). A 409 means the caregiver is already linked to that patient: show its message.
 */
export function useRequestAccess(): ActionState<[CreateAccessRequestBody], string> {
  return useAction(
    async (body: CreateAccessRequestBody) => (await createAccessRequest(body)) || ACCESS_REQUEST_SENT_MESSAGE,
    { errorMessage: 'Could not send your request. Please try again.' },
  );
}

/** Cancels one of the caregiver's own pending requests. The patient is not told. */
export function useCancelAccessRequest(): ActionState<[number], void> {
  return useAction(
    (requestId: number) => cancelAccessRequest(requestId),
    { errorMessage: 'Could not cancel the request. Please try again.' },
  );
}
