import { expect, type Page, test } from '@playwright/test';
import { loginAdmin, registerApprovedDoctor, registerAccount, type RegisteredUser } from './support/accounts';
import { Api } from './support/api';
import { ADMIN, JAMES, runTag } from './support/env';
import { escapeRe, openSectionAt } from './support/ui';
import {
  bookFirstFreeSlot, cancelAppointments, closeAll, deactivateAccount, flowPage, type FlowPage, marked, uniqueSurname,
  waitForNotifications,
} from './flows/account-helpers';

// Record corrections: a throwaway patient asks for a correction to a record Dr. Banda wrote, from the record card
// in Medical Records; Dr. Banda accepts it with a note in his correction queue and the patient is told. A record
// whose author has since left (a throwaway doctor the administrator deactivated) gets a request with no reviewer,
// which appears under "Only unassigned" in the administrator's Correction requests and is assigned to Dr. Banda.
//
// Tables written: users, patients, doctors, user_settings, legal_acceptances, refresh_tokens, appointments (one
// with each doctor; the deactivation cancels the second), medical_records (two, titles start with the run tag),
// record_correction_requests, notifications, outbound_messages, idempotency_keys, audit_log. Everything belongs to
// throwaway accounts e2e-…@carenexus.test and is removed by scripts/dev-reset-e2e-data.sh; the spec cancels its
// appointment with Dr. Banda itself.

test.describe.configure({ mode: 'serial' });

/** The list item of a correction request, found by the text the patient wrote. */
function requestItem(page: Page, text: string) {
  return page.getByRole('listitem').filter({ hasText: text });
}

