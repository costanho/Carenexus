import { expect, type Locator, type Page } from '@playwright/test';
import { type Api, harareLocal } from './api';
import { JAMES, THOMAS } from './env';
import { futureDay } from './smoke';

// Helpers for fix-doctor.spec.ts. Test data carries the "e2e-fix-" prefix in its reason text, so
// afterAll here and the global e2e cleanup (scripts/dev-reset-e2e-data.sh) can find it.

export const FIX_PREFIX = 'e2e-fix-';

export function fixTag(): string {
  return `${FIX_PREFIX}doctor-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

export interface Slot { days: number; hour24: number; minute: number }

/** Clinic wall clock "YYYY-MM-DDTHH:MM" of a slot. */
export function slotKey(s: Slot): string {
  return `${futureDay(s.days).iso}T${String(s.hour24).padStart(2, '0')}:${String(s.minute).padStart(2, '0')}`;
}

/** The slot's start as a UTC instant (Africa/Harare is UTC+2 all year). */
export function slotUtc(s: Slot): Date {
  const [y, m, d] = futureDay(s.days).iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d, s.hour24 - 2, s.minute));
}

/** Today's clinic date (Africa/Harare) as YYYY-MM-DD. */
export function clinicToday(): string {
  return harareLocal(new Date()).slice(0, 10);
}

/** Free quarter-hour slots for Dr. Banda and Thomas, 30-90 days ahead, inside clinic hours. */
export async function freeSlots(james: Api, thomas: Api, count: number): Promise<Slot[]> {
  const busy = new Set<string>();
  for (const [api, path] of [[james, `/api/appointments/doctor/${JAMES.systemId}`], [thomas, `/api/appointments/patient/${THOMAS.systemId}`]] as const) {
    const list = await api.ok(api.get<any[]>(path), `list ${path}`);
    for (const a of list) {
      if (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') busy.add(String(a.scheduledAt).slice(0, 16));
    }
  }
  const out: Slot[] = [];
  const hours = [9, 10, 11, 14, 15, 16];
  while (out.length < count) {
    const s: Slot = {
      days:   30 + Math.floor(Math.random() * 60),
      hour24: hours[Math.floor(Math.random() * hours.length)],
      minute: [0, 15, 30, 45][Math.floor(Math.random() * 4)],
    };
    const key = slotKey(s);
    // An hour apart from every active appointment, so neither 30-minute visit overlaps.
    const near = (k: string) => Math.abs(new Date(k).getTime() - new Date(key).getTime()) < 60 * 60_000;
    if (Array.from(busy).some(near) || out.some(o => near(slotKey(o)))) continue;
    out.push(s);
  }
  return out;
}

/** Thomas books a 30-minute appointment with Dr. Banda at `slot`; returns the appointment id. */
export async function bookWithJames(thomas: Api, slot: Slot, type: 'IN_PERSON' | 'VIDEO', reason: string): Promise<number> {
  const body = await thomas.ok(thomas.post('/api/appointments', {
    patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId,
    scheduledAt:     `${slotKey(slot)}:00`,
    durationMinutes: 30, type, reasonForVisit: reason,
  }), `book ${type}`);
  return body.appointmentId as number;
}

/** Cancels every active appointment of Thomas whose reason starts with the fix prefix. */
export async function cancelFixAppointments(thomas: Api): Promise<void> {
  const list = await thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`);
  for (const a of (list.body ?? []) as any[]) {
    const reason = String(a.reasonForVisit ?? '');
    if (reason.startsWith(FIX_PREFIX) && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED')) {
      await thomas.del(`/api/appointments/${a.appointmentId}`);
    }
  }
}

/** The innermost element that holds `reason` and the given control (one appointment card). */
export function cardOf(page: Page, reason: string, control: Locator): Locator {
  return page.locator('div').filter({ has: page.getByText(reason, { exact: true }) }).filter({ has: control }).last();
}

/** Selects a day in a CalendarPicker inside `scope`, moving forward month by month as needed. */
export async function pickDate(scope: Locator, days: number): Promise<void> {
  const { label } = futureDay(days);
  const day = scope.getByRole('button', { name: label, exact: true });
  for (let i = 0; i < 4 && !(await day.isVisible()); i++) {
    await scope.getByRole('button', { name: 'Next month' }).click();
  }
  await day.click();
  await expect(scope.getByText(label, { exact: true }), 'selected date shown').toBeVisible();
}

/** "h:mm AM/PM" as the doctor's reschedule time slots name it. */
export function slotLabel(s: Slot): string {
  const h12 = s.hour24 % 12 === 0 ? 12 : s.hour24 % 12;
  return `${h12}:${String(s.minute).padStart(2, '0')} ${s.hour24 < 12 ? 'AM' : 'PM'}`;
}

/** Date as the doctor's reschedule confirmation prints it, e.g. "Wed, 18 Nov 2026". */
export function clinicDateLabel(days: number): string {
  return futureDay(days).date.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
}

/** Patient > My Appointments: reveals (pages through "Show N more") the card for `reason`. */
export async function revealPatientAppointment(page: Page, reason: string): Promise<Locator> {
  const text = page.getByText(reason, { exact: true });
  for (let i = 0; i < 30 && !(await text.isVisible()); i++) {
    await page.getByText(/^Show \d+ more appointments?$/).click();
  }
  await expect(text).toBeVisible();
  return cardOf(page, reason, page.getByRole('button', { name: /Show details|Show less/ }));
}
