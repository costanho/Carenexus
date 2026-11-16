import { type Browser, expect, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api } from './support/api';
import { JAMES, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiLogin, apiResponse, cardHolding, cardOf, clinicalDoctor, dialog, firstPage, openAt,
} from './flows/clinical-helpers';

// Flow: referrals.
// 1. Dr. Banda refers Thomas to a platform specialist on the web; the specialist, who cannot open Thomas's record
//    while the referral is pending, accepts it on the web, gains that access, and Thomas sees the referral as
//    Accepted.
// 2. A referral created without a specialist is assigned to the platform specialist later.
// 3. A referral to a specialist outside CareNexus gets its outcome (accepted, then completed) recorded by the
//    referring doctor on the specialist's behalf.
// The specialist is an approved throwaway doctor.
//
// Every free text starts with the run's e2e marker: reasons, the clinical summary, outcome notes and the external
// specialist's name and organisation.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): referrals (reason "e2e…"), care_team (the SPECIALIST row that
// acceptance inserts), notifications (REFERRAL notices), outbound_messages (their fan-out), idempotency_keys,
// audit_log, users/doctors/legal_acceptances (throwaway specialist e2e-…@carenexus.test).

test.describe('flow: referrals', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const pages: MonitoredPage[] = [];
  let james: Api;
  let thomas: Api;
  let specialist: RegisteredUser;
  let specialistApi: Api;
  let doctorPage: MonitoredPage | undefined;

  const openSent = async (browser: Browser) => {
    if (!doctorPage) { doctorPage = await signedInPage(browser, JAMES); pages.push(doctorPage); }
    await openAt(doctorPage.page, 'doctor', 'referrals', 'Referrals', { tab: 'sent' });
    return doctorPage;
  };

  test.beforeAll(async () => {
    [james, thomas] = await Promise.all([Api.login(JAMES), Api.login(THOMAS)]);
    specialist = await clinicalDoctor();
    specialistApi = await apiLogin(specialist);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    await Promise.all([james?.dispose(), thomas?.dispose(), specialistApi?.dispose()]);
  });

  test('the doctor refers, the specialist accepts and the patient sees it accepted', async ({ browser }) => {
    const reason = `${tag} recurrent palpitations, please assess`;
    let referralId = 0;

    await test.step('Dr. Banda creates the referral on the web', async () => {
      const { page, monitor } = await openSent(browser);
      await page.getByRole('button', { name: 'New referral', exact: true }).first().click();
      const form = dialog(page, 'New referral');
      await form.getByLabel('Patient', { exact: true }).selectOption(THOMAS.systemId);
      await form.getByLabel('Reason for referral', { exact: true }).fill(reason);
      await form.getByLabel('Clinical summary', { exact: true }).fill(`${tag} Holter monitor requested. No syncope.`);
      await form.getByLabel('Refer to', { exact: true }).selectOption('PLATFORM');
      await form.getByLabel('Specialist', { exact: true }).selectOption(specialist.systemId);
      const created = apiResponse(page, 'POST', /^\/api\/referrals$/);
      await form.getByRole('button', { name: 'Create referral', exact: true }).click();
      const res = await created;
      expect(res.status(), 'referral created').toBe(201);
      const body = await res.json();
      referralId = body.referralId;
      expect(body.status).toBe('PENDING');
      expect(body.specialistType).toBe('PLATFORM');
      expect(body.specialistSystemId).toBe(specialist.systemId);
      await expect(form).toHaveCount(0);
      monitor.expectClean('create referral');
    });

    await test.step('before accepting, the specialist cannot open Thomas\'s record', async () => {
      // A pending referral gives no access: only acceptance (the SPECIALIST care-team row) does.
      const plans = await specialistApi.get(`/api/care-plans/patient/${THOMAS.systemId}?page=0&size=1`);
      expect(plans.status, 'the specialist reads Thomas\'s care plans before accepting').toBe(403);
    });

    await test.step('the specialist accepts it on the web', async () => {
      const incoming = await firstPage(specialistApi, '/api/referrals/doctor/incoming', {}, 50);
      const mine = incoming.find(r => r.referralId === referralId);
      expect(mine?.allowedActions, 'the specialist may respond').toEqual(expect.arrayContaining(['ACCEPT', 'DECLINE']));

      const spec = await signedInPage(browser, specialist);
      pages.push(spec);
      const { page, monitor } = spec;
      await openAt(page, 'doctor', 'referrals', 'Referrals', { tab: 'received' });
      const accept = page.getByRole('button', { name: 'Accept the referral for Thomas Dube', exact: true });
      await cardOf(page, reason, accept).getByRole('button', { name: 'Accept the referral for Thomas Dube', exact: true }).click();
      const confirm = dialog(page, 'Accept referral');
      await expect(confirm.getByText('Accepting adds you to Thomas Dube\'s care team, so you can open their records.')).toBeVisible();
      const accepted = apiResponse(page, 'PATCH', new RegExp(`^/api/referrals/${referralId}/accept$`));
      await confirm.getByRole('button', { name: 'Accept referral', exact: true }).click();
      expect((await accepted).status(), 'referral accepted').toBe(200);
      await expect(confirm).toHaveCount(0);
      monitor.expectClean('specialist accepts');
    });

    await test.step('the referral is accepted and the specialist may now open Thomas\'s record', async () => {
      const r = await thomas.ok(thomas.get(`/api/referrals/${referralId}`), 'patient reads the referral');
      expect(r.status).toBe('ACCEPTED');
      expect(r.outcomeRecordedBySystemId, 'the named specialist acted').toBeNull();
      expect(r.allowedActions, 'patients never get actions').toEqual([]);
      const plans = await specialistApi.get(`/api/care-plans/patient/${THOMAS.systemId}?page=0&size=1`);
      expect(plans.status, 'the specialist reads Thomas\'s care plans after accepting').toBe(200);
    });

    await test.step('Thomas sees the referral as accepted', async () => {
      const patient = await signedInPage(browser, THOMAS);
      pages.push(patient);
      await openAt(patient.page, 'patient', 'referrals', 'Referrals', { id: referralId });
      // The card of this referral: its own reason and its own details button (open, because the link points at it).
      const card = cardHolding(patient.page, patient.page.getByText(reason, { exact: true }),
        patient.page.getByRole('button', { name: /^(Show|Hide) details of the referral to / }));
      await expect(card).toHaveCount(1);
      await expect(card.getByText('Accepted', { exact: true }).first()).toBeVisible();
      await expect(card.getByText('Specialist care in progress', { exact: true })).toBeVisible();
      patient.monitor.expectClean('patient referrals');
    });
  });

  test('a referral without a specialist is assigned later', async ({ browser }) => {
    const reason = `${tag} unassigned, orthopaedic opinion on knee pain`;
    const created = await james.ok(james.postIdempotent('/api/referrals', {
      patientSystemId: THOMAS.systemId, reason, priority: 'ROUTINE',
    }), 'create an unassigned referral');
    expect(created.specialistType).toBe('UNASSIGNED');
    expect(created.allowedActions).toEqual(expect.arrayContaining(['ASSIGN_SPECIALIST']));

    const { page, monitor } = await openSent(browser);
    const assign = page.getByRole('button', { name: 'Assign specialist for the referral for Thomas Dube', exact: true });
    await cardOf(page, reason, assign).getByRole('button', { name: 'Assign specialist for the referral for Thomas Dube', exact: true }).click();
    const form = dialog(page, 'Assign specialist');
    await form.getByLabel('Refer to', { exact: true }).selectOption('PLATFORM');
    await form.getByLabel('Specialist', { exact: true }).selectOption(specialist.systemId);
    const saved = apiResponse(page, 'PATCH', new RegExp(`^/api/referrals/${created.referralId}/specialist$`));
    await form.getByRole('button', { name: 'Assign specialist', exact: true }).click();
    expect((await saved).status(), 'specialist assigned').toBe(200);
    await expect(form).toHaveCount(0);
    monitor.expectClean('assign specialist');

    const after = await james.ok(james.get(`/api/referrals/${created.referralId}`), 'read the referral');
    expect(after.status).toBe('PENDING');
    expect(after.specialistType).toBe('PLATFORM');
    expect(after.specialistSystemId).toBe(specialist.systemId);
    const incoming = await firstPage(specialistApi, '/api/referrals/doctor/incoming', {}, 50);
    expect(incoming.find(r => r.referralId === created.referralId)?.allowedActions, 'the new specialist may respond')
      .toEqual(expect.arrayContaining(['ACCEPT']));
  });

  test('the referring doctor records the outcome of an external referral', async ({ browser }) => {
    const reason = `${tag} external, cardiac MRI review`;
    const acceptNote = `${tag} appointment booked at the heart centre for next week.`;
    const completeNote = `${tag} MRI normal, discharged back to the GP.`;
    const created = await james.ok(james.postIdempotent('/api/referrals', {
      patientSystemId: THOMAS.systemId, reason, priority: 'URGENT',
      externalSpecialistName: `${tag} Dr. Peter Moyo`, externalSpecialistOrganisation: `${tag} Harare Heart Centre`,
    }), 'create an external referral');
    expect(created.specialistType).toBe('EXTERNAL');
    expect(created.outcomeNoteRequired, 'the referring doctor records on the specialist\'s behalf').toBe(true);

    const { page, monitor } = await openSent(browser);
    const record = page.getByRole('button', { name: 'Record outcome of the referral for Thomas Dube', exact: true });

    await test.step('accepted by the specialist', async () => {
      await cardOf(page, reason, record).getByRole('button', { name: 'Record outcome of the referral for Thomas Dube', exact: true }).click();
      const form = dialog(page, 'Record outcome');
      await expect(form.getByLabel('Outcome', { exact: true })).toHaveValue('ACCEPT');
      await form.getByLabel('Outcome note', { exact: true }).fill(acceptNote);
      const accepted = apiResponse(page, 'PATCH', new RegExp(`^/api/referrals/${created.referralId}/accept$`));
      await form.getByRole('button', { name: 'Record outcome', exact: true }).click();
      expect((await accepted).status(), 'outcome recorded').toBe(200);
      await expect(form).toHaveCount(0);
    });

    await test.step('then completed', async () => {
      await cardOf(page, reason, record).getByRole('button', { name: 'Record outcome of the referral for Thomas Dube', exact: true }).click();
      const form = dialog(page, 'Record outcome');
      await expect(form.getByText('Specialist care completed', { exact: true })).toBeVisible();
      await form.getByLabel('Outcome note', { exact: true }).fill(completeNote);
      const completed = apiResponse(page, 'PATCH', new RegExp(`^/api/referrals/${created.referralId}/complete$`));
      await form.getByRole('button', { name: 'Record outcome', exact: true }).click();
      expect((await completed).status(), 'completion recorded').toBe(200);
      await expect(form).toHaveCount(0);
      monitor.expectClean('record outcome');
    });

    const after = await thomas.ok(thomas.get(`/api/referrals/${created.referralId}`), 'patient reads the referral');
    expect(after.status).toBe('COMPLETED');
    expect(after.outcomeNote).toBe(completeNote);
    expect(after.outcomeRecordedBySystemId).toBe(JAMES.systemId);
    expect(after.specialistType).toBe('EXTERNAL');
  });
});
