import { expect, type Locator, type Page, test } from '@playwright/test';
import { type Clients, disposeAll, harareLocal, listAppointments, loginAll } from './support/api';
import { setAppointmentStart } from './support/db';
import { JAMES, THOMAS } from './support/env';
import {
  type MonitoredPage, PATIENT_SECTIONS, SMOKE_PREFIX, apiIdle, expectNoArtifacts, futureDay,
  signedInPage, smokeTag, visit,
} from './support/smoke';
import { openSectionAt } from './support/ui';

// Smoke: the patient books IN_PERSON and VIDEO appointments through the booking form (type, doctor, duration, then a
// day in the calendar and one of the doctor's free times, which are buttons named "HH:mm" in the "Select Time"
// group), sees them in My Appointments, opens the details and reschedules one (the "New time" group); the doctor sees
// them, opens the details and cancels through the UI, and marks started appointments completed or no-show. Seeded
// doctors work 08:00 to 13:00 and 14:00 to 18:00 in 30-minute slots, so every time used here is on that grid.
// Everything created here has an "e2e-smoke-" reason and is cancelled in afterAll.
//
// Tables written: appointments (e2e-smoke-… reasons; one moved into the past with SQL on the local dev database) and
// the call_sessions, call_jobs and notifications they raise (booking, reschedule, cancel, outcome); refresh_tokens
// and audit_log for every sign-in. scripts/dev-reset-e2e-data.sh removes the tagged rows.

/** A start on the doctor's 30-minute grid, `days` from today (clinic calendar). */
interface Slot { days: number; hour24: number; minute: 0 | 30 }

const GRID_HOURS = [9, 10, 11, 14, 15, 16];

const section = (list: typeof PATIENT_SECTIONS, nav: string) => list.find(s => s.nav === nav)!;

/** Clinic wall clock "YYYY-MM-DDTHH:MM" of a slot (matches the API's LocalDateTime). */
function slotKey(s: Slot): string {
  return `${futureDay(s.days).iso}T${String(s.hour24).padStart(2, '0')}:${String(s.minute).padStart(2, '0')}`;
}

/** "HH:mm": how the slot picker names a start time. */
const slotTime = (s: Slot) => slotKey(s).slice(11);

/** Free grid slots for Dr. Banda and Thomas, 30-90 days ahead, an hour away from every active appointment of both. */
async function freeSlots(api: Clients, count: number): Promise<Slot[]> {
  const filters = { status: 'SCHEDULED,RESCHEDULED', from: harareLocal(new Date()) };
  const rows = [
    ...await listAppointments(api.james, { ...filters, doctorSystemId: JAMES.systemId }),
    ...await listAppointments(api.thomas, { ...filters, patientSystemId: THOMAS.systemId }),
  ];
  const busy = rows.map(a => new Date(String(a.scheduledAt).slice(0, 16)).getTime());
  const out: Slot[] = [];
  while (out.length < count) {
    const s: Slot = {
      days:   30 + Math.floor(Math.random() * 60),
      hour24: GRID_HOURS[Math.floor(Math.random() * GRID_HOURS.length)],
      minute: Math.random() < 0.5 ? 0 : 30,
    };
    const t = new Date(slotKey(s)).getTime();
    const near = (b: number) => Math.abs(b - t) < 60 * 60_000;
    if (busy.some(near) || out.some(o => near(new Date(slotKey(o)).getTime()))) continue;
    out.push(s);
  }
  return out;
}

/** Selects a day in a CalendarPicker inside `scope` (moves forward month by month as needed). */
async function pickDate(scope: Locator, days: number): Promise<void> {
  const { label } = futureDay(days);
  const day = scope.getByRole('button', { name: label, exact: true });
  for (let i = 0; i < 4 && !(await day.isVisible()); i++) {
    await scope.getByRole('button', { name: 'Next month' }).click();
  }
  await day.click();
  await expect(scope.getByText(label, { exact: true }), 'selected date shown').toBeVisible();
}

/** Chooses a start time in the slot picker labelled `group`. */
async function pickSlot(page: Page, group: string, time: string): Promise<void> {
  const button = page.getByRole('group', { name: group, exact: true }).getByRole('button', { name: time, exact: true });
  await expect(button, `${time} is offered`).toBeEnabled();
  await button.click();
  await expect(button).toHaveAttribute('aria-pressed', 'true');
}

