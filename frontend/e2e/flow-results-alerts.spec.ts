import { expect, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api, listLabResults } from './support/api';
import { JAMES, SARAH, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiLogin, apiResponse, cardHolding, dialog, firstPage, grantThomasAccess, notificationsAbout, openAt,
  revokeThomasAccess, throwawayCaregiver,
} from './flows/clinical-helpers';

// Flow: a critical lab result reaches the doctors' alert inbox and the patient's circle.
// Dr. Banda records a critical lab result for Thomas on the web. Exactly one CRITICAL_RESULT alert is raised for
// it; the doctor opens it in Clinical Alerts and acknowledges it. Thomas and Sarah (full access) each get one
// "Important lab result" notification; a caregiver whose grant does not include lab results gets none.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): lab_results (test name "e2e…"), ai_alerts (source
// LAB_RESULT of that result), notifications (about that result, and the grant change notices), outbound_messages
// (their fan-out), dependent_access (grant to the throwaway caregiver, revoked afterwards), user_settings (Sarah's
// and Thomas's lab result alerts are switched on if they were off; that is the default), idempotency_keys,
// audit_log, users/caregivers/legal_acceptances (throwaway caregiver e2e-…@carenexus.test).

test.describe('flow: critical lab result, alerts and notifications', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const testName = `${tag} Serum potassium`;
  const pages: MonitoredPage[] = [];
  let james: Api;
  let thomas: Api;
  let sarah: Api;
  let caregiver: RegisteredUser;
  let caregiverApi: Api;
  let accessId: number | undefined;
  let resultId = 0;
  let alertId = 0;

  test.beforeAll(async () => {
    [james, thomas, sarah] = await Promise.all([Api.login(JAMES), Api.login(THOMAS), Api.login(SARAH)]);
    // Lab result alerts are on by default; another spec may have switched them off for these two accounts.
    for (const api of [thomas, sarah]) {
      await api.ok(api.patch('/api/profile/settings', { labResultAlerts: true }), 'lab result alerts on');
    }
    caregiver = await throwawayCaregiver();
    // Records and medications, but not lab results.
    accessId = await grantThomasAccess(thomas, caregiver, { accessLevel: 'CUSTOM', flags: ['canViewRecords', 'canViewMedications'] });
    caregiverApi = await apiLogin(caregiver);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (thomas) await revokeThomasAccess(thomas, accessId).catch(() => {});
    await Promise.all([james?.dispose(), thomas?.dispose(), sarah?.dispose(), caregiverApi?.dispose()]);
  });

  test('the doctor records a critical lab result', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;

    await openAt(page, 'doctor', 'labs', 'Labs & Results');
    await page.getByRole('button', { name: 'Record result', exact: true }).first().click();
    const form = dialog(page, 'Record lab result');
    await expect(form).toBeVisible();
    await form.getByLabel('Patient', { exact: true }).selectOption(THOMAS.systemId);
    await form.getByLabel('Test name', { exact: true }).fill(testName);
    await form.getByLabel('Test type', { exact: true }).selectOption('BLOOD');
    await form.getByLabel('Status', { exact: true }).selectOption('CRITICAL');
    await form.getByLabel('Value', { exact: true }).fill('6.9');
    await form.getByLabel('Unit', { exact: true }).fill('mmol/L');
    await form.getByLabel('Reference range', { exact: true }).fill('3.5-5.1');

    const created = apiResponse(page, 'POST', /^\/api\/lab-results$/);
    await form.getByRole('button', { name: 'Record result', exact: true }).click();
    const res = await created;
    expect(res.status(), 'lab result recorded').toBe(201);
    const body = await res.json();
    resultId = body.resultId;
    expect(body.status).toBe('CRITICAL');
    expect(body.isCritical).toBe(true);
    await expect(form).toHaveCount(0);
    await expect(page.getByRole('heading', { name: testName, exact: true }).first()).toBeVisible();
    monitor.expectClean('record lab result');

    const listed = await listLabResults(thomas, { patientSystemId: THOMAS.systemId, critical: true });
    expect(listed.some(r => r.resultId === resultId), 'the patient can read the critical result').toBe(true);
  });

  test('the doctor alert inbox shows one alert for the result and the doctor acknowledges it', async ({ browser }) => {
    test.skip(!resultId, 'needs the result of the previous test');
    const alertsForResult = async () => (await firstPage(james, '/api/ai-alerts',
      { patientSystemId: THOMAS.systemId, type: 'CRITICAL_RESULT' }, 100))
      .filter(a => a.sourceType === 'LAB_RESULT' && a.sourceId === resultId);

    await expect.poll(async () => (await alertsForResult()).length,
      { message: 'one CRITICAL_RESULT alert for the result', timeout: 20_000 }).toBe(1);
    const [alert] = await alertsForResult();
    alertId = alert.alertId;
    expect(alert.priority).toBe('CRITICAL');
    expect(alert.acknowledged).toBe(false);
    expect(alert.canAcknowledge).toBe(true);

    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    await openAt(page, 'doctor', 'ai-alerts', 'Clinical Alerts', { patient: THOMAS.systemId, id: alertId });
    // The highlighted card: the only one that says it was opened from a notification, together with its title.
    const highlighted = () => cardHolding(page,
      page.getByText('Opened from your notification', { exact: true }), page.getByText(alert.title, { exact: true }));
    const card = highlighted();
    await expect(card).toHaveCount(1);
    await expect(card.getByText('Unacknowledged', { exact: true }).or(card.getByText('Action required', { exact: true })))
      .toBeVisible();
    const acked = apiResponse(page, 'POST', new RegExp(`^/api/ai-alerts/${alertId}/acknowledge$`));
    await card.getByRole('button', { name: `Acknowledge ${alert.title} for Thomas Dube`, exact: true }).click();
    expect((await acked).status(), 'alert acknowledged').toBe(204);
    monitor.expectClean('acknowledge alert');

    const [after] = await alertsForResult();
    expect(after.acknowledged).toBe(true);
    expect(after.acknowledgedBySystemId).toBe(JAMES.systemId);
    expect(after.canAcknowledge).toBe(false);
    expect(await alertsForResult(), 'still exactly one alert for the result').toHaveLength(1);

    await openAt(page, 'doctor', 'ai-alerts', 'Clinical Alerts', { patient: THOMAS.systemId, id: alertId, status: 'acknowledged' });
    const done = highlighted();
    await expect(done).toHaveCount(1);
    // The status badge inside that card (the screen's "Acknowledged" filter pill is outside it).
    await expect(done.getByText('Acknowledged', { exact: true }).first()).toBeVisible();
    await expect(done.getByRole('button', { name: `Acknowledge ${alert.title} for Thomas Dube`, exact: true })).toHaveCount(0);
  });

  test('the patient and a caregiver with lab access are notified, a caregiver without it is not', async ({ browser }) => {
    test.skip(!resultId, 'needs the result of the first test');

    await test.step('Thomas gets one important-result notification', async () => {
      await expect.poll(async () => (await notificationsAbout(thomas, 'LAB_RESULT', resultId)).map(n => `${n.title} ${n.priority}`),
        { message: 'patient notification', timeout: 20_000 }).toEqual(['Important lab result HIGH']);
    });

    await test.step('Sarah (full access) gets one, naming Thomas', async () => {
      await expect.poll(async () => (await notificationsAbout(sarah, 'LAB_RESULT', resultId)).map(n => n.message),
        { message: 'caregiver notification', timeout: 20_000 })
        .toEqual(['A lab result for Thomas Dube needs attention. Please contact the care team.']);
    });

    await test.step('the caregiver without lab results gets none and cannot read the result', async () => {
      expect(await notificationsAbout(caregiverApi, 'LAB_RESULT', resultId)).toEqual([]);
      const read = await caregiverApi.get(`/api/lab-results/${resultId}`);
      expect(read.status, 'caregiver without lab results reads the result').toBe(403);
    });

    await test.step('the patient sees the notification on the web', async () => {
      const patient = await signedInPage(browser, THOMAS);
      pages.push(patient);
      await openAt(patient.page, 'patient', 'notifications', 'Notifications');
      await expect(patient.page.getByText('Important lab result', { exact: true }).first()).toBeVisible();
      patient.monitor.expectClean('patient notifications');
    });
  });
});
