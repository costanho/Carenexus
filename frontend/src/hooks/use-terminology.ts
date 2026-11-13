import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError } from '@/lib/api/errors';
import { fetchPage } from '@/lib/api/paged';
import { ICD_QUERY_MAX } from '@/lib/validation/medical-record';

// ICD-10 type-ahead for the medical record form: GET /api/terminology/icd?query=&size= (doctors and
// administrators). The server matches a code prefix (the dot is ignored) or every word of the description and
// answers an empty page for fewer than two characters.

export interface IcdSearchItem {
  code:        string;
  description: string;
}

/** Shortest query that is sent. */
export const ICD_SEARCH_MIN_CHARS = 2;
/** Time after the last keystroke before the request is sent. */
export const ICD_SEARCH_DEBOUNCE_MS = 300;
/** Suggestions asked for per search. */
export const ICD_SEARCH_SIZE = 10;

/** Trims, collapses inner whitespace and cuts to the 100 characters the endpoint accepts. */
export function normaliseIcdQuery(query: string): string {
  return query.trim().replace(/\s+/g, ' ').slice(0, ICD_QUERY_MAX);
}

export async function searchIcdCodes(query: string, size: number = ICD_SEARCH_SIZE): Promise<{ items: IcdSearchItem[]; total: number }> {
  const page = await fetchPage<IcdSearchItem>('/api/terminology/icd', 0, size, { query: normaliseIcdQuery(query) });
  const items = page.content
    .filter(row => row && typeof row.code === 'string' && row.code)
    .map(row => ({ code: row.code, description: row.description ?? '' }));
  return { items, total: page.totalElements };
}

export interface IcdSearchState {
  /** Suggestions for the latest settled query, best match first. Empty while the query is too short. */
  results:   IcdSearchItem[];
  /** Matches on the server for the settled query (at most 200); more than `results.length` means "keep typing". */
  total:     number;
  /** True from the first keystroke of a searchable query until its answer arrives. */
  searching: boolean;
  /** A sentence to show when the search failed, or null. */
  error:     string | null;
  /** The query the current `results` answer; '' when nothing has been searched. */
  settledQuery: string;
  /** Runs the current query again after a failure. */
  retry:     () => void;
}

/**
 * Debounced ICD-10 search: `const { results, searching, error } = useIcdSearch(text)`.
 * Nothing is requested for fewer than two characters. An answer that arrives after the text has changed is
 * dropped, so the list never shows suggestions for an older query.
 */
export function useIcdSearch(query: string, options: { enabled?: boolean; size?: number } = {}): IcdSearchState {
  const enabled = options.enabled ?? true;
  const size = options.size ?? ICD_SEARCH_SIZE;
  const wanted = enabled ? normaliseIcdQuery(query) : '';
  const searchable = wanted.length >= ICD_SEARCH_MIN_CHARS;

  const [state, setState] = useState<{ results: IcdSearchItem[]; total: number; error: string | null; settledQuery: string }>(
    { results: [], total: 0, error: null, settledQuery: '' },
  );
  const [attempt, setAttempt] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    const gen = ++generation.current;
    if (!searchable) {
      setState(prev => (prev.settledQuery === '' && prev.results.length === 0 && !prev.error
        ? prev
        : { results: [], total: 0, error: null, settledQuery: '' }));
      return;
    }
    const timer = setTimeout(() => {
      searchIcdCodes(wanted, size)
        .then(({ items, total }) => {
          if (gen !== generation.current) return;
          setState({ results: items, total, error: null, settledQuery: wanted });
        })
        .catch(e => {
          if (gen !== generation.current) return;
          setState({ results: [], total: 0, error: apiError(e, 'ICD codes could not be searched.').message, settledQuery: wanted });
        });
    }, ICD_SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [wanted, searchable, size, attempt]);

  const retry = useCallback(() => {
    // Forget the failed answer so the field shows "searching" again while the query is repeated.
    setState({ results: [], total: 0, error: null, settledQuery: '' });
    setAttempt(n => n + 1);
  }, []);

  // Until the answer for the text on screen has arrived, older suggestions are not shown under it.
  const settled = state.settledQuery === wanted;
  return {
    results:      searchable && settled ? state.results : [],
    total:        searchable && settled ? state.total : 0,
    searching:    searchable && !settled,
    error:        searchable && settled ? state.error : null,
    settledQuery: searchable && settled ? state.settledQuery : '',
    retry,
  };
}
