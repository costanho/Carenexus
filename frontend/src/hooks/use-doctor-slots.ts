import { useCallback, useEffect, useSyncExternalStore } from 'react';
import { apiErrorMessage } from '@/lib/api/errors';
import { getBookingRules, getDoctorSlots } from '@/lib/appointments/api';
import { DEFAULT_BOOKING_RULES } from '@/lib/appointments/rules';
import type { BookingRules, DoctorSlot, DoctorSlots } from '@/lib/appointments/types';
import { useResource } from './use-resource';

// Availability for slot pickers: the clinic's booking rules (loaded once and shared) and the bookable start
// times of one doctor on one clinic day (loaded per doctor, date and duration).

// ─── Booking rules ────────────────────────────────────────────────────────────

/** The rules change only when an administrator reconfigures the clinic, so one load serves the whole session. */
const RULES_STALE_MS = 10 * 60_000;

interface RulesSnapshot {
  rules:   BookingRules;
  /** True once the server's rules are in; until then `rules` holds the defaults. */
  loaded:  boolean;
  loading: boolean;
  error:   string | null;
}

let rulesSnapshot: RulesSnapshot = { rules: DEFAULT_BOOKING_RULES, loaded: false, loading: true, error: null };
let rulesLoadedAt = 0;
let rulesInFlight: Promise<BookingRules> | null = null;
const rulesListeners = new Set<() => void>();

function setRulesSnapshot(next: RulesSnapshot) {
  rulesSnapshot = next;
  rulesListeners.forEach(l => l());
}

function subscribeRules(listener: () => void): () => void {
  rulesListeners.add(listener);
  return () => { rulesListeners.delete(listener); };
}

function getRulesSnapshot(): RulesSnapshot {
  return rulesSnapshot;
}

/**
 * The clinic's booking rules (GET /api/appointments/booking-rules), from the shared cache unless `force` is
 * set. Concurrent callers share one request. Rejects with the axios error on failure.
 */
export function loadBookingRules(force = false): Promise<BookingRules> {
  if (rulesInFlight) return rulesInFlight;
  if (!force && rulesSnapshot.loaded && Date.now() - rulesLoadedAt < RULES_STALE_MS) {
    return Promise.resolve(rulesSnapshot.rules);
  }
  if (!rulesSnapshot.loaded) setRulesSnapshot({ ...rulesSnapshot, loading: true, error: null });
  rulesInFlight = getBookingRules()
    .then(data => {
      // Missing fields (an older server) fall back to the defaults rather than to undefined.
      const rules: BookingRules = { ...DEFAULT_BOOKING_RULES, ...(data ?? {}) };
      rulesLoadedAt = Date.now();
      setRulesSnapshot({ rules, loaded: true, loading: false, error: null });
      return rules;
    })
    .catch(e => {
      setRulesSnapshot({
        ...rulesSnapshot,
        loading: false,
        error:   apiErrorMessage(e, "Could not load the clinic's booking rules."),
      });
      throw e;
    })
    .finally(() => { rulesInFlight = null; });
  return rulesInFlight;
}

export interface BookingRulesState extends RulesSnapshot {
  /** Loads the rules again (after a failure, or when the server said a rule changed). */
  refetch: () => void;
}

/**
 * `const { rules, loaded, error, refetch } = useBookingRules();`
 * `rules` is always usable: the server defaults stand in until the first load and after a failed one, and the
 * server re-checks every booking anyway.
 */
export function useBookingRules(): BookingRulesState {
  const state = useSyncExternalStore(subscribeRules, getRulesSnapshot, getRulesSnapshot);

  useEffect(() => {
    loadBookingRules().catch(() => { /* surfaced through state.error */ });
  }, []);

  const refetch = useCallback(() => {
    loadBookingRules(true).catch(() => { /* surfaced through state.error */ });
  }, []);

  return { ...state, refetch };
}

// ─── Slots of one doctor on one day ───────────────────────────────────────────

export interface DoctorSlotsOptions {
  doctorSystemId:        string | null | undefined;
  /** Clinic calendar day, "yyyy-MM-dd". */
  date:                  string | null | undefined;
  /** Appointment length the times are computed for. */
  durationMinutes:       number;
  /** The appointment being rescheduled, so it does not block its own time. */
  excludeAppointmentId?: number | null;
}

export interface DoctorSlotsState {
  /** The whole response, or null until loaded. */
  data:       DoctorSlots | null;
  /** Every start time of the day in order; entries with `available: false` are shown disabled. */
  slots:      DoctorSlot[];
  /** False until a doctor and a date are chosen. */
  enabled:    boolean;
  loading:    boolean;
  refreshing: boolean;
  error:      string | null;
  /** Loads the day again: after a booking, and after the server refused a time (409 or DOCTOR_UNAVAILABLE). */
  refetch:    () => void;
}

const NO_SLOTS: DoctorSlot[] = [];

/**
 * Bookable start times from GET /api/appointments/doctor/{id}/slots. Loads again whenever the doctor, the date,
 * the duration or the excluded appointment changes; nothing is requested until a doctor and a date are set.
 */
export function useDoctorSlots({ doctorSystemId, date, durationMinutes, excludeAppointmentId }: DoctorSlotsOptions): DoctorSlotsState {
  const doctor = (doctorSystemId ?? '').trim();
  const day = (date ?? '').trim();
  const exclude = excludeAppointmentId ?? null;
  const enabled = !!doctor && /^\d{4}-\d{2}-\d{2}$/.test(day) && durationMinutes > 0;

  const state = useResource<DoctorSlots>(
    () => getDoctorSlots(doctor, day, { durationMinutes, excludeAppointmentId: exclude }),
    [doctor, day, durationMinutes, exclude],
    { enabled, errorMessage: 'Could not load the available times.' },
  );

  return {
    data:       state.data,
    slots:      state.data?.slots ?? NO_SLOTS,
    enabled,
    loading:    state.loading,
    refreshing: state.refreshing,
    error:      state.error,
    refetch:    state.refetch,
  };
}