/** The innermost card that holds `reason` and the given control. */
function cardOf(page: Page, reason: string, control: Locator): Locator {
  return page.locator('div').filter({ has: page.getByText(reason, { exact: true }) }).filter({ has: control }).last();
}

/** Patient > My Appointments: reveals ("Show N more appointments", 10 at a time) the card for `reason`. */
async function revealPatientAppointment(page: Page, reason: string): Promise<Locator> {
  const text = page.getByText(reason, { exact: true });
  for (let i = 0; i < 30 && !(await text.isVisible()); i++) {
    await page.getByRole('button', { name: /^Show (\d+ )?more appointments?$/ }).click();
  }
  await expect(text).toBeVisible();
  return cardOf(page, reason, page.getByRole('button', { name: /Show details|Show less/ }));
}

/** Card date as the patient list shows it: "Wed 18 Nov 2026" (no comma). */
function apptDate(days: number): string {
  const [y, m, d] = futureDay(days).iso.split('-').map(Number);
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
    .formatToParts(new Date(Date.UTC(y, m - 1, d)));
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? '';
  return `${get('weekday')} ${get('day')} ${get('month')} ${get('year')}`;
}

/**
 * The doctor's Appointments section, narrowed to Thomas: one tab such as `today`, or one appointment opened by its
 * id (the section shows it above the list, expanded, whatever page of the newest-first list it would sit on; earlier
 * specs leave enough appointments of Thomas behind to push a card 30 to 90 days ahead off the first page).
 */
async function doctorAppointments(page: Page, opts: { tab?: string; id?: number } = {}): Promise<void> {
  await openSectionAt(page, 'doctor', 'appointments', { patient: THOMAS.systemId, tab: opts.tab, id: opts.id }, 'Appointments');
  await expect(page.getByText(/^Loading\b.*(…|\.\.\.)$/)).toHaveCount(0, { timeout: 20_000 });
  if (opts.id !== undefined) await expect(page.getByText('Opening appointment…')).toHaveCount(0, { timeout: 20_000 });
  await apiIdle(page);
}

