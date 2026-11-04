import { type Browser, type BrowserContextOptions, expect, type Locator, type Page, request } from '@playwright/test';
import { type Api, harareLocal } from './api';
import { API_URL, type DemoUser, JAMES, THOMAS } from './env';
import { type MonitoredPage, PageMonitor, futureDay } from './smoke';
import { login } from './ui';

// Helpers for fix-patient.spec.ts (patient web fixes). Test data starts with FIX_PREFIX; the
// spec cancels its appointments and removes its caregiver grants in afterAll, and the global
// e2e cleanup removes the throwaway e2e-fix-…@carenexus.test caregiver accounts.

export const FIX_PREFIX = 'e2e-fix-patient-';

export function fixTag(): string {
  return `${FIX_PREFIX}${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** A signed-in page that also counts API GETs by path (counting starts before sign-in). */
export interface CountingPage extends MonitoredPage {
  gets: (re: RegExp) => number;
}

export async function signedInCountingPage(browser: Browser, user: DemoUser, options: BrowserContextOptions = {}): Promise<CountingPage> {
  const context = await browser.newContext({ permissions: ['camera', 'microphone'], ...options });
  const page = await context.newPage();
  const urls: string[] = [];
  const apiOrigin = new URL(API_URL).origin;
  page.on('request', r => {
    if (r.method() === 'GET' && r.url().startsWith(apiOrigin)) urls.push(new URL(r.url()).pathname);
  });
  page.on('dialog', d => { d.accept().catch(() => {}); });
  const monitor = new PageMonitor(page, user.role);
  await login(page, user);
  return { context, page, monitor, user, gets: re => urls.filter(u => re.test(u)).length };
}

// ── Appointments ────────────────────────────────────────────────────────────

export interface Slot { days: number; hour24: number; minute: number }

/** Clinic-local "YYYY-MM-DDTHH:MM:00" for a slot. */
export function slotLocal(s: Slot): string {
  return `${futureDay(s.days).iso}T${String(s.hour24).padStart(2, '0')}:${String(s.minute).padStart(2, '0')}:00`;
}

/** A slot 30-90 days ahead that neither Dr. Banda nor Thomas has an active appointment at. */
export async function freeSlot(james: Api, thomas: Api): Promise<Slot> {
  const busy = new Set<string>();
  for (const [api, path] of [[james, `/api/appointments/doctor/${JAMES.systemId}`], [thomas, `/api/appointments/patient/${THOMAS.systemId}`]] as const) {
    const list = await api.ok(api.get<any[]>(path), `list ${path}`);
    for (const a of list) {
      if (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') busy.add(String(a.scheduledAt).slice(0, 16));
    }
  }
  for (;;) {
    const s: Slot = {
      days:   30 + Math.floor(Math.random() * 60),
      hour24: [9, 10, 11, 14, 15, 16][Math.floor(Math.random() * 6)],
      minute: [0, 15, 30, 45][Math.floor(Math.random() * 4)],
    };
    if (!busy.has(slotLocal(s).slice(0, 16))) return s;
  }
}

/**
 * The first quarter-hour slot at least 30 minutes from now (clinic time), as a Slot for the
 * pickers. It is earlier than any seed appointment, so it becomes the patient's next one.
 */
export function soonSlot(): Slot {
  const start = new Date(Math.ceil((Date.now() + 30 * 60_000) / 900_000) * 900_000);
  const local = harareLocal(start); // YYYY-MM-DDTHH:MM:00
  const todayIso = futureDay(0).iso;
  const days = local.slice(0, 10) === todayIso ? 0 : 1;
  return { days, hour24: Number(local.slice(11, 13)), minute: Number(local.slice(14, 16)) };
}

/** Thomas books an appointment with Dr. Banda through the API; returns its id. */
export async function bookWithBanda(thomas: Api, reason: string, slot: Slot, type: 'IN_PERSON' | 'VIDEO' = 'IN_PERSON'): Promise<number> {
  const body = await thomas.ok(thomas.post('/api/appointments', {
    patientSystemId: THOMAS.systemId,
    doctorSystemId:  JAMES.systemId,
    facilityId:      null,
    scheduledAt:     slotLocal(slot),
    durationMinutes: 30,
    type,
    reasonForVisit:  reason,
  }), 'book appointment');
  return body.appointmentId as number;
}

/** Cancels every active appointment of Thomas whose reason starts with FIX_PREFIX. */
export async function cancelFixAppointments(thomas: Api): Promise<void> {
  const list = await thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`);
  for (const a of (list.body ?? []) as any[]) {
    if (String(a.reasonForVisit ?? '').startsWith(FIX_PREFIX) && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED')) {
      await thomas.del(`/api/appointments/${a.appointmentId}`);
    }
  }
}

