import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { fetchActivityPage, fetchDependents } from '@/lib/caregiver/api';
import type {
  ActivityQuery, CaregiverAppointment, CaregiverConsultation, CaregiverDependent,
} from '@/lib/caregiver/types';
import { cachedDoctorName, DOCTOR_NAME_FALLBACK, resolveDoctorNames } from '@/lib/doctors/names';
import { useCurrentUser } from './use-current-user';

// The grant and activity types live in lib/caregiver/types; they are re-exported here under the names web and
// native screens already import from this hook.
export type {
  AccessLevel, AccessStatus, CallStatus, CaregiverAppointment, CaregiverConsultation, CaregiverDependent, HealthStatus,
} from '@/lib/caregiver/types';

// ─── Shared helpers for caregiver screens ─────────────────────────────────────

export const NO_ACCESS_MESSAGE = "You don't have access to this information.";

const ACCOUNT_ERROR = 'Failed to load your account.';

/** True when the request was refused because the caregiver lacks permission (HTTP 403). */
export function isForbidden(err: unknown): boolean {
  return (err as any)?.response?.status === 403;
}

/** Maps an API error to a message safe to show the caregiver. 403 always maps to NO_ACCESS_MESSAGE. */
export function caregiverErrorMessage(err: unknown, fallback: string): string {
  if (isForbidden(err)) return NO_ACCESS_MESSAGE;
  return fallback;
}

/**
 * Maps an error from a caregiver SAVE (profile, health or access edit) to a message. Unlike reads, a refused
 * write carries a specific reason (e.g. "You can only reduce your own access..."), so the backend message is shown.
 */
export function caregiverSaveErrorMessage(err: unknown, fallback: string): string {
  const response = (err as any)?.response;
  if (!response) return 'Unable to reach the server. Please check your connection.';
  const message = response.data?.message;
  if (typeof message === 'string' && message.trim() && response.status < 500) return message;
  return isForbidden(err) ? NO_ACCESS_MESSAGE : fallback;
}

/** Parses a JSON-encoded string list (allergies, chronicConditions). Never throws. */
export { parseList } from '@/lib/caregiver/profile';

// ─── Dependents ───────────────────────────────────────────────────────────────

interface State {
  dependents: CaregiverDependent[];
  loading:    boolean;
  error:      string | null;
  refetch:    () => void;
}

/**
 * The signed-in caregiver's active grants (GET /api/caregiver/dependents). Rows carry every field of
 * `CaregiverDependent`, including the billing flags, `managed`, `canInvite` and `patientPhone`.
 */
