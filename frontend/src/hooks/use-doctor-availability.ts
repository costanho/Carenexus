import { useCallback, useState } from 'react';
import { z } from 'zod';
import { api } from '@/lib/auth/auth.interceptor';
import { apiError, type ApiError } from '@/lib/api/errors';
import { useIdempotencyKey, withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { clinicNowLocal } from '@/lib/time/clinic';
import { type ActionResult, useAction } from './use-action';
import { type PagedState, usePaged } from './use-paged';
import { useResource } from './use-resource';

// The signed-in doctor's weekly hours and time off (be-availability):
//   GET / PUT        /api/doctors/availability            the weekly template, replaced as a whole
//   GET / POST       /api/doctors/availability/time-off   paged time-off ranges (soonest first), add one
//   DELETE           /api/doctors/availability/time-off/{id}
//   GET              /api/appointments/booking-rules      clinic hours that bound the template
// Every time is clinic wall-clock time; nothing here is converted through the device time zone.

// ─── Types (camelCase straight from the API) ──────────────────────────────────

export type AvailabilitySource = 'TEMPLATE' | 'CLINIC_HOURS';

export interface AvailabilityBlock {
  /** ISO weekday: 1 = Monday ... 7 = Sunday. */
  dayOfWeek: number;
  /** "HH:mm", first bookable minute. */
  startTime: string;
  /** "HH:mm", exclusive end: an appointment must end by it. */
  endTime:   string;
  /** false = kept but not offered to patients. */
  isActive:  boolean;
}

export interface DoctorAvailability {
  doctorSystemId: string;
  timeZone:       string;
  /** CLINIC_HOURS = no block is stored and patients book against clinic hours. */
  source:         AvailabilitySource;
  /** One slot length for all blocks, 5 to 240 in steps of 5. */
  slotMinutes:    number;
  blocks:         AvailabilityBlock[];
}

export interface UpdateDoctorAvailabilityRequest {
  slotMinutes: number;
  blocks:      Array<{ dayOfWeek: number; startTime: string; endTime: string; isActive?: boolean }>;
}

export interface TimeOff {
  timeOffId:               number;
  doctorSystemId:          string;
  /** Inclusive start, clinic wall clock "YYYY-MM-DDTHH:mm:ss". */
  startsAt:                string;
  /** Exclusive end, clinic wall clock. */
  endsAt:                  string;
  reason:                  string | null;
  /** Active appointments inside the range right now. Time off never cancels them. */
  conflictingAppointments: number;
  createdAt:               string;
}

export interface CreateTimeOffRequest {
  startsAt: string;
  endsAt:   string;
  reason?:  string | null;
}

/** The part of GET /api/appointments/booking-rules that bounds the weekly template. */
export interface ClinicHours {
  timeZone:      string;
  openHour:      number;
  /** Appointments must end by this hour; 24 = end of day. */
  closeHour:     number;
  allowWeekends: boolean;
}

// ─── Constants ────────────────────────────────────────────────────────────────

export const WEEKDAYS: readonly { day: number; label: string }[] = [
  { day: 1, label: 'Monday' }, { day: 2, label: 'Tuesday' }, { day: 3, label: 'Wednesday' }, { day: 4, label: 'Thursday' },
  { day: 5, label: 'Friday' }, { day: 6, label: 'Saturday' }, { day: 7, label: 'Sunday' },
];

export const MIN_SLOT_MINUTES = 5;
export const MAX_SLOT_MINUTES = 240;
export const TIME_STEP_MINUTES = 5;
export const MAX_BLOCKS_PER_DAY = 6;
export const MAX_TIME_OFF_DAYS = 366;
export const MAX_REASON_LENGTH = 200;
/** Slot length used while the doctor has no template (the server's fallback). */
export const DEFAULT_SLOT_MINUTES = 30;

const TIME_HH_MM = /^([01]\d|2[0-3]):[0-5]\d$/;
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

// ─── API ──────────────────────────────────────────────────────────────────────

export async function getDoctorAvailability(): Promise<DoctorAvailability> {
  const { data } = await api.get<DoctorAvailability>('/api/doctors/availability');
  return { ...data, blocks: Array.isArray(data?.blocks) ? data.blocks : [] };
}

/** Replaces the whole weekly template. An empty `blocks` array returns the doctor to clinic hours. */
export async function saveDoctorAvailability(body: UpdateDoctorAvailabilityRequest): Promise<DoctorAvailability> {
  const { data } = await api.put<DoctorAvailability>('/api/doctors/availability', body);
  return { ...data, blocks: Array.isArray(data?.blocks) ? data.blocks : [] };
}

export function listTimeOff(page: number, size: number, includePast: boolean): Promise<PageResponse<TimeOff>> {
  return fetchPage<TimeOff>('/api/doctors/availability/time-off', page, size, { includePast: includePast ? true : undefined });
}

/** Adds a time-off range. The same `idempotencyKey` on a retry returns the stored row instead of adding another. */
export async function createTimeOff(body: CreateTimeOffRequest, idempotencyKey: string): Promise<TimeOff> {
  const { data } = await api.post<TimeOff>('/api/doctors/availability/time-off', body, withIdempotencyKey(idempotencyKey));
  return data;
}

/** Removes a time-off range. A range that is already gone (404) counts as removed. */
export async function deleteTimeOff(timeOffId: number): Promise<void> {
  try {
    await api.delete(`/api/doctors/availability/time-off/${timeOffId}`);
  } catch (e) {
    if (apiError(e).status !== 404) throw e;
  }
}

/** Clinic opening hours, or null when they cannot be read (the server still validates what is saved). */
export async function getClinicHours(): Promise<ClinicHours | null> {
  const { data } = await api.get<Partial<ClinicHours>>('/api/appointments/booking-rules');
  if (!data || typeof data.openHour !== 'number' || typeof data.closeHour !== 'number') return null;
  return {
    timeZone:      typeof data.timeZone === 'string' ? data.timeZone : '',
    openHour:      data.openHour,
    closeHour:     data.closeHour,
    allowWeekends: data.allowWeekends !== false,
  };
}

// ─── Weekly template: drafts and validation (mirrors PUT /api/doctors/availability) ──

/** One block being edited. `id` only identifies the row on screen; the API has no block ids. */
export interface BlockDraft {
  id:        string;
  dayOfWeek: number;
  startTime: string;
  endTime:   string;
  isActive:  boolean;
}

let draftSeq = 0;
export function newBlockDraft(dayOfWeek: number, startTime: string, endTime: string, isActive = true): BlockDraft {
  draftSeq += 1;
  return { id: `block-${draftSeq}`, dayOfWeek, startTime, endTime, isActive };
}

export function draftsOf(availability: DoctorAvailability | null | undefined): BlockDraft[] {
  return (availability?.blocks ?? []).map(b => newBlockDraft(
    b.dayOfWeek, String(b.startTime).slice(0, 5), String(b.endTime).slice(0, 5), b.isActive !== false,
  ));
}

export function minutesOf(time: string): number {
  const [h, m] = time.split(':').map(Number);
  return h * 60 + m;
}

export function timeOfMinutes(minutes: number): string {
  const safe = Math.min(23 * 60 + 55, Math.max(0, minutes));
  return `${String(Math.floor(safe / 60)).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
}

export function hourLabel(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

/** The blocks of one weekday in start-time order (drafts with an unreadable time keep their place at the end). */
export function blocksOfDay(blocks: readonly BlockDraft[], day: number): BlockDraft[] {
  return blocks
    .filter(b => b.dayOfWeek === day)
    .sort((a, b) => (TIME_HH_MM.test(a.startTime) && TIME_HH_MM.test(b.startTime) ? minutesOf(a.startTime) - minutesOf(b.startTime) : 0));
}

/**
 * A sensible next block for a weekday: after the day's last block (with a gap of one slot where it fits), or the
 * clinic's opening hours for an empty day. Null when nothing fits before closing time.
 */
export function suggestBlock(blocks: readonly BlockDraft[], day: number, slotMinutes: number, clinic: ClinicHours | null): BlockDraft | null {
  const open = (clinic?.openHour ?? 8) * 60;
  const close = Math.min((clinic?.closeHour ?? 18) * 60, 23 * 60 + 55);
  const existing = blocksOfDay(blocks, day).filter(b => TIME_HH_MM.test(b.endTime));
  if (existing.length === 0) {
    return close - open >= slotMinutes ? newBlockDraft(day, timeOfMinutes(open), timeOfMinutes(close)) : null;
  }
  const lastEnd = Math.max(...existing.map(b => minutesOf(b.endTime)));
  const length = Math.max(slotMinutes, 60);
  const withGap = Math.ceil((lastEnd + 60) / TIME_STEP_MINUTES) * TIME_STEP_MINUTES;
  const start = withGap + slotMinutes <= close ? withGap : lastEnd;
  if (start + slotMinutes > close) return null;
  return newBlockDraft(day, timeOfMinutes(start), timeOfMinutes(Math.min(close, start + length)));
}

export interface AvailabilityIssues {
  slotMinutes?: string;
  /** Messages per block, keyed by the draft's id. */
  blocks:       Record<string, { startTime?: string; endTime?: string }>;
  /** Messages per weekday (1 to 7). */
  days:         Record<number, string>;
  valid:        boolean;
}

export const NO_ISSUES: AvailabilityIssues = { blocks: {}, days: {}, valid: true };

function slotIssue(slotMinutes: number): string | undefined {
  return Number.isInteger(slotMinutes) && slotMinutes >= MIN_SLOT_MINUTES && slotMinutes <= MAX_SLOT_MINUTES
    && slotMinutes % TIME_STEP_MINUTES === 0
    ? undefined
    : `Must be between ${MIN_SLOT_MINUTES} and ${MAX_SLOT_MINUTES} minutes in steps of ${TIME_STEP_MINUTES}`;
}

/**
 * The rules the server applies to the weekly template, in the same order: times are HH:mm on a 5-minute
 * boundary, the end is after the start, a block is at least one slot long, blocks lie inside clinic hours (and
 * on open days), a weekday has at most six blocks, and two blocks of a weekday do not overlap (touching is
 * allowed). `clinic` null skips the clinic-hours rules; the server still applies them on save.
 */
export function validateAvailability(
  slotMinutes: number, blocks: readonly BlockDraft[], clinic: ClinicHours | null,
): AvailabilityIssues {
  const issues: AvailabilityIssues = { blocks: {}, days: {}, valid: true };
  const flag = (id: string, field: 'startTime' | 'endTime', message: string) => {
    const entry = issues.blocks[id] ?? (issues.blocks[id] = {});
    if (!entry[field]) entry[field] = message;
    issues.valid = false;
  };

  const slot = slotIssue(slotMinutes);
  if (slot) { issues.slotMinutes = slot; issues.valid = false; }

  const sound: BlockDraft[] = [];
  for (const b of blocks) {
    let ok = true;
    for (const field of ['startTime', 'endTime'] as const) {
      const value = b[field];
      if (!value) { flag(b.id, field, 'Enter a time'); ok = false; }
      else if (!TIME_HH_MM.test(value)) { flag(b.id, field, 'Must be a time in HH:mm format'); ok = false; }
      else if (minutesOf(value) % TIME_STEP_MINUTES !== 0) { flag(b.id, field, 'Must be on a 5-minute boundary'); ok = false; }
    }
    if (!ok) continue;
    const start = minutesOf(b.startTime);
    const end = minutesOf(b.endTime);
    if (end <= start) { flag(b.id, 'endTime', 'Must be after the start time'); continue; }
    if (!slot && end - start < slotMinutes) {
      flag(b.id, 'endTime', `The block must be at least as long as the slot length (${slotMinutes} minutes)`);
      continue;
    }
    if (clinic) {
      if (!clinic.allowWeekends && b.dayOfWeek >= 6) {
        issues.days[b.dayOfWeek] = 'The clinic is closed on weekends';
        issues.valid = false;
        continue;
      }
      if (start < clinic.openHour * 60) {
        flag(b.id, 'startTime', `Must not be before the clinic opens at ${hourLabel(clinic.openHour)}`);
        continue;
      }
      if (end > clinic.closeHour * 60) {
        flag(b.id, 'endTime', `Must not be after the clinic closes at ${hourLabel(clinic.closeHour)}`);
        continue;
      }
    }
    sound.push(b);
  }

  for (const { day } of WEEKDAYS) {
    if (blocks.filter(b => b.dayOfWeek === day).length > MAX_BLOCKS_PER_DAY) {
      issues.days[day] = `At most ${MAX_BLOCKS_PER_DAY} blocks per day`;
      issues.valid = false;
    }
    // Overlap is reported on the later of the two blocks, whatever their Bookable switches say.
    const ofDay = sound.filter(b => b.dayOfWeek === day);
    for (let j = 1; j < ofDay.length; j++) {
      const overlaps = ofDay.slice(0, j).some(a =>
        minutesOf(a.startTime) < minutesOf(ofDay[j].endTime) && minutesOf(ofDay[j].startTime) < minutesOf(a.endTime));
      if (overlaps) flag(ofDay[j].id, 'startTime', 'Overlaps another block on the same day');
    }
  }
  return issues;
}

/** The PUT body for the drafts, ordered by weekday and start time (the order the server reports errors in). */
export function toAvailabilityRequest(slotMinutes: number, blocks: readonly BlockDraft[]): {
  body: UpdateDoctorAvailabilityRequest; order: BlockDraft[];
} {
  const order = WEEKDAYS.flatMap(({ day }) => blocksOfDay(blocks, day));
  return {
    order,
    body: {
      slotMinutes,
      blocks: order.map(b => ({ dayOfWeek: b.dayOfWeek, startTime: b.startTime, endTime: b.endTime, isActive: b.isActive })),
    },
  };
}

function sentence(text: string): string {
  const t = text.trim();
  return t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
}

const SERVER_FIELD = /^blocks\[(\d+)\]\.(startTime|endTime|dayOfWeek)$/;

/**
 * Puts a refused save onto the editor: `blocks[i].startTime: ...` lands on the block that was sent at index `i`
 * (`order` is the array of that request), `slotMinutes: ...` on the slot length. Anything else is returned as
 * `formMessage`.
 */
export function availabilityServerIssues(error: ApiError, order: readonly BlockDraft[]): {
  issues: AvailabilityIssues; formMessage: string | null;
} {
  const issues: AvailabilityIssues = { blocks: {}, days: {}, valid: false };
  let placed = 0;
  const place = (field: string, message: string): boolean => {
    if (field === 'slotMinutes') { issues.slotMinutes = issues.slotMinutes ?? sentence(message); return true; }
    const m = SERVER_FIELD.exec(field);
    const draft = m ? order[Number(m[1])] : undefined;
    if (!m || !draft) return false;
    if (m[2] === 'dayOfWeek') {
      issues.days[draft.dayOfWeek] = issues.days[draft.dayOfWeek] ?? sentence(message);
    } else {
      const entry = issues.blocks[draft.id] ?? (issues.blocks[draft.id] = {});
      const key = m[2] as 'startTime' | 'endTime';
      entry[key] = entry[key] ?? sentence(message);
    }
    return true;
  };

  for (const [field, message] of Object.entries(error.fieldErrors ?? {})) {
    if (place(field, message)) placed += 1;
  }
  if (placed === 0) {
    // Rules checked in the service arrive as "<field>: <text>" in the message only.
    const split = /^([A-Za-z]+(?:\[\d+\])?(?:\.[A-Za-z]+)?):\s*(.+)$/.exec(error.message);
    if (split && place(split[1], split[2])) placed += 1;
  }
  return { issues, formMessage: placed > 0 ? null : error.message };
}

// ─── Time off: form values and validation (mirrors POST /api/doctors/availability/time-off) ──

export interface TimeOffFormValues {
  /** Whole days: from the start of the first day to the end of the last day. */
  allDay:    boolean;
  startDate: string;
  startTime: string;
  endDate:   string;
  endTime:   string;
  reason:    string;
}

function realDate(value: string): boolean {
  if (!DATE_ONLY.test(value)) return false;
  const [y, m, d] = value.split('-').map(Number);
  const at = new Date(Date.UTC(y, m - 1, d));
  return !Number.isNaN(at.getTime()) && at.toISOString().slice(0, 10) === value;
}

function nextDay(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + 1)).toISOString().slice(0, 10);
}

function wallClockMs(local: string): number {
  const [date, time] = local.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm] = time.split(':').map(Number);
  return Date.UTC(y, m - 1, d, hh, mm);
}

/**
 * The range a form describes, as the wall-clock values the API takes, or null while a date or time is missing.
 * Whole days run from midnight to midnight: "24 to 26 December" ends at 00:00 on the 27th.
 */
export function timeOffRange(values: TimeOffFormValues): { startsAt: string; endsAt: string } | null {
  if (!realDate(values.startDate) || !realDate(values.endDate)) return null;
  if (values.allDay) {
    return { startsAt: `${values.startDate}T00:00:00`, endsAt: `${nextDay(values.endDate)}T00:00:00` };
  }
  if (!TIME_HH_MM.test(values.startTime) || !TIME_HH_MM.test(values.endTime)) return null;
  return { startsAt: `${values.startDate}T${values.startTime}:00`, endsAt: `${values.endDate}T${values.endTime}:00` };
}

/** Same rules as the server: the end is after the start, in the future (clinic time), and at most 366 days later. */
export const timeOffSchema = z.object({
  allDay:    z.boolean(),
  startDate: z.string().refine(realDate, 'Choose the first day'),
  startTime: z.string(),
  endDate:   z.string().refine(realDate, 'Choose the last day'),
  endTime:   z.string(),
  reason:    z.string().max(MAX_REASON_LENGTH, `Must be at most ${MAX_REASON_LENGTH} characters`),
}).superRefine((values, ctx) => {
  if (!values.allDay) {
    if (!TIME_HH_MM.test(values.startTime)) ctx.addIssue({ code: 'custom', path: ['startTime'], message: 'Enter a start time' });
    if (!TIME_HH_MM.test(values.endTime)) ctx.addIssue({ code: 'custom', path: ['endTime'], message: 'Enter an end time' });
  }
  const range = timeOffRange(values);
  if (!range) return;
  const endField = values.allDay || values.endDate !== values.startDate ? 'endDate' : 'endTime';
  if (range.endsAt <= range.startsAt) {
    ctx.addIssue({ code: 'custom', path: [endField], message: values.allDay ? 'Must not be before the first day' : 'Must be after the start' });
  } else if (range.endsAt <= clinicNowLocal()) {
    ctx.addIssue({ code: 'custom', path: [endField], message: 'Must be in the future' });
  } else if (wallClockMs(range.endsAt) - wallClockMs(range.startsAt) > MAX_TIME_OFF_DAYS * 86_400_000) {
    ctx.addIssue({ code: 'custom', path: [endField], message: `Time off can be at most ${MAX_TIME_OFF_DAYS} days long` });
  }
});

/** The POST body for valid form values. A blank reason is not sent. */
export function toTimeOffRequest(values: TimeOffFormValues): CreateTimeOffRequest | null {
  const range = timeOffRange(values);
  if (!range) return null;
  const reason = values.reason.trim();
  return { ...range, reason: reason ? reason : null };
}

/** True when the range covers whole days (midnight to midnight). */
export function isWholeDays(timeOff: Pick<TimeOff, 'startsAt' | 'endsAt'>): boolean {
  return String(timeOff.startsAt).slice(11, 16) === '00:00' && String(timeOff.endsAt).slice(11, 16) === '00:00';
}

/** Last day covered by a whole-day range (its exclusive end is midnight of the day after). */
export function lastWholeDay(endsAt: string): string {
  const [y, m, d] = endsAt.slice(0, 10).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}

// ─── Hooks ────────────────────────────────────────────────────────────────────

export interface DoctorAvailabilityState {
  availability: DoctorAvailability | null;
  /** Clinic hours that bound the template; null when they could not be read. */
  clinic:       ClinicHours | null;
  loading:      boolean;
  error:        string | null;
  /** 403 = the caller is not a doctor (administrators have no availability of their own). */
  errorStatus:  number | null;
  refetch:      () => void;
  /** Replaces the weekly template. Never rejects: check `result.ok`. */
  save:         (body: UpdateDoctorAvailabilityRequest) => Promise<ActionResult<DoctorAvailability>>;
  saving:       boolean;
}

/** The signed-in doctor's weekly template together with the clinic hours it has to stay inside. */
export function useDoctorAvailability(): DoctorAvailabilityState {
  const resource = useResource(async () => {
    const [availability, clinic] = await Promise.all([
      getDoctorAvailability(),
      getClinicHours().catch(() => null),
    ]);
    return { availability, clinic };
  }, [], { errorMessage: 'Could not load your availability. Please try again.' });

  const action = useAction(saveDoctorAvailability, { errorMessage: 'Could not save your weekly hours. Please try again.' });
  const { setData } = resource;
  const { run } = action;

  const save = useCallback(async (body: UpdateDoctorAvailabilityRequest) => {
    const result = await run(body);
    if (result.ok) setData(prev => (prev ? { ...prev, availability: result.data } : prev));
    return result;
  }, [run, setData]);

  return {
    availability: resource.data?.availability ?? null,
    clinic:       resource.data?.clinic ?? null,
    loading:      resource.loading,
    error:        resource.error,
    errorStatus:  resource.errorStatus,
    refetch:      resource.refetch,
    save,
    saving:       action.pending,
  };
}

export interface DoctorTimeOffState {
  list:       PagedState<TimeOff>;
  /** Adds a range. Never rejects: check `result.ok`. A retry after a failure repeats the idempotency key. */
  add:        (body: CreateTimeOffRequest) => Promise<ActionResult<TimeOff>>;
  adding:     boolean;
  /** Call when the add form opens: the next submit gets a fresh idempotency key (one key per form open). */
  beginForm:  () => void;
  /** Removes a range (a range that is already gone counts as removed) and reloads the list. */
  remove:     (timeOffId: number) => Promise<ActionResult<void>>;
  /** Id of the range being removed, or null. */
  removingId: number | null;
}

/** The signed-in doctor's time off: running and upcoming ranges, soonest first; past ones too with `includePast`. */
export function useDoctorTimeOff(includePast: boolean): DoctorTimeOffState {
  const list = usePaged<TimeOff>(
    (page, size) => listTimeOff(page, size, includePast),
    [includePast],
    { keyOf: t => t.timeOffId, errorMessage: 'Could not load your time off. Please try again.' },
  );
  // Both functions are stable, so the callbacks below keep their identity between renders.
  const { current: currentKey, renew: renewKey } = useIdempotencyKey();
  const create = useAction((body: CreateTimeOffRequest) => createTimeOff(body, currentKey()), {
    errorMessage: 'Could not add the time off. Please try again.',
  });
  const destroy = useAction(deleteTimeOff, { errorMessage: 'Could not remove the time off. Please try again.' });
  const [removingId, setRemovingId] = useState<number | null>(null);

  const { refetch } = list;
  const runCreate = create.run;
  const runDestroy = destroy.run;

  const add = useCallback(async (body: CreateTimeOffRequest) => {
    const result = await runCreate(body);
    // The next submit is a new request; a failed one is retried with the same key.
    if (result.ok) { renewKey(); refetch(); }
    return result;
  }, [runCreate, renewKey, refetch]);

  const remove = useCallback(async (timeOffId: number) => {
    setRemovingId(timeOffId);
    const result = await runDestroy(timeOffId);
    setRemovingId(null);
    if (result.ok) refetch();
    return result;
  }, [runDestroy, refetch]);

  const resetCreate = create.reset;
  const beginForm = useCallback(() => { renewKey(); resetCreate(); }, [renewKey, resetCreate]);

  return { list, add, adding: create.pending, beginForm, remove, removingId };
}
