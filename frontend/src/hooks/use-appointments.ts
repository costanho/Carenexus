import { useCallback, useEffect, useRef, useSyncExternalStore } from 'react';
import { apiError, apiErrorMessage } from '@/lib/api/errors';
import { deleteAppointment, listAppointments, moveAppointment } from '@/lib/appointments/api';
import { isActiveStatus, isAwaitingOutcome } from '@/lib/appointments/rules';
import type { AppointmentDto, AppointmentListQuery, AppointmentStatus } from '@/lib/appointments/types';
import { describeCallJoin, type CallJoinInfo } from '@/lib/calls/join';
import type { CallSessionStatus } from '@/lib/calls/types';
import { cachedDoctorName, DOCTOR_NAME_FALLBACK, primeDoctorNames, resolveDoctorNames } from '@/lib/doctors/names';
import { clinicNowLocal, clinicToday, formatClinicDateTime, isAfterClinicNow, toClinicLocal } from '@/lib/time/clinic';
import { useCurrentUser } from './use-current-user';
import { loadActiveDoctors, tidyDoctorName } from './use-doctors';
import { useResource } from './use-resource';

// These helpers now live in @/lib/api/errors and @/lib/time/clinic. They are re-exported so existing imports
// from this hook keep working; new code imports them from the lib modules.
export { apiErrorMessage, clinicNowLocal, clinicToday, formatClinicDateTime, isAfterClinicNow };

export interface Appointment {
  appointment_id:          number;
  patient_id:              number;
  doctor_id:               number;
  facility_id:             number | null;
  scheduled_at:            string;
  duration_minutes:        number;
  type:                    'IN_PERSON' | 'VIDEO' | 'PHONE';
  status:                  'SCHEDULED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW' | 'RESCHEDULED';
  reason_for_visit:        string | null;
  /** Legacy field, always null from the API. Calls use call_join_route. */
  video_consultation_link: string | null;
  call_status:             CallSessionStatus | null;
  call_join_route:         string | null;
  join_window_opens_at:    string | null;
  join_window_closes_at:   string | null;
  reminder_sent:           number;
  notes:                   string | null;
  created_at:              string;
  updated_at:              string;
  /** DOC-… id of the appointment's doctor, used to open a private conversation with them. */
  doctor_system_id:        string | null;
  doctor_name:             string;
  doctor_specialization:   string | null;
  doctor_avatar_url:       string | null;
  /** Where an in-person visit takes place; null when no location was chosen. */
  facility_name:           string | null;
  facility_address:        string | null;
  /** The number the doctor calls for a PHONE appointment; null for other types. */
  contact_phone:           string | null;
}

/** Which appointments a list holds. Every filter is applied by the server. */
export type AppointmentFilter =
  | 'ALL'
  /** SCHEDULED or RESCHEDULED and not started yet, soonest first. */
  | 'UPCOMING'
  /** SCHEDULED or RESCHEDULED although the end time has passed: the doctor has not recorded an outcome (US-05). */
  | 'AWAITING_OUTCOME'
  | 'COMPLETED'
  | 'RESCHEDULED'
  | 'CANCELLED'
  | 'NO_SHOW';

export interface UseAppointmentsOptions {
  /** Default 'ALL': the list the dashboard, the sidebar and My Appointments share. */
  filter?:   AppointmentFilter;
  /**
   * A list loaded less than this long ago is shown without another request when a panel mounts. Default 30 s,
   * so moving between sections does not reload it each time; a screen that must be current passes less.
   */
  maxAgeMs?: number;
}

interface State {
  appointments: Appointment[];
  loading:      boolean;
  error:        string | null;
  refetch:      () => void;
  /** True while `refetch` reloads a list that is already on screen. */
  refreshing:   boolean;
  /** True when the server has more appointments for this filter than are loaded. */
  hasMore:      boolean;
  /** Loads the next page. Does nothing while a request is running or when everything is loaded. */
  loadMore:     () => void;
  loadingMore:  boolean;
  /** Number of appointments on the server for this filter. */
  total:        number;
}

/**
 * Builds the wall-clock LocalDateTime string the backend expects (no offset).
 * The backend interprets it in the clinic's time zone.
 */
export function toScheduledAt(date: string, time: string): string {
  return `${date}T${time}:00`;
}

/** True when the given clinic date (YYYY-MM-DD) and time (HH:mm) are later than the clinic's current time. */
export function isFutureSlot(date: string, time: string): boolean {
  if (!date || !time) return false;
  return isAfterClinicNow(toScheduledAt(date, time));
}