test.describe('flow: record corrections', () => {
  const tag = runTag();
  let patient: RegisteredUser;
  let leaver: RegisteredUser;
  let patientApi: Api;
  let james: Api;
  let admin: Api;
  let jamesRecord: { recordId: number; title: string };
  let orphanRecord: { recordId: number; title: string };
  const appointments: number[] = [];
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    admin = await loginAdmin();
    patient = await registerAccount({ role: 'patient', lastName: uniqueSurname('Correct') });
    leaver = await registerApprovedDoctor(admin, { lastName: uniqueSurname('Leaver') });
    [patientApi, james] = await Promise.all([Api.login(patient), Api.login(JAMES)]);

    // A care relationship with each doctor (an appointment), so each can file a record for the patient.
    const withJames = await bookFirstFreeSlot(patientApi, patient.systemId, JAMES.systemId, marked(tag, 'blood pressure review'));
    appointments.push(withJames.appointmentId);
    await bookFirstFreeSlot(patientApi, patient.systemId, leaver.systemId, marked(tag, 'first visit'));

    const title1 = marked(tag, 'Blood pressure review');
    const r1 = await james.ok(james.postIdempotent('/api/medical-records', {
      patientSystemId: patient.systemId, recordType: 'NOTE', title: title1,
      description: 'Blood pressure 160/100 at rest.',
    }), 'Dr. Banda files a record');
    jamesRecord = { recordId: r1.recordId, title: title1 };

    const leaverApi = await Api.login(leaver);
    try {
      const title2 = marked(tag, 'Allergy note');
      const r2 = await leaverApi.ok(leaverApi.postIdempotent('/api/medical-records', {
        patientSystemId: patient.systemId, recordType: 'NOTE', title: title2, description: 'Allergic to penicillin.',
      }), 'the leaving doctor files a record');
      orphanRecord = { recordId: r2.recordId, title: title2 };
    } finally {
      await leaverApi.dispose();
    }
    // The author leaves: a request about their record has nobody to review it.
    await deactivateAccount(admin, leaver.systemId, marked(tag, 'left the practice'));
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await cancelAppointments(patientApi, appointments);
    await Promise.all([patientApi?.dispose(), james?.dispose(), admin?.dispose()]);
  });

  test('the patient asks for a correction and the authoring doctor accepts it', async ({ browser }) => {
    const text = marked(tag, 'The reading was 120/80, not 160/100.');
    const note = marked(tag, 'Corrected the reading in the record.');
    const requester = `${patient.firstName} ${patient.lastName}`;
    let requestId = 0;

    await test.step('the patient sends the request from the record card', async () => {
      const p = await flowPage(browser, patient);
      pages.push(p);
      await openSectionAt(p.page, 'patient', 'medical-records', {}, 'Medical Records');
      await p.page.getByRole('button', { name: `Request a correction to ${jamesRecord.title}` }).click();
      const dialog = p.page.getByRole('dialog', { name: 'Request a correction' });
      await dialog.getByLabel('What should be corrected?', { exact: true }).fill(text);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/correction-requests') && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Send request' }).click();
      const response = await res;
      expect(response.status()).toBe(201);
      const created = await response.json();
      requestId = created.requestId;
      expect(created).toMatchObject({
        status: 'OPEN', resourceType: 'MEDICAL_RECORD', resourceId: jamesRecord.recordId, assignedDoctorSystemId: JAMES.systemId,
      });
      await expect(p.page.getByText('Correction requested', { exact: true })).toBeVisible();
    });

    await test.step('Dr. Banda accepts it from his correction queue with a note', async () => {
      const d = await flowPage(browser, JAMES);
      pages.push(d);
      await openSectionAt(d.page, 'doctor', 'medical-records', { tab: 'corrections' }, 'Medical Records');
      const item = requestItem(d.page, text);
      await expect(item).toBeVisible({ timeout: 30_000 });
      await item.getByRole('button', { name: `Accept the correction request of ${requester}` }).click();
      const dialog = d.page.getByRole('dialog', { name: 'Accept correction request' });
      await dialog.getByLabel(`Note for ${requester}`, { exact: true }).fill(note);
      const res = d.page.waitForResponse(r => r.url().endsWith(`/api/correction-requests/${requestId}/resolve`));
      await dialog.getByRole('button', { name: 'Accept request' }).click();
      expect((await res).status()).toBe(200);
    });

    await test.step('the request is accepted with the note, and the patient is told', async () => {
      const row = await patientApi.ok(patientApi.get(`/api/correction-requests/${requestId}`), 'correction request');
      expect(row).toMatchObject({ status: 'ACCEPTED', resolverSystemId: JAMES.systemId, resolutionNote: note });
      await waitForNotifications(patientApi, n => n.title === 'Correction request accepted' && n.resourceId === requestId,
        'correction request accepted');
      const again = await james.post(`/api/correction-requests/${requestId}/resolve`, { decision: 'REJECTED', note: marked(tag, 'again') });
      expect(again.status, 'a resolved request cannot be resolved again').toBe(409);
    });
  });

  test('a request with no reviewer appears for the administrator and is assigned', async ({ browser }) => {
    const text = marked(tag, 'I am allergic to amoxicillin, not penicillin.');
    const patientName = `${patient.firstName} ${patient.lastName}`;
    const filed = await patientApi.ok(patientApi.postIdempotent('/api/correction-requests', {
      patientSystemId: patient.systemId, resourceType: 'MEDICAL_RECORD', resourceId: orphanRecord.recordId, requestedText: text,
    }), 'request about the leaver\'s record');
    expect(filed.assignedDoctorSystemId, 'the author is no longer active, so nobody is assigned').toBeNull();
    const requestId = filed.requestId as number;
    await waitForNotifications(admin, n => n.title === 'Correction request needs a doctor' && n.resourceId === requestId,
      'administrators are told about the unassigned request');

    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    await test.step('the administrator finds it under "Only unassigned"', async () => {
      await openSectionAt(a.page, 'admin', 'corrections', { unassigned: 'true' }, 'Correction requests');
      await expect(a.page.getByRole('switch', { name: 'Only unassigned' })).toHaveAttribute('aria-checked', 'true');
      const item = requestItem(a.page, text);
      await expect(item).toBeVisible({ timeout: 30_000 });
      await expect(item.getByText('Unassigned', { exact: true })).toBeVisible();
      await item.getByRole('button', { name: new RegExp(`^Review the correction request for .+ of ${escapeRe(patientName)}$`) }).click();
    });

    await test.step('and assigns it to one of the patient\'s doctors', async () => {
      const panel = a.page.getByRole('dialog', { name: `Correction request #${requestId}` });
      await expect(panel).toBeVisible();
      const choice = panel.getByLabel('Assign to', { exact: true });
      await expect(choice.locator(`option[value="${JAMES.systemId}"]`)).toHaveCount(1);
      await expect(choice.locator(`option[value="${leaver.systemId}"]`), 'an inactive doctor is not offered').toHaveCount(0);
      await choice.selectOption({ value: JAMES.systemId });
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/correction-requests/${requestId}/assign`));
      await panel.getByRole('button', { name: 'Assign', exact: true }).click();
      expect((await res).status()).toBe(200);
      await expect(panel.getByText(/James Banda/).first()).toBeVisible();
    });

    await test.step('Dr. Banda now has it to review', async () => {
      const row = await admin.ok(admin.get(`/api/correction-requests/${requestId}`), 'assigned request');
      expect(row).toMatchObject({ status: 'OPEN', assignedDoctorSystemId: JAMES.systemId });
      const assigned = await james.ok(james.page('/api/correction-requests/assigned', { status: 'OPEN' }, 0, 100), 'Dr. Banda\'s queue');
      expect((assigned.content as { requestId: number }[]).map(r => r.requestId)).toContain(requestId);
      await waitForNotifications(james, n => n.title === 'Correction request assigned to you' && n.resourceId === requestId,
        'assigned correction request');

      const unassigned = await admin.ok(admin.page('/api/admin/correction-requests', { status: 'OPEN', unassigned: true }, 0, 100), 'unassigned');
      expect((unassigned.content as { requestId: number }[]).map(r => r.requestId)).not.toContain(requestId);
    });
  });
});
