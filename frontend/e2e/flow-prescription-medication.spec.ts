import { expect, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api, loginAll, withQuery } from './support/api';
import { JAMES, SARAH, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiLogin, apiResponse, clinicNow, dialog, firstPage, grantThomasAccess, hhmm, openAt, revokeThomasAccess, roundUp,
  throwawayCaregiver,
} from './flows/clinical-helpers';

// Flow: prescription with dose times, the daily schedule and reminders.
// Dr. Banda issues a prescription for Thomas with two dose times later today. Thomas sees today's doses, marks one
// taken (and undoes and redoes it) and marks the other missed and back. Sarah, whose grant lets her manage the care
// plan, marks a dose taken from the schedule link of her loved one (stored as her mark) and undoes it; a caregiver
// whose grant is view-only sees the schedule but cannot mark doses (on the web and on the server). The doctor
// replaces one dose time; the future dose moves and the dose already taken stays. Finally Thomas adds a manual
// reminder in the Reminders tab, pauses it and deletes it.
//
// The dose times are 90 to 150 minutes ahead of the clinic's current time (doses are only created for the rest of
// today and tomorrow, and only today's and yesterday's doses can be marked), so the schedule tests are skipped
// after 21:00 clinic time.
//
// Free texts start with the run's e2e marker (special instructions, reminder title and message). The medication
// name ends with the marker's last four characters, so dose names stay readable; the frequency is a plain dosing
// phrase and the dose times are given explicitly, so nothing is derived from it.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): prescriptions (special instructions "e2e…", cancelled in
// afterAll so Thomas's newest active prescriptions are the seed's again),
// medication_schedule_plans and medication_schedules (of that prescription), reminders (title and message "e2e…"),
// dependent_access (grant from Thomas to the throwaway caregiver, revoked afterwards), notifications (new
// prescription and grant change notices), outbound_messages (their fan-out), idempotency_keys, audit_log,
// users/caregivers/legal_acceptances (throwaway caregiver e2e-…@carenexus.test).