/** SCHEDULED/RESCHEDULED appointments that have not started yet (in clinic time). */
export function isUpcomingAppointment(a: Appointment): boolean {
  if (a.status !== 'SCHEDULED' && a.status !== 'RESCHEDULED') return false;
  return isAfterClinicNow(a.scheduled_at);
}

/**
 * True for a SCHEDULED/RESCHEDULED appointment whose end has passed in clinic time. It is shown as
 * "Awaiting outcome", not "Scheduled", and offers no reschedule or cancel.
 */
export function isAppointmentAwaitingOutcome(a: Pick<Appointment, 'status' | 'scheduled_at' | 'duration_minutes'>): boolean {
  return isAwaitingOutcome(a.status, a.scheduled_at, a.duration_minutes);
}

/** The patient's Join control for an appointment (internal call route), or null for none. */
export function appointmentCallJoin(a: Appointment, now = Date.now()): CallJoinInfo | null {
  return describeCallJoin({
    type:               a.type,
    status:             a.status,
    callStatus:         a.call_status,
    callJoinRoute:      a.call_join_route,
    joinWindowOpensAt:  a.join_window_opens_at,
    joinWindowClosesAt: a.join_window_closes_at,
  }, false, now);
}

// ─── View model ───────────────────────────────────────────────────────────────

/** Specialization by doctor id, from the doctor directory (the appointment rows do not carry it). */
const specializations = new Map<string, string | null>();

function givenDoctorName(a: AppointmentDto): string {
  return tidyDoctorName(a.doctorName);
}

/**
 * The view model of one API row, for a screen that loads a single appointment itself (for example the one a
 * notification links to). The cached lists are built with it too.
 */
export function toAppointment(a: AppointmentDto): Appointment {
  const doctorSystemId = a.doctorSystemId ?? '';
  return {
    appointment_id:          a.appointmentId          ?? 0,
    patient_id:              0,
    doctor_id:               0,
    facility_id:             a.facilityId             ?? null,
    scheduled_at:            a.scheduledAt            ? String(a.scheduledAt) : '',
    duration_minutes:        a.durationMinutes        ?? 30,
    type:                    (a.type                  ?? 'IN_PERSON') as Appointment['type'],
    status:                  (a.status                ?? 'SCHEDULED') as Appointment['status'],
    reason_for_visit:        a.reasonForVisit         ?? null,
    video_consultation_link: null,
    call_status:             a.callStatus             ?? null,
    call_join_route:         a.callJoinRoute          ?? null,
    join_window_opens_at:    a.joinWindowOpensAt      ? String(a.joinWindowOpensAt)  : null,
    join_window_closes_at:   a.joinWindowClosesAt     ? String(a.joinWindowClosesAt) : null,
    reminder_sent:           a.reminderSent           ? 1 : 0,
    notes:                   a.notes                  ?? null,
    created_at:              a.createdAt              ? String(a.createdAt) : '',
    updated_at:              a.updatedAt              ? String(a.updatedAt) : '',
    doctor_system_id:        doctorSystemId || null,
    // Never the raw DOC-... id: the name the row carries, else the name looked up by id, else a neutral label.
    doctor_name:             givenDoctorName(a) || cachedDoctorName(doctorSystemId) || DOCTOR_NAME_FALLBACK,
    doctor_specialization:   specializations.get(doctorSystemId) ?? null,
    doctor_avatar_url:       null,
    facility_name:           a.facilityName           ?? null,
    facility_address:        a.facilityAddress        ?? null,
    contact_phone:           a.contactPhone           ?? null,
  };
}

// ─── Shared appointment lists ─────────────────────────────────────────────────
// The dashboard, the right sidebar and My Appointments all show the signed-in patient's appointments. They
// share one cached list per filter, read page by page from GET /api/appointments, so a booking, reschedule
// or cancel made in one panel shows in every panel, and each list is requested once per screen, not per panel.

/**
 * The unfiltered list loads the largest page: every upcoming appointment is then in it (the booking horizon
 * keeps their number small) and panels that do not page, the native screens included, see recent history.
 */
const ALL_PAGE_SIZE = 100;
const FILTER_PAGE_SIZE = 20;

/** Panels that mount within this window of a load share it; `refetch` always loads. */
const DEFAULT_MAX_AGE_MS = 30_000;