test.describe('smoke appointments', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      // Cancel every active appointment this suite created (this run or an earlier, aborted one).
      const rows = await listAppointments(api.thomas, { patientSystemId: THOMAS.systemId, status: 'SCHEDULED,RESCHEDULED' }).catch(() => []);
      for (const a of rows) {
        if (String(a.reasonForVisit ?? '').startsWith(SMOKE_PREFIX)) await api.thomas.del(`/api/appointments/${a.appointmentId}`);
      }
    }
    await disposeAll(api);
  });

  async function mine(tag: string): Promise<any[]> {
    const rows = await listAppointments(api.thomas, { patientSystemId: THOMAS.systemId });
    return rows.filter(a => String(a.reasonForVisit ?? '').startsWith(tag));
  }

  test('patient books IN_PERSON + VIDEO, views details, reschedules; doctor sees details and cancels', async ({ browser }) => {
    const tag = smokeTag();
    const reasons = { IN_PERSON: `${tag} in person`, VIDEO: `${tag} video` };
    const slots = await freeSlots(api, 3); // in person, video, reschedule target
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);

    await test.step('patient books both through the form', async () => {
      const { page, monitor } = patient;
      for (const [i, type] of (['IN_PERSON', 'VIDEO'] as const).entries()) {
        const slot = slots[i];
        await visit(page, section(PATIENT_SECTIONS, 'Book Appointment'));
        await page.getByRole('button', { name: type === 'IN_PERSON' ? /^In Person/ : /^Video Call/ }).click();

        await page.getByLabel('Search doctors').fill('Banda');
        await page.getByRole('button', { name: /^Dr\. James Banda/ }).click();

        // Duration first: the times offered depend on it.
        await page.getByRole('group', { name: 'Duration', exact: true }).getByRole('button', { name: '30 min', exact: true }).click();
        const form = page.locator('div').filter({ has: page.getByText('Date & Time', { exact: true }) }).filter({ has: page.getByText('Select Date', { exact: true }) }).last();
        await pickDate(form, slot.days);
        await pickSlot(page, 'Select Time', slotTime(slot));
        if (type === 'IN_PERSON') {
          // In-person visits ask for a clinic location once the clinic has facilities; wait for that list first.
          await expect(page.getByText('Loading clinic locations…')).toHaveCount(0, { timeout: 20_000 });
          const location = page.getByLabel('Clinic location', { exact: true });
          if (await location.isVisible()) await location.selectOption({ index: 1 });
        }
        await page.getByPlaceholder('Describe your symptoms or reason for the appointment...').fill(reasons[type]);
        await page.getByPlaceholder('Any additional information for the doctor...').fill(`${tag} notes ${type}`);

        const created = page.waitForResponse(r => r.url().endsWith('/api/appointments') && r.request().method() === 'POST');
        await page.getByRole('button', { name: /Book Appointment$/ }).last().click();
        expect((await created).status(), `${type} booking accepted`).toBe(201);
        await expect(page.getByText('Appointment Booked!')).toBeVisible();
        await expect(page.getByText(/Your appointment with Dr\. James Banda has been scheduled\./)).toBeVisible();
        await page.getByRole('button', { name: 'Back to Dashboard' }).click();
        await expect(page.getByText('Health Overview', { exact: true })).toBeVisible();
        monitor.expectClean(`book ${type}`);
      }
      const list = await mine(tag);
      expect(list.map(a => a.type).sort()).toEqual(['IN_PERSON', 'VIDEO']);
      for (const [i, type] of (['IN_PERSON', 'VIDEO'] as const).entries()) {
        const a = list.find(x => x.type === type);
        expect(String(a.scheduledAt).slice(0, 16), `${type} time stored`).toBe(slotKey(slots[i]));
        expect(a.status).toBe('SCHEDULED');
      }
    });

    await test.step('patient sees both in My Appointments, opens details, reschedules the in-person visit', async () => {
      const { page, monitor } = patient;
      await visit(page, section(PATIENT_SECTIONS, 'My Appointments'));
      await apiIdle(page);

      const video = await revealPatientAppointment(page, reasons.VIDEO);
      await expect(video.getByText('Video Call', { exact: true })).toBeVisible();
      await expect(video.getByText(apptDate(slots[1].days), { exact: true })).toBeVisible();
      await expect(video.getByText(`${slotTime(slots[1])} CAT`, { exact: true })).toBeVisible();
      await expect(video.getByText('Dr. James Banda')).toBeVisible();
      await video.getByRole('button', { name: /Show details/ }).click();
      await expect(video.getByText(`${tag} notes VIDEO`)).toBeVisible();

      const inPerson = await revealPatientAppointment(page, reasons.IN_PERSON);
      await expect(inPerson.getByText('In Person', { exact: true })).toBeVisible();
      await expect(inPerson.getByText(apptDate(slots[0].days), { exact: true })).toBeVisible();
      await expect(inPerson.getByText('Upcoming', { exact: true })).toBeVisible();
      await expectNoArtifacts(page, 'my appointments');

      await inPerson.getByRole('button', { name: 'Reschedule appointment with Dr. James Banda' }).click();
      // The "New time" group appears only once a date is chosen, so the panel is anchored on its confirm button.
      const panel = page.locator('div').filter({ has: page.getByRole('heading', { name: 'Choose a new date and time' }) })
        .filter({ has: page.getByRole('button', { name: 'Confirm new time', exact: true }) }).last();
      const target = slots[2];
      await pickDate(panel, target.days);
      await pickSlot(page, 'New time', slotTime(target));
      const put = page.waitForResponse(r => /\/api\/appointments\/\d+$/.test(r.url()) && r.request().method() === 'PUT');
      await panel.getByRole('button', { name: 'Confirm new time' }).click();
      expect((await put).status(), 'reschedule accepted').toBe(200);
      // The card confirms the move (soft: the rest of the flow still runs when this is missing).
      await expect.soft(page.getByText('Your appointment has been moved to the new time.'), 'reschedule confirmation shown').toBeVisible();
      // The list is ordered by date, so the moved visit can land past "Show N more": reveal it again.
      const moved = await revealPatientAppointment(page, reasons.IN_PERSON);
      await expect(moved.getByText(apptDate(target.days), { exact: true })).toBeVisible();
      await expect(moved.getByText('Rescheduled', { exact: true }).first()).toBeVisible();

      const a = (await mine(tag)).find(x => x.type === 'IN_PERSON');
      expect(String(a.scheduledAt).slice(0, 16), 'new time stored').toBe(slotKey(target));
      monitor.expectClean('my appointments');
    });

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);

    const ids = Object.fromEntries((await mine(tag)).map(a => [a.type, a.appointmentId as number])) as Record<'IN_PERSON' | 'VIDEO', number>;

    await test.step('doctor sees both and opens the appointment details', async () => {
      const { page, monitor } = doctor;
      for (const type of ['IN_PERSON', 'VIDEO'] as const) {
        await doctorAppointments(page, { id: ids[type] });
        await expect(page.getByRole('heading', { name: 'Opened appointment' })).toBeVisible();
        // The whole card (details panel and action row included) is the innermost box with the reason and Cancel.
        const card = cardOf(page, reasons[type], page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
        await expect(card).toBeVisible();
        await expect(card.getByText('Thomas Dube', { exact: true }).first()).toBeVisible();
        const toggle = card.getByRole('button', { name: /show appointment details$/i });
        if (await toggle.count()) await toggle.click();
        await expect(card.getByText('PAT-00021', { exact: true })).toBeVisible();
        await expect(card.getByText(/^#\d+$/)).toBeVisible();
        await expect(card.getByText(type === 'VIDEO' ? 'Video' : 'In Person', { exact: true }).first()).toBeVisible();
        await expect(card.getByText(type === 'VIDEO' ? 'Scheduled' : 'Rescheduled', { exact: true }).first()).toBeVisible();
      }
      await expectNoArtifacts(page, 'doctor appointments');
      monitor.expectClean('doctor appointments');
    });

    await test.step('doctor cancels both; the patient sees them cancelled', async () => {
      const { page, monitor } = doctor;
      for (const type of ['IN_PERSON', 'VIDEO'] as const) {
        await doctorAppointments(page, { id: ids[type] });
        const card = cardOf(page, reasons[type], page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
        const done = page.waitForResponse(r => /\/mark-cancelled$/.test(r.url()) && r.request().method() === 'PATCH');
        await card.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }).click(); // confirm + "Done" dialogs are accepted
        expect((await done).status(), `${type} cancel accepted`).toBe(200);
        const after = cardOf(page, reasons[type], page.getByRole('button', { name: /appointment details$/i }));
        await expect(after.getByText('Cancelled', { exact: true }).first()).toBeVisible();
        await expect(after.getByRole('button', { name: 'Cancel appointment with Thomas Dube' })).toHaveCount(0);
      }
      monitor.expectClean('doctor cancel');
      for (const a of await mine(tag)) expect(a.status, `${a.type} cancelled`).toBe('CANCELLED');

      await visit(patient.page, section(PATIENT_SECTIONS, 'Dashboard'));
      await visit(patient.page, section(PATIENT_SECTIONS, 'My Appointments'));
      await apiIdle(patient.page);
      const card = await revealPatientAppointment(patient.page, reasons.VIDEO);
      await expect(card.getByText('Cancelled', { exact: true }).first()).toBeVisible();
      await expect(card.getByRole('button', { name: /^Reschedule appointment with / })).toHaveCount(0);
      patient.monitor.expectClean('patient after cancel');
    });
  });

  test('doctor can reschedule an upcoming appointment from the UI', async ({ browser }) => {
    const tag = smokeTag();
    const [slot] = await freeSlots(api, 1);
    const created = await api.thomas.ok(api.thomas.post('/api/appointments', {
      patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId,
      scheduledAt: `${slotKey(slot)}:00`, durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: `${tag} doctor reschedule`,
    }), 'create appointment');
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page } = doctor;
    await doctorAppointments(page, { id: created.appointmentId });
    const card = cardOf(page, `${tag} doctor reschedule`, page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
    await expect(card).toBeVisible();
    await expect(card.getByRole('button', { name: 'Reschedule appointment with Thomas Dube' }),
      'doctor has a Reschedule action on an upcoming appointment').toBeVisible({ timeout: 5_000 });
  });

  test('doctor marks a started appointment completed and another no-show from the UI; the patient sees both', async ({ browser }) => {
    const tag = smokeTag();
    const reasons = { complete: `${tag} complete`, noShow: `${tag} no-show` };
    const slots = await freeSlots(api, 2);
    const ids: Record<keyof typeof reasons, number> = { complete: 0, noShow: 0 };
    for (const [i, key] of (['complete', 'noShow'] as const).entries()) {
      const a = await api.thomas.ok(api.thomas.post('/api/appointments', {
        patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId,
        scheduledAt: `${slotKey(slots[i])}:00`, durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: reasons[key],
      }), `create ${key}`);
      ids[key] = a.appointmentId;
      // The API refuses past start times, so move it into the past directly in the local dev DB:
      // it started 1-2 hours ago today (odd seconds keep it clear of other active slots).
      const started = new Date(Date.now() - (60 + 37 * (i + 1)) * 60_000 - (i + 1) * 7_000);
      await setAppointmentStart(ids[key], harareLocal(started).slice(0, 17) + String(11 + i * 13).padStart(2, '0'));
    }

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    // Today's appointments of Thomas (the moved ones started earlier today).
    await doctorAppointments(page, { tab: 'today' });

    for (const [key, action, path, label, status] of [
      ['complete', 'Complete', 'mark-completed', 'Completed', 'COMPLETED'],
      ['noShow',   'No-show',  'mark-no-show',   'No Show',   'NO_SHOW'],
    ] as const) {
      await test.step(`${action} via the UI`, async () => {
        const button = page.getByRole('button', { name: `${action} appointment with Thomas Dube`, exact: true });
        const card = cardOf(page, reasons[key], button);
        await expect(card, `${key}: started appointment offers ${action}`).toBeVisible();
        await expect(card.getByRole('button', { name: 'Cancel appointment with Thomas Dube' })).toBeVisible();
        const done = page.waitForResponse(r => r.url().endsWith(`/api/doctors/appointments/${ids[key]}/${path}`) && r.request().method() === 'PATCH');
        await card.getByRole('button', { name: `${action} appointment with Thomas Dube`, exact: true }).click(); // confirm + "Done" accepted
        expect((await done).status(), `${action} accepted`).toBe(200);
        const after = cardOf(page, reasons[key], page.getByRole('button', { name: /appointment details$/i }));
        await expect(after.getByText(label, { exact: true }).first()).toBeVisible();
        await expect(after.getByRole('button', { name: /^(Complete|No-show|Cancel) appointment with Thomas Dube$/ }), 'no status actions left').toHaveCount(0);
        const a = (await mine(tag)).find(x => x.appointmentId === ids[key]);
        expect(a?.status, `${key} stored`).toBe(status);
      });
    }
    monitor.expectClean('doctor complete / no-show');

    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    await visit(patient.page, section(PATIENT_SECTIONS, 'My Appointments'));
    await apiIdle(patient.page);
    for (const [key, label] of [['complete', 'Completed'], ['noShow', 'No Show']] as const) {
      const card = await revealPatientAppointment(patient.page, reasons[key]);
      await expect(card.getByText(label, { exact: true }).first(), `patient sees ${label}`).toBeVisible();
      await expect(card.getByRole('button', { name: /^Reschedule appointment with / })).toHaveCount(0);
    }
    await expectNoArtifacts(patient.page, 'patient appointments after complete / no-show');
    patient.monitor.expectClean('patient after complete / no-show');
  });

  test('booking form exposes the selected appointment type, doctor and time to assistive technology', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;
    await visit(page, section(PATIENT_SECTIONS, 'Book Appointment'));
    const video = page.getByRole('button', { name: /^Video Call/ });
    await video.click();
    await page.getByLabel('Search doctors').fill('Banda');
    const doc = page.getByRole('button', { name: /^Dr\. James Banda/ });
    await doc.click();
    await expect(video, 'chosen appointment type is announced as selected').toHaveAttribute('aria-pressed', 'true');
    await expect(doc, 'chosen doctor is announced as selected').toHaveAttribute('aria-pressed', 'true');
    // Times are named "HH:mm", or "HH:mm, not available" when they cannot be chosen.
    const [slot] = await freeSlots(api, 1);
    const form = page.locator('div').filter({ has: page.getByText('Date & Time', { exact: true }) }).filter({ has: page.getByText('Select Date', { exact: true }) }).last();
    await pickDate(form, slot.days);
    const times = page.getByRole('group', { name: 'Select Time', exact: true });
    await expect(times.getByRole('button').first()).toHaveAccessibleName(/^\d{2}:\d{2}(, not available|, current time)?$/);
    await pickSlot(page, 'Select Time', slotTime(slot));
  });
});