test.describe('flow: prescription, dose schedule and reminders', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const medication = `Amlodipine ${tag.slice(-4)}`;
  const dosage = '5 mg';
  const instructions = `${tag} take with a glass of water`;
  const pages: MonitoredPage[] = [];
  const now = clinicNow();
  const today = now.date;
  const base = roundUp(now.minutes + 90, 5);
  const [t1, t2, t3] = [hhmm(base), hhmm(base + 30), hhmm(base + 60)];
  const tooLate = base + 60 >= 24 * 60;
  const doseName = (time: string) => `${medication} ${dosage} at ${time}`;

  let james: Api;
  let thomas: Api;
  let sarahApi: Api;
  let caregiver: RegisteredUser;
  let caregiverApi: Api;
  let accessId: number | undefined;
  let prescriptionId = 0;
  let doctor: MonitoredPage | undefined;
  let patient: MonitoredPage | undefined;

  const todaysDoses = async () => {
    const all = await thomas.ok(thomas.get<any[]>(withQuery('/api/medication-schedules/me', { date: today })), 'today\'s doses');
    return all.filter(d => d.prescriptionId === prescriptionId).sort((a, b) => a.scheduledTime.localeCompare(b.scheduledTime));
  };

  test.beforeAll(async () => {
    // loginAll also fills in SARAH.systemId (not part of the demo account constants), which the dose marks are checked against.
    ({ james, thomas, sarah: sarahApi } = await loginAll());
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (thomas) await revokeThomasAccess(thomas, accessId).catch(() => {});
    // Cancel the prescription (DELETE sets CANCELLED), so later specs see Thomas's seed active prescriptions.
    if (james && prescriptionId) {
      const r = await james.del(`/api/prescriptions/${prescriptionId}`).catch(() => undefined);
      if (r && r.status !== 204) console.warn(`flow-prescription-medication: cancelling prescription ${prescriptionId} answered ${r.status}`);
    }
    await Promise.all([james?.dispose(), thomas?.dispose(), sarahApi?.dispose(), caregiverApi?.dispose()]);
  });

  test('the doctor issues a prescription with dose times', async ({ browser }) => {
    test.skip(tooLate, 'the dose times would fall after midnight clinic time');
    doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;

    await openAt(page, 'doctor', 'prescriptions', 'Prescriptions');
    await page.getByRole('button', { name: 'New prescription', exact: true }).first().click();
    const panel = dialog(page, 'New prescription');
    await expect(panel).toBeVisible();
    await panel.getByLabel('Patient', { exact: true }).selectOption(THOMAS.systemId);
    await panel.getByLabel('Medication name', { exact: true }).fill(medication);
    await panel.getByLabel('Dosage', { exact: true }).fill(dosage);
    await panel.getByLabel('Frequency', { exact: true }).fill('Twice daily');
    await panel.getByLabel('Instructions', { exact: true }).fill(instructions);
    for (const time of [t1, t2]) {
      const field = panel.getByLabel(/^Time to add/);
      await field.fill(time);
      // Tab would only move between the hour and minute segments of the native time input, so use the button.
      await panel.getByRole('button', { name: 'Add time', exact: true }).click();
      await expect(panel.getByRole('button', { name: `Remove ${time}`, exact: true })).toBeVisible();
    }

    const issued = apiResponse(page, 'POST', /^\/api\/prescriptions$/);
    await panel.getByRole('button', { name: 'Issue prescription', exact: true }).click();
    const res = await issued;
    expect(res.status(), 'prescription issued').toBe(201);
    const body = await res.json();
    prescriptionId = body.prescriptionId;
    expect(body.status).toBe('ACTIVE');
    expect(body.medicationName).toBe(medication);
    await expect(panel).toHaveCount(0);

    const plan = await james.ok(james.get(`/api/prescriptions/${prescriptionId}/schedule`), 'dose times');
    expect(plan.times).toEqual([t1, t2]);
    monitor.expectClean('issue prescription');
  });

  test('the patient sees today\'s doses and marks one taken', async ({ browser }) => {
    test.skip(tooLate || !prescriptionId, 'needs the prescription of the previous test');
    await expect.poll(async () => (await todaysDoses()).map(d => `${d.scheduledTime} ${d.status}`),
      { message: 'doses created for today', timeout: 20_000 }).toEqual([`${t1} PENDING`, `${t2} PENDING`]);

    patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;
    await openAt(page, 'patient', 'prescriptions', 'Prescriptions');
    await expect(page.getByRole('heading', { name: 'Today\'s schedule', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `Mark taken, ${doseName(t2)}`, exact: true })).toBeVisible();

    /** Presses a dose control and waits for the server to accept it. */
    const press = async (action: 'Mark taken' | 'Mark missed' | 'Undo', time: string) => {
      const path = { 'Mark taken': 'taken', 'Mark missed': 'missed', 'Undo': 'undo' }[action];
      const done = apiResponse(page, 'PATCH', new RegExp(`^/api/medication-schedules/\\d+/${path}$`));
      await page.getByRole('button', { name: `${action}, ${doseName(time)}`, exact: true }).click();
      expect((await done).status(), `${action} ${time}`).toBe(200);
    };

    await test.step('mark the first dose taken, undo it and mark it again', async () => {
      await press('Mark taken', t1);
      await expect(page.getByRole('button', { name: `Undo, ${doseName(t1)}`, exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: `Mark taken, ${doseName(t1)}`, exact: true })).toHaveCount(0);
      await press('Undo', t1);
      await expect(page.getByRole('button', { name: `Mark taken, ${doseName(t1)}`, exact: true })).toBeVisible();
      await press('Mark taken', t1);
      await expect(page.getByRole('button', { name: `Undo, ${doseName(t1)}`, exact: true })).toBeVisible();
    });

    await test.step('mark the second dose missed, then put it back', async () => {
      await press('Mark missed', t2);
      expect((await todaysDoses()).map(d => d.status)).toEqual(['TAKEN', 'MISSED']);
      await press('Undo', t2);
      await expect(page.getByRole('button', { name: `Mark taken, ${doseName(t2)}`, exact: true })).toBeVisible();
    });

    const doses = await todaysDoses();
    expect(doses.map(d => `${d.scheduledTime} ${d.status}`)).toEqual([`${t1} TAKEN`, `${t2} PENDING`]);
    expect(doses[0].markedBySystemId).toBe(THOMAS.systemId);
    expect(doses[0].takenAt, 'taken time recorded').not.toBeNull();
    monitor.expectClean('patient marks doses');
  });

  test('a caregiver who may manage the care plan can mark doses', async ({ browser }) => {
    test.skip(tooLate || !prescriptionId, 'needs the prescription of the first test');
    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    // The schedule link of a notification: one loved one, one day.
    await openAt(page, 'proxy', 'medications', 'Medications', { patient: THOMAS.systemId, tab: 'schedule', date: today });
    await expect(page.getByRole('heading', { name: 'Thomas Dube: today\'s schedule', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `Undo, ${doseName(t1)}, Thomas Dube`, exact: true })).toBeVisible();

    /** Presses one of Sarah's dose controls and waits for the server to accept it. */
    const press = async (action: 'Mark taken' | 'Undo', time: string) => {
      const path = action === 'Mark taken' ? 'taken' : 'undo';
      const done = apiResponse(page, 'PATCH', new RegExp(`^/api/medication-schedules/\\d+/${path}$`));
      await page.getByRole('button', { name: `${action}, ${doseName(time)}, Thomas Dube`, exact: true }).click();
      expect((await done).status(), `Sarah: ${action} ${time}`).toBe(200);
    };

    await test.step('Sarah marks the second dose taken and the mark is stored as hers', async () => {
      await press('Mark taken', t2);
      await expect(page.getByRole('button', { name: `Undo, ${doseName(t2)}, Thomas Dube`, exact: true })).toBeVisible();
      const [, second] = await todaysDoses();
      expect(second.scheduledTime).toBe(t2);
      expect(second.status, 'the dose Sarah marked').toBe('TAKEN');
      expect(second.markedBySystemId, 'the mark records the caregiver').toBe(SARAH.systemId);
      expect(second.takenAt, 'taken time recorded').not.toBeNull();
    });

    await test.step('Sarah undoes it, so the dose is due again for the next tests', async () => {
      await press('Undo', t2);
      await expect(page.getByRole('button', { name: `Mark taken, ${doseName(t2)}, Thomas Dube`, exact: true })).toBeVisible();
      const doses = await todaysDoses();
      expect(doses.map(d => `${d.scheduledTime} ${d.status}`)).toEqual([`${t1} TAKEN`, `${t2} PENDING`]);
      expect(doses[1].markedBySystemId ?? null).toBeNull();
    });
    monitor.expectClean('caregiver with care-plan permission');
  });

  test('a view-only caregiver sees the schedule but cannot mark doses', async ({ browser }) => {
    test.skip(tooLate || !prescriptionId, 'needs the prescription of the first test');
    caregiver = await throwawayCaregiver();
    accessId = await grantThomasAccess(thomas, caregiver, { accessLevel: 'VIEW_ONLY', flags: ['canViewMedications'] });
    caregiverApi = await apiLogin(caregiver);

    await test.step('the server refuses the caregiver\'s update', async () => {
      const [, pending] = await todaysDoses();
      const visible = await caregiverApi.ok(caregiverApi.get<any[]>(
        withQuery(`/api/medication-schedules/patient/${THOMAS.systemId}`, { date: today })), 'caregiver reads the doses');
      expect(visible.some(d => d.scheduleId === pending.scheduleId), 'the caregiver can read the dose').toBe(true);
      const refused = await caregiverApi.patch(`/api/medication-schedules/${pending.scheduleId}/taken`);
      expect(refused.status, 'a view-only caregiver marks a dose').toBe(403);
    });

    await test.step('the web shows the schedule without controls', async () => {
      const carer = await signedInPage(browser, caregiver);
      pages.push(carer);
      carer.monitor.allow({ url: /^\/api\//, status: 403, why: 'the caregiver grant is view-only and covers medications only, on purpose' });
      const { page } = carer;
      await openAt(page, 'proxy', 'medications', 'Medications', { patient: THOMAS.systemId, tab: 'schedule', date: today });
      await expect(page.getByRole('heading', { name: 'Thomas Dube: today\'s schedule', exact: true })).toBeVisible();
      await expect(page.getByText(medication, { exact: true }).first()).toBeVisible();
      await expect(page.getByText(
        'You can see this schedule but not mark doses. Marking needs permission to manage the care plan of Thomas Dube.',
        { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: new RegExp(`^(Mark taken|Mark missed|Undo), ${medication}`) })).toHaveCount(0);
      carer.monitor.expectClean('caregiver schedule');
    });
  });

  test('the doctor changes the dose times and the patient\'s doses follow', async ({ browser }) => {
    test.skip(tooLate || !prescriptionId, 'needs the prescription of the first test');
    if (!doctor) { doctor = await signedInPage(browser, JAMES); pages.push(doctor); }
    const { page, monitor } = doctor;

    await openAt(page, 'doctor', 'prescriptions', 'Prescriptions', { id: prescriptionId });
    const opened = page.getByRole('region', { name: 'Opened prescription' });
    await opened.getByRole('button', { name: `Edit schedule times, ${medication} for Thomas Dube`, exact: true }).click();
    const edit = dialog(page, 'Edit schedule times');
    await edit.getByRole('button', { name: `Remove ${t2}`, exact: true }).click();
    const field = edit.getByLabel(/^Time to add/);
    await field.fill(t3);
    await edit.getByRole('button', { name: 'Add time', exact: true }).click();
    await expect(edit.getByRole('button', { name: `Remove ${t3}`, exact: true })).toBeVisible();
    const saved = apiResponse(page, 'PUT', new RegExp(`^/api/prescriptions/${prescriptionId}/schedule$`));
    await edit.getByRole('button', { name: 'Save times', exact: true }).click();
    expect((await saved).status(), 'dose times saved').toBe(200);
    await expect(edit).toHaveCount(0);
    monitor.expectClean('edit dose times');

    const plan = await thomas.ok(thomas.get(`/api/prescriptions/${prescriptionId}/schedule`), 'plan as the patient reads it');
    expect(plan.times).toEqual([t1, t3]);
    await expect.poll(async () => (await todaysDoses()).map(d => `${d.scheduledTime} ${d.status}`),
      { message: 'the taken dose stays, the future dose moves', timeout: 15_000 }).toEqual([`${t1} TAKEN`, `${t3} PENDING`]);

    if (!patient) { patient = await signedInPage(browser, THOMAS); pages.push(patient); }
    await openAt(patient.page, 'patient', 'prescriptions', 'Prescriptions');
    await expect(patient.page.getByRole('button', { name: `Mark taken, ${doseName(t3)}`, exact: true })).toBeVisible();
    await expect(patient.page.getByRole('button', { name: new RegExp(`, ${doseName(t2)}$`) })).toHaveCount(0);
    await expect(patient.page.getByRole('button', { name: `Undo, ${doseName(t1)}`, exact: true })).toBeVisible();
  });

  test('the patient adds, pauses and deletes a manual reminder', async ({ browser }) => {
    if (!patient) { patient = await signedInPage(browser, THOMAS); pages.push(patient); }
    const { page, monitor } = patient;
    const title = `${tag} evening tablet`;
    let reminderId = 0;

    await test.step('add a daily medication reminder', async () => {
      await openAt(page, 'patient', 'prescriptions', 'Prescriptions');
      await page.getByRole('tab', { name: 'Reminders', exact: true }).click();
      await page.waitForURL(u => u.searchParams.get('tab') === 'reminders');
      await page.getByRole('button', { name: 'Add reminder', exact: true }).first().click();
      const form = dialog(page, 'Add reminder');
      await form.getByLabel('Reminder type').selectOption('MEDICATION');
      await form.getByLabel('Title', { exact: true }).fill(title);
      await form.getByLabel('Message', { exact: true }).fill(`${tag} take the evening tablet with water.`);
      await form.getByLabel(/^Time \(clinic time/).fill('21:00');
      await expect(form.getByLabel('Repeats')).toHaveValue('DAILY');
      const created = apiResponse(page, 'POST', /^\/api\/reminders$/);
      await form.getByRole('button', { name: 'Add reminder', exact: true }).click();
      const res = await created;
      expect(res.status(), 'reminder created').toBe(201);
      const body = await res.json();
      reminderId = body.reminderId;
      expect(body.sourceType).toBe('MANUAL');
      expect(body.status).toBe('ACTIVE');
      await expect(form).toHaveCount(0);
      await expect(page.getByText(`"${title}" was added.`, { exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    });

    await test.step('pause it', async () => {
      const paused = apiResponse(page, 'PATCH', new RegExp(`^/api/reminders/${reminderId}$`));
      await page.getByRole('button', { name: `Pause reminder ${title}`, exact: true }).click();
      expect((await paused).status(), 'reminder paused').toBe(200);
      await expect(page.getByRole('button', { name: `Resume reminder ${title}`, exact: true })).toBeVisible();
      const stored = (await firstPage(thomas, `/api/reminders/patient/${THOMAS.systemId}`, {}, 100))
        .find(r => r.reminderId === reminderId);
      expect(stored?.status).toBe('PAUSED');
      expect(stored?.isActive).toBe(false);
      expect(stored?.nextDueAt ?? null).toBeNull();
    });

    await test.step('delete it', async () => {
      // The confirmation is a browser dialog, accepted by the page's dialog handler.
      const removed = apiResponse(page, 'DELETE', new RegExp(`^/api/reminders/${reminderId}$`));
      await page.getByRole('button', { name: `Delete reminder ${title}`, exact: true }).click();
      expect((await removed).status(), 'reminder deleted').toBe(204);
      await expect(page.getByText(`"${title}" was deleted.`, { exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: title, exact: true })).toHaveCount(0);
      const left = await firstPage(thomas, `/api/reminders/patient/${THOMAS.systemId}`, {}, 100);
      expect(left.some(r => r.reminderId === reminderId)).toBe(false);
      monitor.expectClean('reminders');
    });
  });
});
