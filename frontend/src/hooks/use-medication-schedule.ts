import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError } from '@/lib/api/errors';
import { getAdherence, listMyDoses, listPatientDoses, markDose } from '@/lib/medication/api';
import { DOSE_STATUS_LABEL, doseName } from '@/lib/medication/dose';
import type { DoseAction, MedicationAdherence, MedicationDose } from '@/lib/medication/types';
import { useResource } from './use-resource';

// Medication schedule of one patient: the doses of a clinic day with the taken / missed / undo mutations, and
// the adherence summary. Mutations are also exported as plain functions (they throw the axios error).

export { markDose, markDoseMissed, markDoseTaken, undoDose } from '@/lib/medication/api';
export type { DoseAction, DoseStatus, MedicationAdherence, MedicationDose } from '@/lib/medication/types';

export interface MedicationScheduleOptions {
  /**
   * True when the signed-in person is the patient: the day is read from /api/medication-schedules/me and no
   * system id is needed. Otherwise the patient's system id is required (a caregiver's loved one).
   */
  self?:    boolean;
  /** When false nothing is requested. Default true. */
  enabled?: boolean;
  /** Called after a dose really changed, for example to reload the adherence summary. */
  onChanged?: (dose: MedicationDose) => void;
  /** Called when the server refuses a dose update (403): the viewer's permission is not what the screen assumed. */
  onMarkDenied?: () => void;
}

export interface MedicationScheduleState {
  /** The day's doses in server order (time, then id). Empty until loaded. */
  doses:       MedicationDose[];
  /** True until the first result for this patient and day arrives. */
  loading:     boolean;
  /** True while a reload keeps the current doses on screen. */
  refreshing:  boolean;
  /** A sentence to show when the day could not be loaded, or null. */
  error:       string | null;
  /** HTTP status of the failed load (0 = no response), or null. */
  errorStatus: number | null;
  /** The server refused to show this patient's schedule (403). */
  noAccess:    boolean;
  refetch:     () => void;
  /**
   * Runs one dose update. Resolves true when the dose changed. A second call while one is running is ignored.
   * The updated dose is applied at once and the day is then read again, so an older reload cannot bring back the
   * previous status.
   */
  mark:        (dose: MedicationDose, action: DoseAction) => Promise<boolean>;
  /** The dose being updated right now, or null. */
  pendingId:   number | null;
  /** The update running on that dose, or null. */
  pendingAction: DoseAction | null;
  /** Why the last update failed, or null. */
  actionError: string | null;
  /** The server refused the last update with 403; the screen should stop offering updates. */
  markDenied:  boolean;
  /** What the last successful update did ("Metformin 850mg at 08:00 is now Taken"), for a live region. */
  announcement: string | null;
  clearActionError: () => void;
}

const LOAD_ERROR   = 'The medication schedule could not be loaded.';
const UPDATE_ERROR = 'This dose could not be updated. Please try again.';

/**
 * `const schedule = useMedicationSchedule(patientSystemId, date, { self })` loads the doses of one clinic day
 * ("yyyy-MM-dd"). A change of patient or day drops the previous doses at once, so one person's doses are never
 * shown under another's name. Dose ids are not kept across reloads (future ids change when a doctor edits the
 * schedule).
 */