// ── Pickers (same controls as the booking form and the reschedule panel) ────

/** Selects a day in a CalendarPicker inside `scope` (moves forward month by month as needed). */
export async function pickDate(scope: Locator, days: number): Promise<void> {
  const { label } = futureDay(days);
  const day = scope.getByRole('button', { name: label, exact: true });
  for (let i = 0; i < 4 && !(await day.isVisible()); i++) {
    await scope.getByRole('button', { name: 'Next month' }).click();
  }
  await day.click();
  await expect(scope.getByText(label, { exact: true }), 'selected date shown').toBeVisible();
}

/** Selects a time in a TimePicker inside `scope` through its AM/PM, Hour and Minute radio groups. */
export async function pickTime(scope: Locator, hour24: number, minute: number): Promise<void> {
  const ap = hour24 < 12 ? 'AM' : 'PM';
  const h12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  const mm = `:${String(minute).padStart(2, '0')}`;
  await scope.getByRole('radiogroup', { name: 'AM or PM' }).getByRole('radio', { name: ap, exact: true }).click();
  await scope.getByRole('radiogroup', { name: 'Hour' }).getByRole('radio', { name: String(h12), exact: true }).click();
  await scope.getByRole('radiogroup', { name: 'Minute' }).getByRole('radio', { name: mm, exact: true }).click();
  await expect(scope.getByText(`${h12}${mm} ${ap}`, { exact: true })).toBeVisible();
}

/**
 * My Appointments: reveals (pages through "Show N more") and returns the card for `reason`.
 * Looks only inside the list pane, not the right rail, which may show the same reason.
 */
export async function revealAppointment(page: Page, reason: string): Promise<Locator> {
  const list = page.locator('div')
    .filter({ has: page.getByRole('heading', { name: 'My Appointments', exact: true }) })
    .filter({ has: page.getByRole('button', { name: /Show details|Show less/ }) })
    .last();
  const text = list.getByText(reason, { exact: true });
  for (let i = 0; i < 30 && !(await text.isVisible()); i++) {
    await list.getByText(/^Show \d+ more appointments?$/).click();
  }
  await expect(text).toBeVisible();
  // Filter on page-level locators: a `has` locator chained from `list` would require each candidate
  // card to contain the whole list (its heading), so nothing would match.
  return list.locator('div')
    .filter({ has: page.getByText(reason, { exact: true }) })
    .filter({ has: page.getByRole('button', { name: /Show details|Show less/ }) })
    .last();
}

// ── Caregivers ──────────────────────────────────────────────────────────────

/** Registers a throwaway caregiver (local dev API only); the password lives only in memory. */
export async function registerFixCaregiver(): Promise<DemoUser> {
  const user: DemoUser = {
    email:    `${fixTag()}@carenexus.test`,
    password: `Fix-${crypto.randomUUID()}`,
    role:     'proxy',
    systemId: '',
    name:     'Fix Carer',
  };
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/register', {
      data: { firstName: 'Fix', lastName: 'Carer', email: user.email, password: user.password, role: 'proxy' },
    });
    expect(res.status(), 'register throwaway caregiver').toBeLessThan(300);
    user.systemId = (await res.json()).user?.system_id ?? '';
  } finally {
    await ctx.dispose();
  }
  return user;
}

/** Removes Thomas's grants to throwaway fix caregivers. */
export async function removeFixGrants(thomas: Api): Promise<void> {
  const grants = await thomas.get<any[]>('/api/patients/me/caregiver-access');
  for (const g of (grants.body ?? []) as any[]) {
    if (String(g.caregiverEmail ?? '').startsWith(FIX_PREFIX)) {
      await thomas.del(`/api/patients/me/caregiver-access/${g.accessId}`);
    }
  }
}

/** Calendar date (YYYY-MM-DD) in a time zone, `days` from now. */
export function dateIn(timeZone: string, days: number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(Date.now() + days * 86_400_000));
}
