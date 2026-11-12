import { withIdempotencyKey } from '@/lib/api/idempotency';
import { fetchPage } from '@/lib/api/paged';
import type { PageResponse } from '@/lib/api/types';
import { api } from '@/lib/auth/auth.interceptor';
import type { CreateReminderInput, Reminder, ReminderListFilter, UpdateReminderInput } from './types';

// One function per reminder endpoint (contract api/be-reminders.md). There is no GET by id: every write
// answers with the full reminder and the list is fetched again.

const BASE = '/api/reminders';

/**
 * GET /api/reminders/patient/{patientSystemId}: one page of the reminders addressed to a patient, manual and
 * automatic together, active first, then soonest due. 403 when the caller has no access to the patient.
 */
export function listReminders(
  patientSystemId: string,
  filter: ReminderListFilter = {},
  page = 0,
  size?: number,
): Promise<PageResponse<Reminder>> {
  return fetchPage<Reminder>(`${BASE}/patient/${encodeURIComponent(patientSystemId)}`, page, size, {
    // Absent = all; `false` is sent (paused and ended only).
    active: filter.active ?? undefined,
  });
}

/**
 * POST /api/reminders: creates a manual reminder for a patient (201; 200 with the stored body when the same
 * `Idempotency-Key` is repeated). Pass the key of the open form so a retried submit cannot create a second row.
 */
export async function createReminder(input: CreateReminderInput, idempotencyKey?: string): Promise<Reminder> {
  const { data } = await api.post<Reminder>(BASE, input, idempotencyKey ? withIdempotencyKey(idempotencyKey) : undefined);
  return data;
}

/** PATCH /api/reminders/{id}: edits a manual reminder, or pauses / resumes any reminder with `isActive`. */
export async function updateReminder(reminderId: number, input: UpdateReminderInput): Promise<Reminder> {
  const { data } = await api.patch<Reminder>(`${BASE}/${reminderId}`, input);
  return data;
}

/** PATCH `{ isActive: false }`. */
export function pauseReminder(reminderId: number): Promise<Reminder> {
  return updateReminder(reminderId, { isActive: false });
}

/** PATCH `{ isActive: true }`. 409 when the reminder has ended (no upcoming time to resume). */
export function resumeReminder(reminderId: number): Promise<Reminder> {
  return updateReminder(reminderId, { isActive: true });
}

/** DELETE /api/reminders/{id}: removes a manual reminder (204). Automatic reminders answer 409. */
export async function deleteReminder(reminderId: number): Promise<void> {
  await api.delete(`${BASE}/${reminderId}`);
}