export function useMedicationSchedule(
  patientSystemId: string | null | undefined,
  date: string,
  options: MedicationScheduleOptions = {},
): MedicationScheduleState {
  const self = options.self === true;
  const id = (patientSystemId ?? '').trim();
  const enabled = (options.enabled ?? true) && (self || id !== '') && date !== '';

  const resource = useResource<MedicationDose[]>(
    () => (self ? listMyDoses(date) : listPatientDoses(id, date)),
    [self, self ? '' : id, date],
    { enabled, errorMessage: LOAD_ERROR },
  );
  const { refetch, setData } = resource;

  const [pending, setPending]           = useState<{ scheduleId: number; action: DoseAction } | null>(null);
  const [actionError, setActionError]   = useState<string | null>(null);
  const [markDenied, setMarkDenied]     = useState(false);
  const [announcement, setAnnouncement] = useState<string | null>(null);

  const busy = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const onChanged = useRef(options.onChanged);
  onChanged.current = options.onChanged;
  const onMarkDenied = useRef(options.onMarkDenied);
  onMarkDenied.current = options.onMarkDenied;

  // Messages belong to the patient and day they were raised for.
  const scope = `${self ? 'me' : id}|${date}`;
  const scopeRef = useRef(scope);
  scopeRef.current = scope;
  useEffect(() => {
    setActionError(null);
    setAnnouncement(null);
  }, [scope]);
  // A refusal is about the person, not the day: it is forgotten only when the patient changes.
  useEffect(() => { setMarkDenied(false); }, [self, id]);

  const mark = useCallback(async (dose: MedicationDose, action: DoseAction): Promise<boolean> => {
    if (busy.current) return false;
    busy.current = true;
    const startedIn = scopeRef.current;
    setPending({ scheduleId: dose.scheduleId, action });
    setActionError(null);
    setAnnouncement(null);
    try {
      const updated = await markDose(dose.scheduleId, action);
      if (!mounted.current) return true;
      if (scopeRef.current === startedIn) {
        setData(prev => (prev ? prev.map(d => (d.scheduleId === updated.scheduleId ? updated : d)) : prev));
        setAnnouncement(`${doseName(updated)} at ${updated.scheduledTime} is now ${DOSE_STATUS_LABEL[updated.status]}`);
        // A reload that started before the update (the periodic refresh, the Refresh button, the reload after an
        // earlier refused update) may still answer with the old status. Starting a new load makes useResource drop
        // that older answer, and the list is read again from the server, which now has the update.
        refetch();
      }
      onChanged.current?.(updated);
      return true;
    } catch (e) {
      if (!mounted.current) return false;
      const failure = apiError(e, UPDATE_ERROR);
      if (scopeRef.current !== startedIn) return false;
      if (failure.status === 403) {
        // The permission changed since the screen was drawn. Stop offering updates and let the screen re-read access.
        setMarkDenied(true);
        setActionError(failure.message);
        onMarkDenied.current?.();
        refetch();
      } else if (failure.status === 404) {
        // The dose was removed (the schedule was changed, or the prescription was stopped): show the current day.
        setActionError('This dose is no longer on the schedule. The list has been refreshed.');
        refetch();
      } else if (failure.status === 409) {
        // Only doses of today and yesterday can be updated; the day may have rolled over while the page was open.
        setActionError(failure.message);
        refetch();
      } else {
        setActionError(failure.message);
      }
      return false;
    } finally {
      busy.current = false;
      if (mounted.current) setPending(null);
    }
  }, [refetch, setData]);

  const clearActionError = useCallback(() => setActionError(null), []);

  return {
    doses:       resource.data ?? [],
    loading:     resource.loading,
    refreshing:  resource.refreshing,
    error:       resource.error,
    errorStatus: resource.errorStatus,
    noAccess:    resource.errorStatus === 403,
    refetch,
    mark,
    pendingId:     pending?.scheduleId ?? null,
    pendingAction: pending?.action ?? null,
    actionError,
    markDenied,
    announcement,
    clearActionError,
  };
}

export interface MedicationAdherenceState {
  adherence:   MedicationAdherence | null;
  loading:     boolean;
  error:       string | null;
  errorStatus: number | null;
  refetch:     () => void;
}

/**
 * Taken, missed and pending counts of a patient over the last `days` clinic days (default 30, 1 to 90).
 * A patient passes their own system id.
 */
export function useMedicationAdherence(
  patientSystemId: string | null | undefined,
  days = 30,
  options: { enabled?: boolean } = {},
): MedicationAdherenceState {
  const id = (patientSystemId ?? '').trim();
  const window = Math.min(90, Math.max(1, Math.floor(days) || 30));
  const resource = useResource<MedicationAdherence>(
    () => getAdherence(id, window),
    [id, window],
    { enabled: (options.enabled ?? true) && id !== '', errorMessage: 'The adherence summary could not be loaded.' },
  );
  return {
    adherence:   resource.data,
    loading:     resource.loading,
    error:       resource.error,
    errorStatus: resource.errorStatus,
    refetch:     resource.refetch,
  };
}
