import { expect, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api } from './support/api';
import { JAMES, SARAH, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiLogin, apiResponse, dialog, firstPage, grantThomasAccess, openAt, outsiderDoctor, revokeThomasAccess,
  throwawayCaregiver,
} from './flows/clinical-helpers';

// Flow: vitals and symptoms.
// Thomas records a heart-rate reading and logs a mild symptom on the web. His doctor sees both in the patient
// detail (Vitals tab); a doctor without a care relationship is refused. Sarah (full access) sees them read-only in
// Health Summary; a caregiver whose grant does not include records sees the not-shared state and is refused by the
// server, for reading and for writing.
// The values are in the normal range and the symptom is mild, so no alert or caregiver notification is raised.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): health_metrics and symptom_logs (notes "e2e…"),
// disease_detection and ai_alerts (only if screening flags them; sourced from those rows), dependent_access (grant to
// the throwaway caregiver, revoked afterwards), notifications (grant change notices), outbound_messages,
// idempotency_keys, audit_log, users/doctors/caregivers/legal_acceptances (throwaway accounts e2e-…@carenexus.test).

test.describe('flow: vitals and symptoms', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const readingNote = `${tag} after breakfast`;
  const symptomNote = `${tag} mild headache since this morning`;
  const pages: MonitoredPage[] = [];
  let thomas: Api;
  let caregiver: RegisteredUser;
  let caregiverApi: Api;
  let outsiderApi: Api;
  let accessId: number | undefined;
  let metricId = 0;
  let symptomId = 0;

  test.beforeAll(async () => {
    thomas = await Api.login(THOMAS);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (thomas) await revokeThomasAccess(thomas, accessId).catch(() => {});
    await Promise.all([thomas?.dispose(), caregiverApi?.dispose(), outsiderApi?.dispose()]);
  });

  test('the patient records a reading and logs a symptom', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;
    await openAt(page, 'patient', 'health-tracking', 'Vitals & Symptoms');

    await test.step('record a heart-rate reading', async () => {
      await page.getByRole('button', { name: 'Record vitals', exact: true }).first().click();
      const form = dialog(page, 'Record vitals');
      await form.getByLabel('Heart rate (bpm)', { exact: true }).fill('72');
      await form.getByLabel('Notes', { exact: true }).fill(readingNote);
      const saved = apiResponse(page, 'POST', /^\/api\/health-metrics$/);
      await form.getByRole('button', { name: 'Save readings', exact: true }).click();
      const res = await saved;
      expect(res.status(), 'reading saved').toBe(201);
      const body = await res.json();
      expect(body.metrics).toHaveLength(1);
      metricId = body.metrics[0].metricId;
      expect(body.metrics[0]).toMatchObject({ type: 'HEART_RATE', unit: 'bpm', severity: 'NORMAL', notes: readingNote });
      await expect(form).toHaveCount(0);
      await expect(page.getByText('Reading saved.', { exact: true })).toBeVisible();
    });

    await test.step('log a mild symptom', async () => {
      await page.getByRole('button', { name: 'Log symptoms', exact: true }).first().click();
      const form = dialog(page, 'Log symptoms');
      await form.getByRole('group', { name: 'Common symptoms' }).getByRole('button', { name: 'Headache', exact: true }).click();
      await form.getByLabel('Severity', { exact: true }).selectOption('MILD');
      await form.getByLabel('Duration (days)', { exact: true }).fill('0');
      await form.getByLabel('Notes', { exact: true }).fill(symptomNote);
      const saved = apiResponse(page, 'POST', /^\/api\/symptom-logs$/);
      await form.getByRole('button', { name: 'Save symptoms', exact: true }).click();
      const res = await saved;
      expect(res.status(), 'symptoms saved').toBe(201);
      const body = await res.json();
      symptomId = body.symptomId;
      expect(body).toMatchObject({ symptoms: ['Headache'], severity: 'MILD', durationDays: 0, notes: symptomNote });
      await expect(form).toHaveCount(0);
      await expect(page.getByText('Symptoms saved.', { exact: true })).toBeVisible();
      await expect(page.getByText(symptomNote, { exact: true }).first()).toBeVisible();
    });

    await test.step('the reading is in the readings history', async () => {
      await page.getByRole('tab', { name: 'Readings history', exact: true }).click();
      await expect(page.getByText(readingNote, { exact: true }).first()).toBeVisible();
      monitor.expectClean('patient vitals and symptoms');
    });
  });

  test('the treating doctor sees them; an unrelated doctor is refused', async ({ browser }) => {
    test.skip(!metricId || !symptomId, 'needs the reading and symptom of the previous test');
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    await openAt(page, 'doctor', 'patients', 'Patients', { patient: THOMAS.systemId, tab: 'vitals' });
    await expect(page.getByText(readingNote, { exact: true }).first()).toBeVisible();
    await expect(page.getByText(symptomNote, { exact: true }).first()).toBeVisible();
    monitor.expectClean('doctor vitals tab');

    outsiderApi = await apiLogin(await outsiderDoctor());
    const metrics = await outsiderApi.get(`/api/health-metrics/patient/${THOMAS.systemId}?page=0&size=1`);
    expect(metrics.status, 'unrelated doctor reads readings').toBe(403);
    const symptoms = await outsiderApi.get(`/api/symptom-logs/patient/${THOMAS.systemId}?page=0&size=1`);
    expect(symptoms.status, 'unrelated doctor reads symptom logs').toBe(403);
    const single = await outsiderApi.get(`/api/symptom-logs/${symptomId}`);
    expect(single.status, 'unrelated doctor reads one symptom log').toBe(403);
  });

  test('a caregiver with records access sees them read-only; one without it is refused', async ({ browser }) => {
    test.skip(!metricId || !symptomId, 'needs the reading and symptom of the first test');

    await test.step('Sarah sees the symptom in Health Summary without entry controls', async () => {
      const sarah = await signedInPage(browser, SARAH);
      pages.push(sarah);
      const { page, monitor } = sarah;
      await openAt(page, 'proxy', 'health-summary', 'Health Summary', { patient: THOMAS.systemId });
      await expect(page.getByText(symptomNote, { exact: true }).first()).toBeVisible();
      await expect(page.getByRole('button', { name: 'Record vitals', exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Log symptoms', exact: true })).toHaveCount(0);
      monitor.expectClean('caregiver health summary');
    });

    await test.step('a caregiver without records access is refused', async () => {
      caregiver = await throwawayCaregiver();
      accessId = await grantThomasAccess(thomas, caregiver, { accessLevel: 'CUSTOM', flags: ['canViewMedications', 'canViewAppointments'] });
      caregiverApi = await apiLogin(caregiver);

      const read = await caregiverApi.get(`/api/health-metrics/patient/${THOMAS.systemId}?page=0&size=1`);
      expect(read.status, 'caregiver without records reads readings').toBe(403);
      const symptoms = await caregiverApi.get(`/api/symptom-logs/patient/${THOMAS.systemId}?page=0&size=1`);
      expect(symptoms.status, 'caregiver without records reads symptom logs').toBe(403);
      const write = await caregiverApi.post('/api/health-metrics', {
        patientSystemId: THOMAS.systemId, readings: [{ type: 'HEART_RATE', value: 70, notes: `${tag} refused` }],
      });
      expect(write.status, 'caregiver without the care-plan permission records a reading').toBe(403);

      const carer = await signedInPage(browser, caregiver);
      pages.push(carer);
      carer.monitor.allow({ url: /^\/api\//, status: 403, why: 'the caregiver grant is limited on purpose; screens may probe data it does not cover' });
      await openAt(carer.page, 'proxy', 'health-summary', 'Health Summary', { patient: THOMAS.systemId });
      await expect(carer.page.getByText('Vitals and symptoms of Thomas Dube are not shared with you.', { exact: true })).toBeVisible();
      await expect(carer.page.getByText(symptomNote, { exact: true })).toHaveCount(0);
      carer.monitor.expectClean('caregiver without records');
    });

    // The patient's own data is unchanged by the refused write.
    const rows = await firstPage(thomas, `/api/health-metrics/patient/${THOMAS.systemId}`, {}, 50);
    expect(rows.some(m => m.notes === `${tag} refused`)).toBe(false);
  });
});
