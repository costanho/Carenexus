import { expect, type Locator, test } from '@playwright/test';
import { type Clients, disposeAll, loginAll } from './support/api';
import { setAppointmentStart } from './support/db';
import { JAMES, THOMAS } from './support/env';
import {
  bookWithJames, cancelFixAppointments, cardOf, clinicDateLabel, clinicToday, fixTag, freeSlots, pickDate,
  revealPatientAppointment, slotKey, slotLabel, slotUtc,
} from './support/fix-doctor';
import {
  DOCTOR_SECTIONS, type MonitoredPage, PATIENT_SECTIONS, apiIdle, expectNoArtifacts, marker, pageOverflow,
  signedInPage, visit,
} from './support/smoke';

// Doctor fixes: reschedule from the web UI (and the patient sees it), real appointment types on
// Consultations, Schedule "Today" without cancelled appointments, no 375 px overflow on the
// dashboard, section titles as headings, and "Message patient" opening the private conversation.
// Appointments created here have an "e2e-fix-" reason and are cancelled in afterAll.

const section = (list: typeof DOCTOR_SECTIONS, nav: string) => list.find(s => s.nav === nav)!;

test.describe('doctor fixes', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) await cancelFixAppointments(api.thomas);
    await disposeAll(api);
  });

  test('doctor reschedules a VIDEO appointment from the UI; the patient and the call window follow', async ({ browser }) => {
    const tag = fixTag();
    const reason = `${tag} reschedule`;
    const [from, to] = await freeSlots(api.james, api.thomas, 2);
    const id = await bookWithJames(api.thomas, from, 'VIDEO', reason);
    const before = await api.thomas.ok(api.thomas.get(`/api/appointments/${id}`), 'appointment before');

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;

    await test.step('doctor picks a new date and time on the appointment card', async () => {
      await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
      await page.getByLabel('Search appointments by patient').fill('Thomas');
      // The card is the innermost box with the reason and Cancel (Reschedule hides while its panel is open).
      const card = cardOf(page, reason, page.getByRole('button', { name: 'Cancel appointment with Thomas Dube' }));
      await card.getByRole('button', { name: 'Reschedule appointment with Thomas Dube' }).click();
      await expect(card.getByRole('heading', { name: 'Choose a new date and time' })).toBeVisible();

      // Confirm stays disabled until both are chosen.
      await expect(card.getByRole('button', { name: 'Confirm new time' })).toBeDisabled();
      await pickDate(card, to.days);
      const slot = card.getByRole('radio', { name: slotLabel(to), exact: true });
      await expect(slot).toBeEnabled();
      await slot.click();
      await expect(slot).toBeChecked();
      await expect(card.getByText(`New time: ${clinicDateLabel(to.days)}, ${slotLabel(to)} CAT`)).toBeVisible();

      const saved = page.waitForResponse(r => r.url().endsWith(`/api/appointments/${id}/reschedule`) && r.request().method() === 'PATCH');
      await card.getByRole('button', { name: 'Confirm new time' }).click();
      expect((await saved).status(), 'reschedule accepted').toBe(200);

      await expect(page.getByText(
        `Appointment with Thomas Dube moved to ${clinicDateLabel(to.days)}, ${slotLabel(to)} CAT.`, { exact: true },
      ), 'success confirmation').toBeVisible();
      // The refreshed list shows the card as Rescheduled.
      const after = cardOf(page, reason, page.getByRole('button', { name: /appointment details$/ }));
      await expect(after.getByText('Rescheduled', { exact: true }).first()).toBeVisible();
      await expectNoArtifacts(page, 'doctor reschedule');
      monitor.expectClean('doctor reschedule');
    });

    await test.step('the API has the new time and the call window moved with it', async () => {
      const a = await api.thomas.ok(api.thomas.get(`/api/appointments/${id}`), 'appointment after');
      expect(String(a.scheduledAt).slice(0, 16), 'new start').toBe(slotKey(to));
      expect(a.status).toBe('RESCHEDULED');
      expect(a.durationMinutes, 'duration kept').toBe(before.durationMinutes);
      const newStart = slotUtc(to).getTime();
      await expect.poll(async () => {
        const r = await api.thomas.get(`/api/appointments/${id}`);
        const opens = r.body?.joinWindowOpensAt ? new Date(r.body.joinWindowOpensAt).getTime() : NaN;
        return {
          moved:  opens <= newStart && newStart - opens <= 60 * 60_000,
          active: !['CANCELLED', 'ENDED', 'FAILED'].includes(String(r.body?.callStatus)),
        };
      }, { message: 'join window follows the new start, call not ended', timeout: 30_000 })
        .toEqual({ moved: true, active: true });
    });

    await test.step('the patient sees the new time in My Appointments', async () => {
      const patient = await signedInPage(browser, THOMAS);
      pages.push(patient);
      await visit(patient.page, PATIENT_SECTIONS.find(s => s.nav === 'My Appointments')!);
      await apiIdle(patient.page);
      const card = await revealPatientAppointment(patient.page, reason);
      await expect(card.getByText(clinicDateLabel(to.days), { exact: true })).toBeVisible();
      await expect(card.getByText(slotKey(to).slice(11), { exact: true })).toBeVisible();
      await expect(card.getByText('Rescheduled', { exact: true }).first()).toBeVisible();
      patient.monitor.expectClean('patient after doctor reschedule');
    });
  });

  test('a started or cancelled appointment offers no Reschedule action', async ({ browser }) => {
    const tag = fixTag();
    const reason = `${tag} no reschedule`;
    const [slot] = await freeSlots(api.james, api.thomas, 1);
    const id = await bookWithJames(api.thomas, slot, 'IN_PERSON', reason);
    await api.thomas.ok(api.thomas.del(`/api/appointments/${id}`), 'cancel');

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page } = doctor;
    await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
    await page.getByLabel('Search appointments by patient').fill('Thomas');
    const card = cardOf(page, reason, page.getByRole('button', { name: /appointment details$/ }));
    await expect(card.getByText('Cancelled', { exact: true }).first()).toBeVisible();
    await expect(card.getByRole('button', { name: /^Reschedule/ })).toHaveCount(0);
  });

  test('Consultations show the real appointment type, never "Unknown"', async ({ browser }) => {
    const list = await api.james.ok(api.james.get<any[]>(`/api/consultations/doctor/${JAMES.systemId}`), 'consultations');
    test.skip(list.length === 0, 'no consultations in the dev seed');
    const schedule = await api.james.ok(api.james.get<any[]>('/api/doctors/schedule'), 'schedule');
    const types = new Set(list
      .map(c => schedule.find(e => e.type === 'APPOINTMENT' && e.appointmentId === c.appointmentId)?.appointmentType)
      .filter(Boolean));
    const label: Record<string, string> = { IN_PERSON: 'In Person', VIDEO: 'Video', PHONE: 'Phone' };

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    await visit(page, section(DOCTOR_SECTIONS, 'Consultations'));
    await apiIdle(page);
    await expect(page.getByText('Unknown', { exact: true })).toHaveCount(0);
    expect(types.size, 'seed consultations are linked to appointments').toBeGreaterThan(0);
    for (const t of types) {
      await expect(page.getByText(label[t as string], { exact: true }).first(), `${t} badge`).toBeVisible();
    }
    monitor.expectClean('doctor consultations');
  });

  test('Schedule "Today" counts only active appointments, like the dashboard', async ({ browser }) => {
    const tag = fixTag();
    const reason = `${tag} cancelled today`;
    // A cancelled appointment today: book ahead, cancel, then move it to this morning (local DB only).
    const [slot] = await freeSlots(api.james, api.thomas, 1);
    const id = await bookWithJames(api.thomas, slot, 'IN_PERSON', reason);
    await api.thomas.ok(api.thomas.del(`/api/appointments/${id}`), 'cancel');
    const today = clinicToday();
    await setAppointmentStart(id, `${today}T06:07:00`);

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    await apiIdle(page);

    const schedule = await api.james.ok(api.james.get<any[]>('/api/doctors/schedule'), 'schedule');
    const todays = schedule.filter(e => e.type === 'APPOINTMENT' && String(e.scheduledAt).startsWith(today));
    const active = todays.filter(e => e.appointmentStatus !== 'CANCELLED').length;
    expect(todays.length, 'today includes the cancelled one').toBeGreaterThan(active);

    await expect(page.getByLabel(new RegExp(`^Today's Appointments: ${active}\\.`)), 'dashboard Today').toBeVisible();

    await visit(page, section(DOCTOR_SECTIONS, 'Schedule'));
    await expect(page.getByTestId('schedule-stat-today').getByText(String(active), { exact: true }), 'Schedule Today stat').toBeVisible();
    await expect(page.getByText(`${active} appointment${active === 1 ? '' : 's'}`, { exact: true }), 'default list').toBeVisible();
    await expect(page.getByText(reason, { exact: true }), 'cancelled hidden by default').toHaveCount(0);

    await page.getByRole('button', { name: 'All incl. cancelled' }).click();
    await expect(page.getByText(`${todays.length} appointment${todays.length === 1 ? '' : 's'}`, { exact: true })).toBeVisible();
    await expect(page.getByText(reason, { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Cancelled', exact: true }).click();
    await expect(page.getByText(reason, { exact: true })).toBeVisible();
    monitor.expectClean('doctor schedule');
  });

  test('375 px: the doctor dashboard and top bar fit the screen', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES, { viewport: { width: 375, height: 812 } });
    pages.push(doctor);
    const { page, monitor } = doctor;
    await expect(marker(page, DOCTOR_SECTIONS[0])).toBeVisible();
    await apiIdle(page);
    expect(await pageOverflow(page), 'doctor dashboard at 375 px').toEqual([]);
    // The collapsed sidebar rail keeps its own Sign out; check the one in the top bar, next to the profile.
    const profile = page.getByRole('button', { name: /Dr\. James Banda/ });
    await expect(profile).toBeVisible();
    const topBar = page.locator('div').filter({ has: profile }).filter({ has: page.getByRole('button', { name: 'Sign out' }) }).last();
    await expect(topBar.getByRole('button', { name: 'Sign out' })).toBeVisible();
    monitor.expectClean('doctor 375 px');
  });

  test('every doctor section title is a heading', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page } = doctor;
    for (const s of DOCTOR_SECTIONS) {
      await visit(page, s);
      await expect(page.getByRole('heading', { name: s.title, exact: true }).first(), `${s.nav} title`).toBeVisible();
    }
  });

  test('Message patient opens the private conversation with that patient', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    const messages = section(DOCTOR_SECTIONS, 'Messages');

    async function openFrom(button: Locator): Promise<number> {
      const opened = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
      await button.click();
      const res = await opened;
      expect([200, 201], 'thread opened').toContain(res.status());
      const thread = await res.json();
      expect(thread.type).toBe('PRIVATE');
      expect(thread.patientSystemId).toBe(THOMAS.systemId);
      expect((thread.members as any[]).map(m => m.systemId).sort()).toEqual([JAMES.systemId, THOMAS.systemId].sort());

      await expect(marker(page, messages)).toBeVisible();
      const others = (thread.members as any[]).filter(m => m.systemId !== JAMES.systemId).map(m => m.label).join(', ');
      const title = (thread.subject ?? '').trim() || others;
      await expect(page.getByRole('heading', { name: title, exact: true }).first(), 'thread open').toBeVisible();
      return thread.threadId as number;
    }

    await visit(page, section(DOCTOR_SECTIONS, 'Patients'));
    await page.getByLabel('Search patients by name or email').fill('Thomas');
    const fromPatients = await openFrom(page.getByRole('button', { name: 'Message Thomas Dube' }).first());

    await visit(page, section(DOCTOR_SECTIONS, 'Appointments'));
    await page.getByLabel('Search appointments by patient').fill('Thomas');
    const fromAppointments = await openFrom(page.getByRole('button', { name: 'Message Thomas Dube' }).first());
    expect(fromAppointments, 'same conversation, no duplicate').toBe(fromPatients);

    // The existing private thread between Thomas and Dr. Banda (the API returns it, never a second one).
    const viaApi = await api.thomas.ok(api.thomas.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'PRIVATE', memberSystemIds: [JAMES.systemId],
    }), 'private thread');
    expect(viaApi.threadId).toBe(fromPatients);
    monitor.expectClean('message patient');
  });
});
