import { usePaged, type PagedState } from '@/hooks/use-paged';
import { useResource, type ResourceState } from '@/hooks/use-resource';
import {
  getCorrection, listAdminCorrections, listAssignableDoctors,
  type AdminCorrectionFilter, type AssignableDoctor, type CorrectionRequest,
} from '@/lib/admin/corrections-api';

/** Every correction request for the admin `corrections` section, newest first, page by page. */
export function useAdminCorrections({ status, unassigned = false }: AdminCorrectionFilter = {}): PagedState<CorrectionRequest> {
  return usePaged<CorrectionRequest>(
    (page, size) => listAdminCorrections({ status, unassigned }, page, size),
    [status, unassigned],
    { keyOf: r => r.requestId, errorMessage: 'The correction requests could not be loaded.' },
  );
}

/** One correction request by id; nothing is requested while `requestId` is null. */
export function useAdminCorrection(requestId: number | null): ResourceState<CorrectionRequest> {
  return useResource<CorrectionRequest>(
    () => getCorrection(requestId as number),
    [requestId],
    { enabled: requestId != null, errorMessage: 'The correction request could not be loaded.' },
  );
}

/** The doctors a request may be assigned to (the patient's active related doctors). */
export function useAssignableDoctors(requestId: number | null, enabled = true): ResourceState<AssignableDoctor[]> {
  return useResource<AssignableDoctor[]>(
    () => listAssignableDoctors(requestId as number),
    [requestId],
    { enabled: enabled && requestId != null, errorMessage: 'The doctors for this patient could not be loaded.' },
  );
}