const ACTIVE: readonly AppointmentStatus[] = ['SCHEDULED', 'RESCHEDULED'];

function queryFor(filter: AppointmentFilter, patientSystemId: string, now: string): AppointmentListQuery {
  switch (filter) {
    case 'UPCOMING':         return { patientSystemId, status: ACTIVE, from: now, order: 'asc' };
    case 'AWAITING_OUTCOME': return { patientSystemId, status: ACTIVE, to: now, order: 'desc' };
    case 'ALL':              return { patientSystemId };
    default:                 return { patientSystemId, status: [filter] };
  }
}

function rowAwaitsOutcome(a: AppointmentDto, now: string): boolean {
  return isAwaitingOutcome(a.status, a.scheduledAt, a.durationMinutes ?? 30, now);
}

/**
 * Whether a row belongs in a filter's list. For AWAITING_OUTCOME the server query (started before `now`) also
 * returns visits still in progress; only those whose end has passed belong, the rule the card label uses.
 */
function belongsIn(filter: AppointmentFilter, a: AppointmentDto, now: string): boolean {
  const start = toClinicLocal(a.scheduledAt) ?? '';
  switch (filter) {
    case 'ALL':              return true;
    case 'UPCOMING':         return isActiveStatus(a.status) && start >= now;
    case 'AWAITING_OUTCOME': return rowAwaitsOutcome(a, now);
    default:                 return a.status === filter;
  }
}

/** The rows of a page that belong in the store's list; the server query is wider only for AWAITING_OUTCOME. */
function keepRows(store: Store, rows: AppointmentDto[], now: string): AppointmentDto[] {
  return store.filter === 'AWAITING_OUTCOME' ? rows.filter(r => rowAwaitsOutcome(r, now)) : rows;
}

interface Snapshot {
  /** Patient system id the list belongs to; null before the first load. */
  key:          string | null;
  appointments: Appointment[];
  /** True only until the first list for `key` arrives; refreshes keep showing the old list. */
  loading:      boolean;
  refreshing:   boolean;
  loadingMore:  boolean;
  error:        string | null;
  hasMore:      boolean;
  total:        number;
}

interface Store {
  filter:       AppointmentFilter;
  pageSize:     number;
  snapshot:     Snapshot;
  /** The rows as the API sent them, in list order; the view models are rebuilt from these. */
  rows:         AppointmentDto[];
  nextPage:     number;
  totalPages:   number;
  /** The clinic time the first page was asked with; later pages reuse it so the pages line up. */
  anchor:       string;
  /** Rows the server returned for this list that do not belong in it (visits still in progress), kept out of `total`. */
  dropped:      number;
  startedAt:    number;
  /** A change made elsewhere may belong in this list: reload it the next time it is shown. */
  stale:        boolean;
  inFlight:     { key: string; promise: Promise<void> } | null;
  busyMore:     boolean;
  generation:   number;
  listeners:    Set<() => void>;
  subscribe:    (listener: () => void) => () => void;
  getSnapshot:  () => Snapshot;
}

const EMPTY: Snapshot = {
  key: null, appointments: [], loading: true, refreshing: false, loadingMore: false, error: null, hasMore: false, total: 0,
};

const stores = new Map<AppointmentFilter, Store>();

function storeFor(filter: AppointmentFilter): Store {
  const existing = stores.get(filter);
  if (existing) return existing;
  const store: Store = {
    filter,
    pageSize:   filter === 'ALL' ? ALL_PAGE_SIZE : FILTER_PAGE_SIZE,
    snapshot:   EMPTY,
    rows:       [],
    nextPage:   0,
    totalPages: 0,
    anchor:     '',
    dropped:    0,
    startedAt:  0,
    stale:      false,
    inFlight:   null,
    busyMore:   false,
    generation: 0,
    listeners:  new Set(),
    subscribe:  listener => {
      store.listeners.add(listener);
      return () => { store.listeners.delete(listener); };
    },
    getSnapshot: () => store.snapshot,
  };
  stores.set(filter, store);
  return store;
}

function publish(store: Store, next: Snapshot) {
  store.snapshot = next;
  store.listeners.forEach(l => l());
}

/** List order: soonest first for upcoming appointments, newest first everywhere else (as the server sorts). */
function sortRows(store: Store) {
  const direction = store.filter === 'UPCOMING' ? 1 : -1;
  store.rows.sort((a, b) => {
    const byTime = String(a.scheduledAt).localeCompare(String(b.scheduledAt));
    return direction * (byTime !== 0 ? byTime : a.appointmentId - b.appointmentId);
  });
}