export function useCaregiverDependents(): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const [dependents, setDependents] = useState<CaregiverDependent[]>([]);
  const [loading,    setLoading]    = useState(true);
  const [error,      setError]      = useState<string | null>(null);
  const [tick,       setTick]       = useState(0);

  useEffect(() => {
    if (!user) {
      if (userLoading) {
        // The account is (re)loading: show the spinner, not the error of the attempt before.
        setLoading(true);
        setError(null);
      } else {
        // The signed-in user could not be loaded: stop loading and say so instead of spinning forever
        setLoading(false);
        setError(userError ?? ACCOUNT_ERROR);
      }
      return;
    }
    let cancelled = false;
    setLoading(true);

    fetchDependents()
      .then(rows => {
        if (cancelled) return;
        setDependents(rows);
        setError(null);
      })
      .catch(err => { if (!cancelled) setError(caregiverErrorMessage(err, 'Failed to load loved ones.')); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [user?.system_id, userLoading, userError, tick]);

  // Retry also reloads the account when that is what failed; otherwise it could never succeed.
  const refetch = useCallback(() => {
    if (!user) refetchUser();
    setTick(t => t + 1);
  }, [user, refetchUser]);

  return { dependents, loading, error, refetch };
}

// ─── Activity (appointments + consultations across all dependents) ────────────

export interface CaregiverActivityOptions extends ActivityQuery {
  /** Rows per request for each list, 1 to 100. Default 100. */
  pageSize?: number;
}

interface ActivityState {
  /** Appointments loaded so far, newest first. Rows carry `doctorName`. */
  appointments:  CaregiverAppointment[];
  /** Consultations loaded so far, newest first. Rows carry `doctorName`. */
  consultations: CaregiverConsultation[];
  loading:       boolean;
  error:         string | null;
  refetch:       () => void;
  /** True when the server has more appointments or consultations in the window than are loaded. */
  hasMore:       boolean;
  /** True while `loadMore` is fetching the next page. */
  loadingMore:   boolean;
  /** Loads the next page of both lists. Does nothing while a request runs or when nothing is left. */
  loadMore:      () => void;
  /** The window the server applied: clinic dates "YYYY-MM-DD"; `to` null means open (all future rows). */
  from:          string | null;
  to:            string | null;
  /** Rows on the server for the window. */
  totalAppointments:  number;
  totalConsultations: number;
}

const ACTIVITY_PAGE_SIZE = 100;
const ACTIVITY_ERROR = 'Failed to load appointments and activity.';

interface ActivityInner {
  appointments:  CaregiverAppointment[];
  consultations: CaregiverConsultation[];
  from:          string | null;
  to:            string | null;
  /** Last page loaded; -1 before the first one. */
  page:          number;
  totalPages:    number;
  totalAppointments:  number;
  totalConsultations: number;
}

const EMPTY_ACTIVITY: ActivityInner = {
  appointments: [], consultations: [], from: null, to: null, page: -1, totalPages: 0,
  totalAppointments: 0, totalConsultations: 0,
};

type WithDoctor = { doctorSystemId: string | null; doctorName?: string | null };

/** Fills `doctorName` from the row itself or the name cache; "Your doctor" until a lookup settles. */
function nameDoctors<T extends WithDoctor>(rows: T[]): T[] {
  return rows.map(row => {
    const given = (row.doctorName ?? '').trim();
    if (given) return row;
    if (!row.doctorSystemId) return { ...row, doctorName: null };
    return { ...row, doctorName: cachedDoctorName(row.doctorSystemId) ?? DOCTOR_NAME_FALLBACK };
  });
}

function applyDoctorNames<T extends WithDoctor>(rows: T[], names: Map<string, string>): T[] {
  return rows.map(row => {
    const name = row.doctorSystemId ? names.get(row.doctorSystemId.trim()) : undefined;
    return name && name !== row.doctorName ? { ...row, doctorName: name } : row;
  });
}

function appendUnique<T>(existing: T[], incoming: T[], keyOf: (row: T) => number): T[] {
  const seen = new Set(existing.map(keyOf));
  return existing.concat(incoming.filter(row => !seen.has(keyOf(row))));
}

/**
 * Appointments and consultations of every loved one whose grant shares them, read page by page from
 * GET /api/caregiver/dependents/activity. Without options the window is the server default (the last 90 days
 * plus everything in the future); pass `from` / `to` (clinic dates) to choose another. A screen that lists the
 * rows shows a "Load more" control while `hasMore` is true.
 */
export function useCaregiverActivity(options: CaregiverActivityOptions = {}): ActivityState {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const from = options.from?.trim() || undefined;
  const to   = options.to?.trim() || undefined;
  const pageSize = Math.min(100, Math.max(1, Math.floor(options.pageSize ?? ACTIVITY_PAGE_SIZE)));

  const [data,        setData]        = useState<ActivityInner>(EMPTY_ACTIVITY);
  const [loading,     setLoading]     = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error,       setError]       = useState<string | null>(null);
  const [tick,        setTick]        = useState(0);

  // Bumped whenever the query restarts; a response from an older generation is dropped.
  const generation = useRef(0);
  const busy = useRef(false);
  const dataRef = useRef(data);
  dataRef.current = data;

  const load = useCallback(async (page: number, gen: number) => {
    const result = await fetchActivityPage(page, pageSize, { from, to });
    if (gen !== generation.current) return;
    const appointments  = nameDoctors(result.appointments.content);
    const consultations = nameDoctors(result.consultations.content);
    setData(prev => ({
      appointments:  page === 0 ? appointments  : appendUnique(prev.appointments, appointments, a => a.appointmentId),
      consultations: page === 0 ? consultations : appendUnique(prev.consultations, consultations, c => c.consultationId),
      from:          result.from,
      to:            result.to,
      page,
      totalPages:    Math.max(result.appointments.totalPages, result.consultations.totalPages),
      totalAppointments:  result.appointments.totalElements,
      totalConsultations: result.consultations.totalElements,
    }));
    setError(null);

    // The feed may leave the doctor's name out, so it is looked up here (cached; never the raw system id).
    const ids = [...appointments, ...consultations]
      .filter(row => row.doctorSystemId && !cachedDoctorName(row.doctorSystemId))
      .map(row => row.doctorSystemId);
    if (ids.length === 0) return;
    void resolveDoctorNames(ids).then(names => {
      if (gen !== generation.current) return;
      setData(prev => ({
        ...prev,
        appointments:  applyDoctorNames(prev.appointments, names),
        consultations: applyDoctorNames(prev.consultations, names),
      }));
    });
  }, [from, to, pageSize]);

  useEffect(() => {
    const gen = ++generation.current;
    busy.current = false;
    setLoadingMore(false);
    if (!user) {
      if (userLoading) {
        setLoading(true);
        setError(null);
      } else {
        setLoading(false);
        setError(userError ?? ACCOUNT_ERROR);
      }
      return;
    }
    setLoading(true);
    busy.current = true;
    load(0, gen)
      .catch(err => { if (gen === generation.current) setError(caregiverErrorMessage(err, ACTIVITY_ERROR)); })
      .finally(() => {
        if (gen !== generation.current) return;
        busy.current = false;
        setLoading(false);
      });
  }, [user?.system_id, userLoading, userError, tick, load]);

  const hasMore = data.page >= 0 && data.page + 1 < data.totalPages;

  const loadMore = useCallback(() => {
    const current = dataRef.current;
    if (busy.current || current.page < 0 || current.page + 1 >= current.totalPages) return;
    const gen = generation.current;
    busy.current = true;
    setLoadingMore(true);
    load(current.page + 1, gen)
      .catch(err => { if (gen === generation.current) setError(caregiverErrorMessage(err, ACTIVITY_ERROR)); })
      .finally(() => {
        if (gen !== generation.current) return;
        busy.current = false;
        setLoadingMore(false);
      });
  }, [load]);

  const refetch = useCallback(() => {
    if (!user) refetchUser();
    setTick(t => t + 1);
  }, [user, refetchUser]);

  return {
    appointments:  data.appointments,
    consultations: data.consultations,
    loading,
    error,
    refetch,
    hasMore,
    loadingMore,
    loadMore,
    from: data.from,
    to:   data.to,
    totalAppointments:  data.totalAppointments,
    totalConsultations: data.totalConsultations,
  };
}

// ─── One request per loved one ────────────────────────────────────────────────

export interface DependentDataOptions {
  /**
   * Whether the grant shares this kind of data, for example `d => effectivePermission(d, 'canViewLabResults')`.
   * Loved ones for whom it returns false are not requested and are listed in `deniedDependentIds`.
   */
  allowed?: (dependent: CaregiverDependent) => boolean;
  /** Values the loader reads besides the loved one (a filter, a date); a change reloads everyone. */
  deps?:    DependencyList;
}

export interface DependentDataState<T> {
  /** The loaded value per patient system id, for every loved one whose request succeeded. */
  byPatient: Record<string, T>;
  /** Loved ones whose grant does not share this data, or for whom the server answered 403. */
  deniedDependentIds: string[];
  /** Loved ones whose request failed for another reason; `retryFailed` asks for these again. */
  failedDependentIds: string[];
  /** True while any request is running. */
  loading: boolean;
  /** Loads everyone again. */
  refetch: () => void;
  /** Loads only the loved ones in `failedDependentIds` again, keeping what is already on screen. */
  retryFailed: () => void;
}

interface DependentDataInner<T> {
  byPatient: Record<string, T>;
  denied:    string[];
  failed:    string[];
}

/**
 * Loads one value per loved one (prescriptions, lab results, invoices...) with one request each, so one loved
 * one failing or refusing never hides the others. The screen shows a "not shared with you" note for the ids
 * in `deniedDependentIds` and an error with Retry for the ids in `failedDependentIds`.
 */
export function useDependentData<T>(
  dependents: readonly CaregiverDependent[],
  load: (dependent: CaregiverDependent) => Promise<T>,
  options: DependentDataOptions = {},
): DependentDataState<T> {
  const [state,   setState]   = useState<DependentDataInner<T>>({ byPatient: {}, denied: [], failed: [] });
  const [loading, setLoading] = useState(dependents.length > 0);
  const [tick,    setTick]    = useState(0);
  const [retry,   setRetry]   = useState(0);

  const loadRef = useRef(load);
  loadRef.current = load;
  const allowedRef = useRef(options.allowed);
  allowedRef.current = options.allowed;
  const dependentsRef = useRef(dependents);
  dependentsRef.current = dependents;
  const stateRef = useRef(state);
  stateRef.current = state;
  const generation = useRef(0);
  const lastRetry = useRef(0);
  const lastQuery = useRef<DependencyList | null>(null);

  // Who is loaded depends on the list of loved ones and on whether each grant shares the data.
  const key = dependents
    .map(d => `${d.patientSystemId}:${options.allowed && !options.allowed(d) ? 0 : 1}`)
    .join(',');
  const extra = options.deps ?? [];

  useEffect(() => {
    const gen = ++generation.current;
    // A retry reloads only the failed loved ones, unless the list, the filter or the caller's deps changed too.
    const query: DependencyList = [key, tick, ...extra];
    const sameQuery = lastQuery.current !== null
      && lastQuery.current.length === query.length
      && lastQuery.current.every((value, i) => Object.is(value, query[i]));
    const retryOnly = sameQuery && retry !== lastRetry.current;
    lastQuery.current = query;
    lastRetry.current = retry;

    const everyone = dependentsRef.current;
    const allowed = everyone.filter(d => !allowedRef.current || allowedRef.current(d));
    const notShared = everyone.filter(d => !allowed.includes(d)).map(d => d.patientSystemId);
    const previous = stateRef.current;
    const targets = retryOnly ? allowed.filter(d => previous.failed.includes(d.patientSystemId)) : allowed;

    if (targets.length === 0) {
      if (!retryOnly) setState({ byPatient: {}, denied: notShared, failed: [] });
      setLoading(false);
      return;
    }
    setLoading(true);
    Promise.allSettled(targets.map(d => loadRef.current(d))).then(settled => {
      if (gen !== generation.current) return;
      const byPatient: Record<string, T> = retryOnly ? { ...previous.byPatient } : {};
      const denied = retryOnly ? previous.denied.slice() : notShared.slice();
      const failed: string[] = [];
      settled.forEach((result, i) => {
        const id = targets[i].patientSystemId;
        if (result.status === 'fulfilled') byPatient[id] = result.value;
        else if (isForbidden(result.reason)) { if (!denied.includes(id)) denied.push(id); }
        else failed.push(id);
      });
      setState({ byPatient, denied, failed });
      setLoading(false);
    });
    // `extra` is the caller's dependency list; the loader and the filter are read through refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, tick, retry, ...extra]);

  const refetch = useCallback(() => setTick(t => t + 1), []);
  const retryFailed = useCallback(() => setRetry(r => r + 1), []);

  return {
    byPatient:          state.byPatient,
    deniedDependentIds: state.denied,
    failedDependentIds: state.failed,
    loading,
    refetch,
    retryFailed,
  };
}
