import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import { getAccountRequest, listAccountRequests, type AccountRequestFilter } from '@/lib/admin/ops-api';
import type { AdminAccountRequest } from '@/lib/admin/ops-types';

/** Account requests (exports, closures, deletion requests) of all users, newest first, page by page. */
export function useAdminAccountRequests({ type, status }: AccountRequestFilter = {}): PagedState<AdminAccountRequest> {
  return usePaged<AdminAccountRequest>(
    (page, size) => listAccountRequests({ type, status }, page, size),
    [type, status],
    { keyOf: r => r.requestId, errorMessage: 'The account requests could not be loaded.' },
  );
}

/** One account request by id; nothing is requested while `requestId` is null. */
export function useAdminAccountRequest(requestId: number | null): ResourceState<AdminAccountRequest> {
  return useResource<AdminAccountRequest>(
    () => getAccountRequest(requestId as number),
    [requestId],
    { enabled: requestId != null, errorMessage: 'The account request could not be loaded.' },
  );
}