function withRows(store: Store, patch: Partial<Snapshot> = {}): Snapshot {
  return {
    ...store.snapshot,
    ...patch,
    appointments: store.rows.map(toAppointment),
    hasMore:      store.nextPage < store.totalPages,
  };
}

function appendUnique(existing: AppointmentDto[], incoming: AppointmentDto[]): AppointmentDto[] {
  const seen = new Set(existing.map(r => r.appointmentId));
  const out = existing.slice();
  for (const row of incoming) {
    if (seen.has(row.appointmentId)) continue;
    seen.add(row.appointmentId);
    out.push(row);
  }
  return out;
}

/**
 * Makes sure every doctor on the rows has a display name. Rows carry `doctorName`; a row without one is looked
 * up by id (resolveDoctorNames never rejects and never yields the id), and the list is rebuilt when it settles.
 */
function resolveNames(store: Store, key: string, generation: number) {
  primeDoctorNames(store.rows.map(r => ({ doctor_system_id: r.doctorSystemId, full_name: givenDoctorName(r) })));
  const missing = Array.from(new Set(
    store.rows.filter(r => !givenDoctorName(r) && !cachedDoctorName(r.doctorSystemId)).map(r => r.doctorSystemId),
  )).filter(Boolean);
  if (missing.length === 0) return;
  void resolveDoctorNames(missing).then(found => {
    if (generation !== store.generation || store.snapshot.key !== key) return;
    if (Array.from(found.values()).every(name => name === DOCTOR_NAME_FALLBACK)) return;
    publish(store, withRows(store));
  });
}

/** Fills the specialization map from the doctor directory. The list never waits on it and never fails with it. */
function loadSpecializations(): Promise<void> {
  return loadActiveDoctors()
    .then(doctors => {
      doctors.forEach(d => { if (d.doctor_system_id) specializations.set(d.doctor_system_id, d.specialization); });
    })
    .catch(() => { /* the cards simply show no specialization */ });
}

function loadList(store: Store, key: string, options: { force?: boolean; maxAgeMs?: number } = {}): Promise<void> {
  const force = options.force ?? false;
  if (!force && store.inFlight?.key === key) return store.inFlight.promise;
  const maxAge = options.maxAgeMs ?? DEFAULT_MAX_AGE_MS;
  const fresh = store.snapshot.key === key && !store.snapshot.error && !store.stale
    && store.startedAt > 0 && Date.now() - store.startedAt < maxAge;
  if (!force && fresh) return Promise.resolve();

  if (store.snapshot.key !== key) {
    // Another account (or the first load): never show the previous patient's list.
    store.rows = [];
    store.nextPage = 0;
    store.totalPages = 0;
    publish(store, { ...EMPTY, key });
  } else if (!store.snapshot.loading) {
    publish(store, { ...store.snapshot, refreshing: true });
  }
  store.startedAt = Date.now();
  store.stale = false;
  store.busyMore = false;

  const generation = ++store.generation;
  const anchor = clinicNowLocal();
  // Specializations come from the doctor directory and only decorate the cards: the list is published as soon
  // as its own page arrives, and rebuilt once they are in.
  void loadSpecializations().then(() => {
    if (generation !== store.generation || store.snapshot.key !== key || store.snapshot.loading) return;
    publish(store, withRows(store));
  });
  const promise = listAppointments(queryFor(store.filter, key, anchor), 0, store.pageSize)
    .then(page => {
      if (generation !== store.generation) return;
      const content = page.content ?? [];
      const kept = keepRows(store, content, anchor);
      store.rows = appendUnique([], kept);
      store.anchor = anchor;
      store.dropped = content.length - kept.length;
      store.nextPage = 1;
      store.totalPages = page.totalPages ?? 0;
      publish(store, withRows(store, {
        key, loading: false, refreshing: false, loadingMore: false, error: null,
        total: Math.max(store.rows.length, (page.totalElements ?? store.rows.length) - store.dropped),
      }));
      resolveNames(store, key, generation);
    })
    .catch(e => {
      if (generation !== store.generation) return;
      // The rows already on screen stay; the screen shows the error above them.
      publish(store, {
        ...store.snapshot, loading: false, refreshing: false, loadingMore: false,
        error: apiError(e, 'Failed to load appointments.').message,
      });
    })
    .finally(() => { if (store.inFlight?.promise === promise) store.inFlight = null; });
  store.inFlight = { key, promise };
  return promise;
}

