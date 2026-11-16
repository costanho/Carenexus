import { expect, type Locator, type Page, test } from '@playwright/test';
import { type Api, type Clients, disposeAll, harareLocal, loginAll } from './support/api';
import { setAppointmentStart } from './support/db';
import { JAMES, THOMAS } from './support/env';
import {
  DOCTOR_SECTIONS, type MonitoredPage, PATIENT_SECTIONS, SMOKE_PREFIX, apiIdle, expectNoArtifacts, futureDay,
  signedInPage, smokeTag, visit,
} from './support/smoke';

// Smoke: the patient books IN_PERSON and VIDEO appointments through the booking form (calendar +
// time picker), sees them in My Appointments, opens the details and reschedules one; the doctor
// sees them, opens the details, reschedules and cancels through the UI. Everything created here
// has an "e2e-smoke-" reason and is cancelled in afterAll.

interface Slot { days: number; hour24: number; minute: number }

const section = (list: typeof PATIENT_SECTIONS, nav: string) => list.find(s => s.nav === nav)!;

/** Local "YYYY-MM-DDTHH:MM" for a slot (clinic time, matches the API's LocalDateTime). */
function slotKey(s: Slot): string {
  return `${futureDay(s.days).iso}T${String(s.hour24).padStart(2, '0')}:${String(s.minute).padStart(2, '0')}`;
}

