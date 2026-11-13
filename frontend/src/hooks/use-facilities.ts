import { useEffect, useState } from 'react';
import { MAX_PAGE_SIZE } from '@/lib/api/paged';
import { listFacilities } from '@/lib/appointments/api';
import type { Facility } from '@/lib/appointments/types';
import { useResource } from './use-resource';

// Clinic locations for pickers (GET /api/facilities, active facilities only, by name). The first 100 are
// loaded at once; a clinic with more is searched on the server instead of paging through a select.

/** Longest search text the endpoint accepts. */
export const FACILITY_SEARCH_MAX_LENGTH = 100;
const SEARCH_DELAY_MS = 300;

export interface FacilitiesState {
  /** The facilities to offer: the whole list, or the matches of the current search. Empty is a normal state. */
  facilities: Facility[];
  /** Number of active facilities at the clinic (not only those loaded). */
  total:      number;
  /** True until the first list arrives. */
  loading:    boolean;
  /** True while a search is running. */
  searching:  boolean;
  error:      string | null;
  refetch:    () => void;
  /** True when the clinic has more facilities than one request returns: show a search box bound to `query`. */
  searchable: boolean;
  query:      string;
  setQuery:   (text: string) => void;
}

const NONE: Facility[] = [];

/**
 * `const { facilities, loading, error, refetch } = useFacilities({ enabled: type === 'IN_PERSON' });`
 * Nothing is requested while `enabled` is false. A clinic without facilities returns an empty list: the
 * booking form then shows no picker and books with `facilityId: null`.
 */
export function useFacilities(options: { enabled?: boolean } = {}): FacilitiesState {
  const enabled = options.enabled ?? true;
  const [query, setQueryText] = useState('');
  const [applied, setApplied] = useState('');

  const all = useResource(
    () => listFacilities(0, MAX_PAGE_SIZE),
    [],
    { enabled, errorMessage: 'Could not load the clinic locations.' },
  );
  const firstPage = all.data?.content ?? NONE;
  const total = all.data?.totalElements ?? 0;
  const searchable = total > firstPage.length;

  // The search runs a moment after typing stops, so each keystroke is not a request.
  useEffect(() => {
    const text = query.trim();
    if (!searchable || !text) { setApplied(''); return; }
    const timer = setTimeout(() => setApplied(text), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [query, searchable]);

  const search = useResource(
    () => listFacilities(0, MAX_PAGE_SIZE, applied),
    [applied],
    { enabled: enabled && searchable && applied !== '', errorMessage: 'Could not search the clinic locations.' },
  );
  const searchActive = searchable && applied !== '';

  return {
    facilities: searchActive ? search.data?.content ?? NONE : firstPage,
    total,
    loading:    all.loading,
    searching:  searchActive && search.loading,
    error:      all.error ?? (searchActive ? search.error : null),
    refetch:    () => { all.refetch(); if (searchActive) search.refetch(); },
    searchable,
    query,
    setQuery:   text => setQueryText(text.slice(0, FACILITY_SEARCH_MAX_LENGTH)),
  };
}
