import { expect, type Locator, type Page, test } from '@playwright/test';
import { registerAccount, type RegisteredUser } from './support/accounts';
import { Api, listAppointments, withQuery } from './support/api';
import { JAMES, runTag } from './support/env';
import { openSectionAt } from './support/ui';
import {
  bookFirstFreeSlot, cancelAppointments, closeAll, flowPage, type FlowPage, marked, uniqueSurname,
} from './flows/account-helpers';

// Caregiver access requests: a caregiver asks an existing patient for access by email; the patient approves with
// permissions of their own choosing (appointments and medications, nothing else); the caregiver can then read
// exactly that and is refused the rest; the patient sees what the caregiver looked at and the consent history.
// A second caregiver's request is declined with a reason, which that caregiver sees, and gives no access.
//
// Tables written: users, patients, caregivers, user_settings, legal_acceptances, refresh_tokens,
// appointments (one visit with Dr. Banda, cancelled afterwards), caregiver_access_requests, dependent_access (the
// grant), consent_records, notifications (request, approval, decline), outbound_messages, audit_log (the
// caregiver's reads feed the activity panel). Everything belongs to
// throwaway accounts e2e-…@carenexus.test and is removed by scripts/dev-reset-e2e-data.sh.

test.describe.configure({ mode: 'serial' });

const PATIENT_SECTION = 'My Proxies (Caregivers)';

/** Sets one permission switch to `on` (the switch is named by its label). */
async function setSwitch(scope: Locator, label: string, on: boolean): Promise<void> {
  const control = scope.getByRole('switch', { name: label, exact: true });
  await expect(control).toBeEnabled();
  if ((await control.getAttribute('aria-checked')) !== String(on)) await control.click();
  await expect(control).toHaveAttribute('aria-checked', String(on));
}

/**
 * The patient's card of a waiting request: the innermost element holding the caregiver's name (a heading) and the
 * "What they asked for" block, which every mode of the card shows.
 */
function requestCard(page: Page, caregiverName: string): Locator {
  return page.locator('div')
    .filter({ has: page.getByRole('heading', { name: caregiverName, exact: true }) })
    .filter({ hasText: 'What they asked for' })
    .last();
}

