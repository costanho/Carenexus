import { usePaged, type PagedState } from '@/hooks/use-paged';
import { listAdminFacilities } from '@/lib/admin/ops-api';
import type { Facility, FacilityActiveFilter } from '@/lib/admin/ops-types';

export interface AdminFacilitiesQuery {
  /** Which facilities to list. Default all. */
  active?: FacilityActiveFilter;
  /** Name or address search (already debounced by the caller). */
  query?: string;
}

/** Facilities in any state for the admin `facilities` section, page by page, sorted by name. */
export function useAdminFacilities({ active = 'all', query = '' }: AdminFacilitiesQuery = {}): PagedState<Facility> {
  const search = query.trim();
  const flag = active === 'active' ? true : active === 'inactive' ? false : undefined;
  return usePaged<Facility>(
    (page, size) => listAdminFacilities({ active: flag, query: search }, page, size),
    [active, search],
    { keyOf: f => f.facilityId, errorMessage: 'The facilities could not be loaded.' },
  );
}