function loadMoreOf(store: Store) {
  const key = store.snapshot.key;
  if (!key || store.busyMore || store.inFlight || store.snapshot.loading || store.nextPage >= store.totalPages) return;
  const generation = store.generation;
  const pageToLoad = store.nextPage;
  store.busyMore = true;
  publish(store, { ...store.snapshot, loadingMore: true, error: null });
  listAppointments(queryFor(store.filter, key, store.anchor || clinicNowLocal()), pageToLoad, store.pageSize)
    .then(page => {
      if (generation !== store.generation) return;
      const content = page.content ?? [];
      const kept = keepRows(store, content, store.anchor || clinicNowLocal());
      store.rows = appendUnique(store.rows, kept);
      store.dropped += content.length - kept.length;
      store.nextPage = pageToLoad + 1;
      store.totalPages = page.totalPages ?? store.totalPages;
      const total = page.totalElements != null
        ? Math.max(store.rows.length, page.totalElements - store.dropped)
        : store.snapshot.total;
      publish(store, withRows(store, { loadingMore: false, total }));
      resolveNames(store, key, generation);
    })
    .catch(e => {
      if (generation !== store.generation) return;
      // The rows stay; asking for more again retries the same page.
      publish(store, { ...store.snapshot, loadingMore: false, error: apiError(e, 'Failed to load more appointments.').message });
    })
    .finally(() => { if (generation === store.generation) store.busyMore = false; });
}

// ─── Applying changes without a reload ────────────────────────────────────────

/** Bumped by every change, so figures derived from the lists (the stat cards) know to refresh. */
let changeVersion = 0;
const versionListeners = new Set<() => void>();

function subscribeVersion(listener: () => void): () => void {
  versionListeners.add(listener);
  return () => { versionListeners.delete(listener); };
}

function getVersion(): number {
  return changeVersion;
}

function bumpVersion() {
  changeVersion += 1;
  versionListeners.forEach(l => l());
}

/**
 * Puts the result of a booking, reschedule or edit (the appointment the API returned) into every cached list
 * of that patient: the row is replaced where it is listed, added where it now belongs and dropped from a
 * filtered list it no longer matches. No request is made, so the screen is right even when a reload would fail.
 * Pass `created: true` for a new booking, so the totals count it.
 */
export function applyAppointmentUpdate(updated: AppointmentDto, options: { created?: boolean } = {}): void {
  if (!updated || typeof updated.appointmentId !== 'number') return;
  const now = clinicNowLocal();
  stores.forEach(store => {
    if (store.snapshot.key !== updated.patientSystemId || store.snapshot.loading) return;
    const index = store.rows.findIndex(r => r.appointmentId === updated.appointmentId);
    const belongs = belongsIn(store.filter, updated, now);
    if (index < 0 && !belongs) return;

    let total = store.snapshot.total;
    if (index >= 0 && belongs) {
      store.rows = store.rows.map(r => (r.appointmentId === updated.appointmentId ? updated : r));
    } else if (index >= 0) {
      store.rows = store.rows.filter(r => r.appointmentId !== updated.appointmentId);
      total = Math.max(0, total - 1);
    } else {
      store.rows = store.rows.concat(updated);
      // An existing appointment that was simply not loaded yet is already in the unfiltered total.
      if (options.created || store.filter !== 'ALL') total += 1;
    }
    sortRows(store);
    publish(store, withRows(store, { total }));
    resolveNames(store, updated.patientSystemId, store.generation);
  });
  bumpVersion();
}

/** Marks an appointment CANCELLED in the cached lists (DELETE answers with no body to apply). */
function applyCancelled(appointmentId: number) {
  for (const store of stores.values()) {
    const row = store.rows.find(r => r.appointmentId === appointmentId);
    if (row) {
      applyAppointmentUpdate({ ...row, status: 'CANCELLED' });
      return;
    }
  }
  // Not in any loaded list: the lists cannot be patched, so they reload the next time they are shown.
  stores.forEach(store => { store.stale = true; });
  bumpVersion();
}

/** Soft-cancels an appointment (backend sets status CANCELLED). Throws the axios error on failure. */
export async function cancelAppointment(appointmentId: number): Promise<void> {
  await deleteAppointment(appointmentId);
  applyCancelled(appointmentId);
}