test.describe('flow: caregiver asks a patient for access', () => {
  let patient: RegisteredUser;
  let carer: RegisteredUser;
  let declined: RegisteredUser;
  let patientApi: Api;
  let carerApi: Api;
  let appointmentId: number | undefined;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    patient = await registerAccount({ role: 'patient', lastName: uniqueSurname('Shared') });
    carer = await registerAccount({ role: 'proxy', lastName: uniqueSurname('Carer') });
    declined = await registerAccount({ role: 'proxy', lastName: uniqueSurname('Declined') });
    patientApi = await Api.login(patient);
    carerApi = await Api.login(carer);
    // An appointment for the caregiver to read: a list read is audited per patient it returns, so reading an
    // empty list would leave nothing in the patient's activity feed.
    ({ appointmentId } = await bookFirstFreeSlot(patientApi, patient.systemId, JAMES.systemId, marked(runTag(), 'check-up')));
  });

  test.afterAll(async () => {
    await closeAll(pages);
    if (appointmentId !== undefined) await cancelAppointments(patientApi, [appointmentId]);
    await Promise.all([patientApi?.dispose(), carerApi?.dispose()]);
  });

  test('request, approval with chosen permissions, only granted data, activity and consent history', async ({ browser }) => {
    const tag = runTag();
    const carerName = `${carer.firstName} ${carer.lastName}`;
    const patientName = `${patient.firstName} ${patient.lastName}`;

    const c = await flowPage(browser, carer);
    pages.push(c);
    await test.step('the caregiver sends a request by the patient\'s email', async () => {
      await openSectionAt(c.page, 'proxy', 'add-dependent', { mode: 'link' }, 'Add a Loved One');
      await c.page.getByLabel('Patient\'s email address', { exact: true }).fill(patient.email);
      await c.page.getByLabel('Your relationship to them', { exact: true }).fill('Sister');
      await c.page.getByLabel('Message to the patient (optional)', { exact: true }).fill(marked(tag, 'I would like to help with your appointments.'));
      const res = c.page.waitForResponse(r => r.url().endsWith('/api/caregiver/access-requests') && r.request().method() === 'POST');
      await c.page.getByRole('button', { name: 'Send request' }).click();
      expect((await res).status()).toBe(202);
      await expect(c.page.getByRole('heading', { name: 'Request sent' })).toBeVisible();
    });

    await test.step('until it is answered the caregiver learns nothing about the patient and reads nothing', async () => {
      const mine = await carerApi.ok(carerApi.page('/api/caregiver/access-requests'), 'caregiver requests');
      const row = (mine.content as any[]).find(r => r.patientEmail === patient.email);
      expect(row?.status).toBe('PENDING');
      expect(row?.patientSystemId ?? null).toBeNull();
      expect(row?.patientName ?? null).toBeNull();
      expect((await carerApi.get(withQuery('/api/appointments', { patientSystemId: patient.systemId }))).status).toBe(403);
    });

    const p = await flowPage(browser, patient);
    pages.push(p);
    await test.step('the patient approves with their own choice of permissions', async () => {
      await openSectionAt(p.page, 'patient', 'my-proxies', {}, PATIENT_SECTION);
      const card = requestCard(p.page, carerName);
      await expect(card).toBeVisible();
      await expect(card.getByText(marked(tag, 'I would like to help with your appointments.'))).toBeVisible();
      await card.getByRole('button', { name: `Review and approve, request from ${carerName}` }).click();
      await card.getByRole('radio', { name: /^Custom\./ }).click();
      await expect(card.getByRole('radio', { name: /^Custom\./ })).toHaveAttribute('aria-checked', 'true');
      for (const [label, on] of [
        ['View appointments', true], ['View medications', true], ['View medical records', false], ['View lab results', false],
        ['View imaging results', false], ['View consultations', false], ['View billing', false],
        ['Book appointments', false],
      ] as const) {
        await setSwitch(card, label, on);
      }
      const req = p.page.waitForRequest(r => /\/api\/patients\/me\/caregiver-access\/requests\/\d+\/approve$/.test(r.url()));
      const res = p.page.waitForResponse(r => /\/api\/patients\/me\/caregiver-access\/requests\/\d+\/approve$/.test(r.url()));
      await card.getByRole('button', { name: 'Approve access' }).click();
      const body = (await req).postDataJSON();
      expect(body).toMatchObject({ accessLevel: 'CUSTOM', canViewAppointments: true, canViewMedications: true, canViewLabResults: false, canViewBilling: false });
      expect((await res).status()).toBe(200);
      await expect(requestCard(p.page, carerName)).toHaveCount(0);
      await expect(p.page.getByRole('list', { name: 'Caregivers with access' })).toContainText(carerName);
    });

    await test.step('the stored grant is exactly what the patient chose', async () => {
      const grants = await patientApi.ok(patientApi.get<any[]>('/api/patients/me/caregiver-access'), 'patient grants');
      const grant = grants.find(g => g.caregiverSystemId === carer.systemId);
      expect(grant).toMatchObject({
        accessLevel: 'CUSTOM', canViewAppointments: true, canViewMedications: true,
        canViewRecords: false, canViewLabResults: false, canViewBilling: false, canScheduleAppointments: false,
      });
    });

    await test.step('the caregiver reads appointments and medications, and is refused lab results, records and billing', async () => {
      const visible = await listAppointments(carerApi, { patientSystemId: patient.systemId });
      expect(visible.map(a => a.appointmentId), 'the caregiver sees the patient\'s appointment').toContain(appointmentId);
      expect((await carerApi.get(withQuery('/api/prescriptions', { patientSystemId: patient.systemId }))).status, 'medications').toBe(200);
      for (const path of ['/api/lab-results', '/api/medical-records', '/api/imaging-results']) {
        expect((await carerApi.get(withQuery(path, { patientSystemId: patient.systemId }))).status, path).toBe(403);
      }
      expect((await carerApi.get(withQuery('/api/billing/invoices', { patientSystemId: patient.systemId }))).status, 'billing').toBe(403);

      await openSectionAt(c.page, 'proxy', 'patient-profile', { patient: patient.systemId }, 'Patient Profile');
      const shortcuts = c.page.getByRole('list', { name: `Shortcuts for ${patientName}` });
      await expect(shortcuts.getByRole('button', { name: `View appointments, ${patientName}` })).toBeVisible();
      await expect(shortcuts.getByRole('button', { name: `View medications, ${patientName}` })).toBeVisible();
      for (const hidden of ['Lab results', 'Medical records', 'Billing']) {
        await expect(shortcuts.getByRole('button', { name: `${hidden}, ${patientName}` }), hidden).toHaveCount(0);
      }
      await expect(c.page.getByRole('button', { name: `Book appointment for ${patientName}` })).toHaveCount(0);
    });

    await test.step('the patient sees what the caregiver looked at and the consent history', async () => {
      const grants = await patientApi.ok(patientApi.get<any[]>('/api/patients/me/caregiver-access'), 'patient grants');
      const accessId = grants.find(g => g.caregiverSystemId === carer.systemId).accessId as number;
      await expect.poll(async () => {
        const feed = await patientApi.ok(patientApi.page(`/api/patients/me/caregiver-access/${accessId}/activity`), 'activity');
        return (feed.content as { action: string; resourceType: string | null }[]).some(e => e.action === 'READ' && e.resourceType === 'APPOINTMENT');
      }, { message: 'the caregiver\'s appointment read is in the activity feed' }).toBe(true);

      await openSectionAt(p.page, 'patient', 'my-proxies', {}, PATIENT_SECTION);
      await p.page.getByRole('button', { name: `Activity, ${carerName}` }).click();
      const activity = p.page.getByRole('list', { name: `Activity of ${carerName}` });
      await expect(activity.getByText('Viewed appointments').first()).toBeVisible();
      await p.page.getByRole('button', { name: `Consent history, ${carerName}` }).click();
      await expect(p.page.getByRole('list', { name: `Consent history for ${carerName}` }).getByText('Access granted').first()).toBeVisible();

      const consents = await patientApi.ok(patientApi.page('/api/patients/me/consents'), 'consent ledger');
      expect((consents.content as { eventKind: string; caregiverSystemId?: string }[]).some(r => r.eventKind === 'GRANTED')).toBe(true);
    });
  });

  test('a declined request gives no access and the caregiver sees the reason', async ({ browser }) => {
    const reason = `${runTag()} I already have someone helping me.`;
    const name = `${declined.firstName} ${declined.lastName}`;
    const declinedApi = await Api.login(declined);
    try {
      const sent = await declinedApi.post('/api/caregiver/access-requests', {
        patientEmail: patient.email, relationship: 'Neighbour', accessLevel: 'FULL_ACCESS',
      });
      expect(sent.status).toBe(202);

      const p = await flowPage(browser, patient);
      pages.push(p);
      await openSectionAt(p.page, 'patient', 'my-proxies', {}, PATIENT_SECTION);
      const card = requestCard(p.page, name);
      await card.getByRole('button', { name: `Decline, request from ${name}` }).click();
      await card.getByLabel('Reason (optional)', { exact: true }).fill(reason);
      const res = p.page.waitForResponse(r => /\/api\/patients\/me\/caregiver-access\/requests\/\d+\/decline$/.test(r.url()));
      await card.getByRole('button', { name: 'Decline request' }).click();
      expect((await res).status()).toBe(200);
      await expect(requestCard(p.page, name)).toHaveCount(0);
      await expect(p.page.getByRole('list', { name: 'Caregivers with access' })).not.toContainText(name);

      const mine = await declinedApi.ok(declinedApi.page('/api/caregiver/access-requests'), 'declined caregiver requests');
      const row = (mine.content as any[]).find(r => r.patientEmail === patient.email);
      expect(row).toMatchObject({ status: 'DECLINED', declineReason: reason, patientSystemId: null, accessId: null });
      expect((await declinedApi.get(withQuery('/api/appointments', { patientSystemId: patient.systemId }))).status).toBe(403);

      const d = await flowPage(browser, declined);
      pages.push(d);
      await openSectionAt(d.page, 'proxy', 'access-requests', {}, 'Access Requests');
      await d.page.getByRole('tab', { name: 'Declined' }).click();
      await expect(d.page.getByText(`Reason given: ${reason}`)).toBeVisible();
    } finally {
      await declinedApi.dispose();
    }
  });
});