/** Picks free slots for Dr. Banda and Thomas, 30-90 days ahead, on the quarter hour. */
async function freeSlots(james: Api, thomas: Api, count: number): Promise<Slot[]> {
  const busy = new Set<string>();
  for (const [api, path] of [[james, `/api/appointments/doctor/${JAMES.systemId}`], [thomas, `/api/appointments/patient/${THOMAS.systemId}`]] as const) {
    const list = await api.ok(api.get<any[]>(path), `list ${path}`);
    for (const a of list) {
      if (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') busy.add(String(a.scheduledAt ?? a.scheduled_at).slice(0, 16));
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
    if (busy.has(key) || out.some(o => slotKey(o) === key)) continue;
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

/** Selects a time in a TimePicker inside `scope` (AM/PM, hour, minute buttons). */
async function pickTime(scope: Locator, hour24: number, minute: number): Promise<string> {
  const picker = scope.locator('div').filter({ has: scope.page().getByText('AM / PM', { exact: true }) })
    .filter({ has: scope.page().getByText('Minute', { exact: true }) }).last();
  const ap = hour24 < 12 ? 'AM' : 'PM';
  const h12 = hour24 % 12 === 0 ? 12 : hour24 % 12;
  await picker.getByText(ap, { exact: true }).click();
  await picker.getByText(String(h12), { exact: true }).click();
  await picker.getByText(`:${String(minute).padStart(2, '0')}`, { exact: true }).click();
  const shown = `${h12}:${String(minute).padStart(2, '0')} ${ap}`;
  await expect(picker.getByText(shown, { exact: true })).toBeVisible();
  return shown;
}

/** The innermost card that holds `reason` and the given control. */
function cardOf(page: Page, reason: string, control: Locator): Locator {
  return page.locator('div').filter({ has: page.getByText(reason, { exact: true }) }).filter({ has: control }).last();
}

/** Patient > My Appointments: reveals (pages through "Show N more") the card for `reason`. */
async function revealPatientAppointment(page: Page, reason: string): Promise<Locator> {
  const text = page.getByText(reason, { exact: true });
  for (let i = 0; i < 30 && !(await text.isVisible()); i++) {
    await page.getByText(/^Show \d+ more appointments?$/).click();
  }
  await expect(text).toBeVisible();
  return cardOf(page, reason, page.getByRole('button', { name: /Show details|Show less/ }));
}

function apptDate(days: number): string {
  // Patient list format, e.g. "Wed, 18 Nov 2026".
  return futureDay(days).date.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
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
      const list = await api.thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`);
      for (const a of (list.body ?? []) as any[]) {
        const reason = String(a.reasonForVisit ?? a.reason_for_visit ?? '');
        if (reason.startsWith(SMOKE_PREFIX) && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED')) {
          await api.thomas.del(`/api/appointments/${a.appointmentId ?? a.appointment_id}`);
        }
      }
    }
    await disposeAll(api);
  });

  async function mine(tag: string): Promise<any[]> {
    const list = await api.thomas.ok(api.thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`), 'patient appointments');
    return list.filter(a => String(a.reasonForVisit ?? a.reason_for_visit ?? '').startsWith(tag));
  }

  test('patient books IN_PERSON + VIDEO, views details, reschedules; doctor sees details and cancels', async ({ browser }) => {
    const tag = smokeTag();
    const reasons = { IN_PERSON: `${tag} in person`, VIDEO: `${tag} video` };
    const slots = await freeSlots(api.james, api.thomas, 3); // in person, video, reschedule target
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);

    await test.step('patient books both through the form', async () => {
      const { page, monitor } = patient;
      for (const [i, type] of (['IN_PERSON', 'VIDEO'] as const).entries()) {
        const slot = slots[i];
        await visit(page, section(PATIENT_SECTIONS, 'Book Appointment'));
        const typeBtn = page.getByRole('button', { name: type === 'IN_PERSON' ? /In Person/ : /Video Call/ });
        await typeBtn.click();

        await page.getByLabel('Search doctors').fill('Banda');
        const doc = page.getByRole('button', { name: /Dr\. James Banda/ });
        await doc.click();

        const form = page.locator('div').filter({ has: page.getByText('Date & Time', { exact: true }) }).filter({ has: page.getByText('Select Date', { exact: true }) }).last();
        await pickDate(form, slot.days);
        await pickTime(form, slot.hour24, slot.minute);
        await page.getByText('30 min', { exact: true }).click();
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
        expect(String(a.scheduledAt ?? a.scheduled_at).slice(0, 16), `${type} time stored`).toBe(slotKey(slots[i]));
        expect(a.status).toBe('SCHEDULED');
      }
    });

    await test.step('patient sees both in My Appointments, opens details, reschedules the in-person visit', async () => {
      const { page, monitor } = patient;
      await visit(page, section(PATIENT_SECTIONS, 'My Appointments'));
      await apiIdle(page);

      const video = await revealPatientAppointment(page, reasons.VIDEO);
      await expect(video.getByText('Video Call', { exact: true })).toBeVisible();
      await expect(video.getByText(apptDate(slots[1].days))).toBeVisible();
      await expect(video.getByText('Dr. James Banda')).toBeVisible();
      await video.getByRole('button', { name: /Show details/ }).click();
      await expect(video.getByText(`${tag} notes VIDEO`)).toBeVisible();

      const inPerson = await revealPatientAppointment(page, reasons.IN_PERSON);
      await expect(inPerson.getByText('In Person', { exact: true })).toBeVisible();
      await expect(inPerson.getByText(apptDate(slots[0].days))).toBeVisible();
      await expect(inPerson.getByText('Upcoming', { exact: true })).toBeVisible();
      await expectNoArtifacts(page, 'my appointments');

      await inPerson.getByRole('button', { name: /Reschedule$/ }).click();
      const panel = page.locator('div').filter({ has: page.getByText('Choose a new date and time', { exact: true }) }).filter({ has: page.getByText('Minute', { exact: true }) }).last();
      const target = slots[2];
      await pickDate(panel, target.days);
      await pickTime(panel, target.hour24, target.minute);
      const put = page.waitForResponse(r => /\/api\/appointments\/\d+$/.test(r.url()) && r.request().method() === 'PUT');
      await panel.getByRole('button', { name: 'Confirm new time' }).click();
      expect((await put).status(), 'reschedule accepted').toBe(200);
      // The card confirms the move (soft: the rest of the flow still runs when this is missing).
      await expect.soft(page.getByText('Your appointment has been moved to the new time.'), 'reschedule confirmation shown').toBeVisible();
      // The list is ordered by date, so the moved visit can land past "Show N more": reveal it again.
      const moved = await revealPatientAppointment(page, reasons.IN_PERSON);
      await expect(moved.getByText(apptDate(target.days))).toBeVisible();

      const a = (await mine(tag)).find(x => x.type === 'IN_PERSON');
      expect(String(a.scheduledAt ?? a.scheduled_at).slice(0, 16), 'new time stored').toBe(slotKey(target));
      monitor.expectClean('my appointments');
    });

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);

    await test.step('doctor sees both and opens the appointment details', async () => {
      const { page, monitor } = doctor;
      await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
      await apiIdle(page);
      await page.getByLabel('Search appointments by patient').fill('Thomas');
      for (const type of ['IN_PERSON', 'VIDEO'] as const) {
        // The whole card (details panel and action row included) is the innermost box with the reason and Cancel.
        const card = cardOf(page, reasons[type], page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
        await expect(card).toBeVisible();
        await expect(card.getByText('Thomas Dube', { exact: true })).toBeVisible();
        await card.getByRole('button', { name: 'Show appointment details' }).click();
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
        const card = cardOf(page, reasons[type], page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
        const done = page.waitForResponse(r => /\/mark-cancelled$/.test(r.url()) && r.request().method() === 'PATCH');
        await card.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }).click(); // confirm + "Done" dialogs are accepted
        expect((await done).status(), `${type} cancel accepted`).toBe(200);
        const after = cardOf(page, reasons[type], page.getByRole('button', { name: /appointment details$/ }));
        await expect(after.getByText('Cancelled', { exact: true }).first()).toBeVisible();
        await expect(after.getByRole('button', { name: 'Cancel appointment with Thomas Dube' })).toHaveCount(0);
      }
      monitor.expectClean('doctor cancel');
      for (const a of await mine(tag)) expect(a.status, `${a.type} cancelled`).toBe('CANCELLED');

      await visit(patient.page, section(PATIENT_SECTIONS, 'Dashboard'));
      await visit(patient.page, section(PATIENT_SECTIONS, 'My Appointments'));
      await apiIdle(patient.page);
      const card = await revealPatientAppointment(patient.page, reasons.VIDEO);
      await expect(card.getByText('Cancelled', { exact: true })).toBeVisible();
      await expect(card.getByRole('button', { name: /Reschedule$/ })).toHaveCount(0);
      patient.monitor.expectClean('patient after cancel');
    });
  });

  test('doctor can reschedule an upcoming appointment from the UI', async ({ browser }) => {
    const tag = smokeTag();
    const [slot] = await freeSlots(api.james, api.thomas, 1);
    const [y, m, d] = futureDay(slot.days).iso.split('-').map(Number);
    await api.thomas.ok(api.thomas.post('/api/appointments', {
      patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId,
      scheduledAt: harareLocal(new Date(Date.UTC(y, m - 1, d, slot.hour24 - 2, slot.minute))), // Harare = UTC+2
      durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: `${tag} doctor reschedule`,
    }), 'create appointment');
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page } = doctor;
    await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
    await page.getByLabel('Search appointments by patient').fill('Thomas');
    const card = cardOf(page, `${tag} doctor reschedule`, page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
    await expect(card).toBeVisible();
    // Backend supports it (PATCH /api/doctors/appointments/{id}/mark-rescheduled, PATCH /api/appointments/{id}/reschedule);
    // the doctor's card should offer it next to Cancel.
    await expect(card.getByRole('button', { name: /^Reschedule/ }), 'doctor has a Reschedule action on an upcoming appointment')
      .toBeVisible({ timeout: 5_000 });
  });

  test('doctor marks a started appointment completed and another no-show from the UI; the patient sees both', async ({ browser }) => {
    const tag = smokeTag();
    const reasons = { complete: `${tag} complete`, noShow: `${tag} no-show` };
    const slots = await freeSlots(api.james, api.thomas, 2);
    const ids: Record<keyof typeof reasons, number> = { complete: 0, noShow: 0 };
    for (const [i, key] of (['complete', 'noShow'] as const).entries()) {
      const [y, m, d] = futureDay(slots[i].days).iso.split('-').map(Number);
      const a = await api.thomas.ok(api.thomas.post('/api/appointments', {
        patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId,
        scheduledAt: harareLocal(new Date(Date.UTC(y, m - 1, d, slots[i].hour24 - 2, slots[i].minute))),
        durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: reasons[key],
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
    await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
    await apiIdle(page);
    await page.getByLabel('Search appointments by patient').fill('Thomas');

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
        const after = cardOf(page, reasons[key], page.getByRole('button', { name: /appointment details$/ }));
        await expect(after.getByText(label, { exact: true }).first()).toBeVisible();
        await expect(after.getByRole('button', { name: /appointment with Thomas Dube$/ }), 'no status actions left').toHaveCount(0);
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
      await expect(card.getByRole('button', { name: /Reschedule$/ })).toHaveCount(0);
    }
    await expectNoArtifacts(patient.page, 'patient appointments after complete / no-show');
    patient.monitor.expectClean('patient after complete / no-show');
  });

  test('booking form exposes the selected appointment type and doctor to assistive technology', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;
    await visit(page, section(PATIENT_SECTIONS, 'Book Appointment'));
    const video = page.getByRole('button', { name: /Video Call/ });
    await video.click();
    await page.getByLabel('Search doctors').fill('Banda');
    const doc = page.getByRole('button', { name: /Dr\. James Banda/ });
    await doc.click();
    const state = (l: typeof doc) => l.evaluate(el => ['aria-selected', 'aria-pressed', 'aria-checked'].some(a => el.getAttribute(a) === 'true'));
    expect.soft(await state(video), 'chosen appointment type is announced as selected').toBe(true);
    expect.soft(await state(doc), 'chosen doctor is announced as selected').toBe(true);
  });
});