/** Moves an appointment to a new local date/time via PUT /api/appointments/{id}. Throws the axios error on failure. */
export async function rescheduleAppointment(appointmentId: number, date: string, time: string): Promise<void> {
  applyAppointmentUpdate(await moveAppointment(appointmentId, toScheduledAt(date, time)));
}

/**
 * Re-fetches the shared appointment lists now (call after a change whose result is not at hand).
 * Every mounted useAppointments() consumer updates; resolves once the shared list is in. Never rejects: a
 * failed reload keeps the rows on screen and reports through each list's `error`.
 */
export function invalidateAppointments(): Promise<void> {
  const reloads: Promise<void>[] = [];
  stores.forEach(store => {
    const key = store.snapshot.key;
    if (!key) return;
    if (store.listeners.size > 0 || store.filter === 'ALL') reloads.push(loadList(store, key, { force: true }));
    else store.stale = true;
  });
  bumpVersion();
  return Promise.all(reloads).then(() => undefined);
}

export function useAppointments(options: UseAppointmentsOptions = {}): State {
  const { user, loading: userLoading, error: userError, refetch: refetchUser } = useCurrentUser();
  const store = storeFor(options.filter ?? 'ALL');
  const maxAgeMs = options.maxAgeMs;
  const snap = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  const key  = user?.system_id ?? null;

  useEffect(() => {
    if (key) loadList(store, key, { maxAgeMs });
    // `maxAgeMs` only decides whether mounting reloads; a changed value must not reload by itself.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, store]);

  // Without a signed-in user there is no list to reload: Retry loads the account again, and the effect above
  // fetches the appointments once it arrives.
  const refetch = useCallback(() => {
    if (key) loadList(store, key, { force: true });
    else refetchUser();
  }, [key, store, refetchUser]);

  const loadMore = useCallback(() => loadMoreOf(store), [store]);

  const idle = { refreshing: false, hasMore: false, loadMore, loadingMore: false, total: 0 };
  if (!key) {
    const accountError = userLoading ? null : (userError ?? 'Could not load your account.');
    return { appointments: [], loading: !accountError, error: accountError, refetch, ...idle };
  }
  // Until this patient's own list arrives, report loading rather than another account's data.
  if (snap.key !== key) return { appointments: [], loading: true, error: null, refetch, ...idle };
  return {
    appointments: snap.appointments,
    loading:      snap.loading,
    error:        snap.error,
    refetch,
    refreshing:   snap.refreshing,
    hasMore:      snap.hasMore,
    loadMore,
    loadingMore:  snap.loadingMore,
    total:        snap.total,
  };
}

// ─── Totals for the stat cards ────────────────────────────────────────────────

export interface AppointmentCounts {
  total:     number;
  /** SCHEDULED or RESCHEDULED and not started yet. */
  upcoming:  number;
  completed: number;
  /** CANCELLED and NO_SHOW together. */
  cancelled: number;
}

async function countAppointments(query: AppointmentListQuery): Promise<number> {
  return (await listAppointments(query, 0, 1)).totalElements;
}

/**
 * The signed-in patient's appointment totals, counted by the server (`totalElements` of one-row pages), so they
 * are right however many appointments are loaded. They refresh after every change made through this module.
 */
export function useAppointmentCounts(): { counts: AppointmentCounts | null; loading: boolean; error: string | null; refetch: () => void } {
  const { user } = useCurrentUser();
  const key = user?.system_id ?? null;
  const version = useSyncExternalStore(subscribeVersion, getVersion, getVersion);

  const { data, loading, error, refetch } = useResource<AppointmentCounts>(
    async () => {
      const patientSystemId = key ?? '';
      const [total, upcoming, completed, cancelled] = await Promise.all([
        countAppointments({ patientSystemId }),
        countAppointments(queryFor('UPCOMING', patientSystemId, clinicNowLocal())),
        countAppointments({ patientSystemId, status: ['COMPLETED'] }),
        countAppointments({ patientSystemId, status: ['CANCELLED', 'NO_SHOW'] }),
      ]);
      return { total, upcoming, completed, cancelled };
    },
    [key],
    { enabled: !!key, errorMessage: 'Could not load your appointment totals.' },
  );

  // A change made after this panel mounted reloads the figures; the current ones stay on screen meanwhile.
  const seenVersion = useRef(version);
  useEffect(() => {
    if (seenVersion.current === version) return;
    seenVersion.current = version;
    refetch();
  }, [version, refetch]);

  return { counts: data, loading, error, refetch };
}
