import { useCallback, useEffect, useRef, useState } from 'react';
import { apiError, type ApiError } from '@/lib/api/errors';
import { useIdempotencyKey } from '@/lib/api/idempotency';
import {
  createReminder, deleteReminder, listReminders, pauseReminder, resumeReminder, updateReminder,
} from '@/lib/reminders/api';
import type { CreateReminderInput, Reminder, ReminderListFilter, UpdateReminderInput } from '@/lib/reminders/types';
import { type ActionResult, type ActionState, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';

// Reminders of one patient (contract api/be-reminders.md): the paged list with the active filter, and the
// writes. There is no GET by id; after a write the caller applies the returned reminder and refetches the list,
// because a pause, resume or edit changes the order (active first, soonest due).

export interface UseRemindersOptions {
  /** Rows per request. Default 20. */
  pageSize?: number;
  /** When false nothing is requested. Default true. */
  enabled?:  boolean;
}

/**
 * The reminders addressed to a patient, manual and automatic together. `filter.active`: true = active only,
 * false = paused and ended only, null or absent = all. Nothing is requested until `patientSystemId` is known.
 * A 403 (`errorStatus === 403`) means the caller has no access to this patient.
 */
export function useReminders(
  patientSystemId: string | null | undefined,
  filter: ReminderListFilter = {},
  options: UseRemindersOptions = {},
): PagedState<Reminder> {
  const id = (patientSystemId ?? '').trim();
  const active = filter.active ?? null;
  return usePaged<Reminder>(
    (page, size) => listReminders(id, { active }, page, size),
    [id, active],
    {
      enabled:      !!id && (options.enabled ?? true),
      pageSize:     options.pageSize,
      keyOf:        reminder => reminder.reminderId,
      errorMessage: 'Reminders could not be loaded. Please try again.',
    },
  );
}

export interface CreateReminderState extends ActionState<[CreateReminderInput], Reminder> {
  /** Starts a new idempotency key (a form that stays mounted calls it each time it is opened). */
  renewKey: () => void;
}

/**
 * Creates a manual reminder. One `Idempotency-Key` is kept per mounted form: a retry after a failure repeats it,
 * so a request that did reach the server is not stored twice, and it is replaced after a success. Mount the
 * hook inside the form so every form open starts with a new key.
 */
export function useCreateReminder(): CreateReminderState {
  const key = useIdempotencyKey();
  const action = useAction(async (input: CreateReminderInput) => {
    const created = await createReminder(input, key.current());
    key.renew();
    return created;
  }, { errorMessage: 'The reminder could not be added. Please try again.' });
  return { ...action, renewKey: key.renew };
}

/** Edits a manual reminder (PATCH with the changed fields only). */
export function useUpdateReminder(): ActionState<[number, UpdateReminderInput], Reminder> {
  return useAction(
    (reminderId: number, input: UpdateReminderInput) => updateReminder(reminderId, input),
    { errorMessage: 'The reminder could not be saved. Please try again.' },
  );
}

export type ReminderRowAction = 'pause' | 'resume' | 'delete';

export interface ReminderRowActions {
  /** PATCH `{ isActive: false }`. Resolves with the updated reminder; never rejects. */
  pause:  (reminderId: number) => Promise<ActionResult<Reminder>>;
  /** PATCH `{ isActive: true }`. Fails with 409 when the reminder has ended. */
  resume: (reminderId: number) => Promise<ActionResult<Reminder>>;
  /** DELETE (manual reminders only). */
  remove: (reminderId: number) => Promise<ActionResult<void>>;
  /** The row action in flight, or null. One runs at a time; a second call meanwhile is ignored (`error: null`). */
  busy:   { reminderId: number; action: ReminderRowAction } | null;
}

const ROW_ERROR: Record<ReminderRowAction, string> = {
  pause:  'The reminder could not be paused. Please try again.',
  resume: 'The reminder could not be resumed. Please try again.',
  delete: 'The reminder could not be deleted. Please try again.',
};

/** Pause, resume and delete for the rows of a reminder list, with the row in flight exposed for its spinner. */
export function useReminderRowActions(): ReminderRowActions {
  const [busy, setBusy] = useState<ReminderRowActions['busy']>(null);
  // The ref (not the state) is the double-submit guard: two clicks in one frame both see busy === null.
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const run = useCallback(async <R,>(
    reminderId: number,
    action: ReminderRowAction,
    request: () => Promise<R>,
  ): Promise<ActionResult<R>> => {
    if (running.current) return { ok: false, error: null };
    running.current = true;
    setBusy({ reminderId, action });
    try {
      return { ok: true, data: await request() };
    } catch (e) {
      const error: ApiError = apiError(e, ROW_ERROR[action]);
      return { ok: false, error };
    } finally {
      running.current = false;
      if (mounted.current) setBusy(null);
    }
  }, []);

  const pause  = useCallback((reminderId: number) => run(reminderId, 'pause', () => pauseReminder(reminderId)), [run]);
  const resume = useCallback((reminderId: number) => run(reminderId, 'resume', () => resumeReminder(reminderId)), [run]);
  const remove = useCallback((reminderId: number) => run(reminderId, 'delete', () => deleteReminder(reminderId)), [run]);

  return { pause, resume, remove, busy };
}
