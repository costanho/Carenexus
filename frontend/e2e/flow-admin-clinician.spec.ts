import { expect, type Locator, type Page, test } from '@playwright/test';
import { loginAdmin, registerAccount, type RegisteredUser } from './support/accounts';
import type { Api } from './support/api';
import { ADMIN, runTag } from './support/env';
import { closeOutboxReader } from './support/outbox';
import { login, openSectionAt } from './support/ui';
import {
  closeAll, flowPage, type FlowPage, registerThroughUi, submitSignIn, tryLogin, uniqueSurname,
} from './flows/account-helpers';

// Clinician verification: a doctor registers on the web form with licence details and cannot sign in while the
// registration waits; an administrator approves it from the verification queue and the doctor signs in. A second
// registration is rejected with a reason (sign-in then says so); the administrator corrects the licence number and
// re-opens and approves the rejected registration, after which that doctor signs in too.
//
// Tables written: users, doctors (verification status, licence fields, verified_by, rejection_reason),
// user_settings, legal_acceptances, email_verification_tokens, refresh_tokens, outbound_messages (clinician
// approved and rejected emails), notifications (administrators' "new clinician" and the doctor's approval),
// audit_log. Every row belongs to throwaway accounts e2e-…@carenexus.test and is removed by
// scripts/dev-reset-e2e-data.sh.

test.describe.configure({ mode: 'serial' });

const QUEUE_LABEL = 'Clinician verification';

function nameOf(user: RegisteredUser): string {
  return `${user.firstName} ${user.lastName}`;
}

/** The queue card of one clinician (a list item holding the link to their account). */
function queueCard(page: Page, user: RegisteredUser) {
  return page.getByRole('listitem').filter({ has: page.getByRole('link', { name: `${nameOf(user)}, ${user.systemId}, open account` }) });
}

/**
 * The queue card of one clinician, paging through the list until it shows. Every tab lists registrations oldest
 * first, 20 to a page behind a "Load more" button, so a registration made by this run is on the last page once a
 * tab holds more than 20 clinicians (seed data plus the throwaway doctors other specs register and approve).
 */
async function revealQueueCard(page: Page, user: RegisteredUser): Promise<Locator> {
  const card = queueCard(page, user);
  const more = page.getByRole('button', { name: 'Load more', exact: true });
  const rows = page.getByRole('list', { name: /^Clinicians: / }).getByRole('listitem');
  for (let pageNo = 0; pageNo < 50; pageNo++) {
    await expect(card.or(more).first(), `the card of ${nameOf(user)} or a "Load more" button`).toBeVisible();
    if (await card.count() > 0) break;
    const before = await rows.count();
    await more.click();
    await expect.poll(() => rows.count(), { message: 'the next page of clinicians loads' }).toBeGreaterThan(before);
  }
  await expect(card).toBeVisible();
  return card;
}

async function openQueue(page: Page, tab?: 'REJECTED' | 'APPROVED'): Promise<void> {
  await openSectionAt(page, 'admin', 'clinician-queue', tab ? { tab } : {}, QUEUE_LABEL);
  await expect(page.getByRole('heading', { name: 'Clinician verification' })).toBeVisible();
}

test.describe('flow: clinician verification by an administrator', () => {
  let admin: Api;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    admin = await loginAdmin();
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await admin?.dispose();
    await closeOutboxReader();
  });

  test('a doctor registers with licence details, waits, is approved from the queue and signs in', async ({ browser }) => {
    const tag = runTag();
    const licenseNo = `E2E-LIC-${tag.slice(4).toUpperCase()}`;
    const reg = await flowPage(browser);
    pages.push(reg);
    const { user: doctor, sent } = await registerThroughUi(reg.page, {
      role: 'doctor', tag, lastName: uniqueSurname('Approve'), licenseNo, specialization: 'Family Medicine',
      licensingBody: 'Medical and Dental Practitioners Council',
    });

    await test.step('the form sent the licence details and the confirmation says the account waits for approval', async () => {
      expect(String(sent.role)).toMatch(/^doctor$/i);
      expect(sent.licenseNo).toBe(licenseNo);
      expect(sent.specialization).toBe('Family Medicine');
      await expect(reg.page.getByText(/Your clinician account is pending verification/)).toBeVisible();
    });

    await test.step('signing in is refused while the registration waits', async () => {
      const answer = await submitSignIn(reg.page, doctor);
      expect(answer.status).toBe(403);
      expect(answer.body?.code).toBe('ACCOUNT_PENDING_VERIFICATION');
      await expect(reg.page.getByText(/pending verification/i).first()).toBeVisible();
      await expect(reg.page).toHaveURL(/\/auth\/login\/web\/login-web$/);
    });

    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    await test.step('the administrator sees the registration and its licence in the queue and approves it', async () => {
      await openQueue(a.page);
      const card = await revealQueueCard(a.page, doctor);
      await expect(card.getByText(licenseNo, { exact: true })).toBeVisible();
      await expect(card.getByText('Awaiting verification', { exact: true })).toBeVisible();
      await card.getByRole('button', { name: `Approve ${nameOf(doctor)}` }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Approve clinician' });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByText(new RegExp(`Licence ${licenseNo}`))).toBeVisible();
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/doctors/${doctor.systemId}/verify`));
      await dialog.getByRole('button', { name: 'Approve', exact: true }).click();
      expect((await res).status()).toBe(200);
      await expect(a.page.getByText(`${nameOf(doctor)} (${doctor.systemId}) is approved and can sign in as a clinician.`)).toBeVisible();
      await expect(queueCard(a.page, doctor)).toHaveCount(0);
    });

    await test.step('the approved doctor signs in to the doctor dashboard', async () => {
      await login(reg.page, doctor);
      await expect(reg.page).toHaveURL(/\/dashboard\/doctor$/);
      const row = await admin.ok(admin.get(`/api/admin/users/${doctor.systemId}`), 'doctor account');
      expect(row.isActive).toBe(true);
    });
  });

  test('a rejected registration is corrected and approved: the doctor then signs in', async ({ browser }) => {
    const wrongLicence = `E2E-WRONG-${runTag().slice(4).toUpperCase()}`;
    const doctor = await registerAccount({ role: 'doctor', lastName: uniqueSurname('Reject'), clinician: { licenseNo: wrongLicence } });
    const reason = `${runTag()} The licence number could not be found in the council register.`;
    const a = await flowPage(browser, ADMIN);
    pages.push(a);

    await test.step('the administrator rejects the registration with a reason', async () => {
      await openQueue(a.page);
      const card = await revealQueueCard(a.page, doctor);
      await card.getByRole('button', { name: `Reject ${nameOf(doctor)}` }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Reject registration' });
      await expect(dialog).toBeVisible();
      await test.step('a reason is required', async () => {
        let posted = false;
        const watch = (r: { url(): string }) => { if (r.url().includes('/reject')) posted = true; };
        a.page.on('request', watch);
        await dialog.getByRole('button', { name: 'Reject registration' }).click();
        await expect(dialog.getByRole('alert').first()).toBeVisible();
        a.page.off('request', watch);
        expect(posted, 'no request without a reason').toBe(false);
      });
      await dialog.getByLabel('Reason', { exact: true }).fill(reason);
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/doctors/${doctor.systemId}/reject`));
      await dialog.getByRole('button', { name: 'Reject registration' }).click();
      expect((await res).status()).toBe(200);
      await expect(a.page.getByText(`The registration of ${nameOf(doctor)} (${doctor.systemId}) was rejected.`)).toBeVisible();
    });

    await test.step('the rejected doctor cannot sign in', async () => {
      const attempt = await tryLogin(doctor.email, doctor.password);
      expect(attempt.status).toBe(403);
      expect(attempt.body?.code).toBe('ACCOUNT_REJECTED');
    });

    const correctLicence = `E2E-FIX-${runTag().slice(4).toUpperCase()}`;
    await test.step('the administrator corrects the licence number on the Rejected tab', async () => {
      await openQueue(a.page, 'REJECTED');
      await expect(a.page.getByRole('tab', { name: /^Rejected/ })).toHaveAttribute('aria-selected', 'true');
      const card = await revealQueueCard(a.page, doctor);
      await expect(card.getByText(reason, { exact: true })).toBeVisible();
      await card.getByRole('button', { name: `Edit licence of ${nameOf(doctor)}` }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Edit licence' });
      const field = dialog.getByLabel('Licence number', { exact: true });
      await expect(field).toHaveValue(wrongLicence);
      await field.fill(correctLicence);
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/doctors/${doctor.systemId}/licence`));
      await dialog.getByRole('button', { name: 'Save licence' }).click();
      expect((await res).status()).toBe(200);
      await expect(a.page.getByText(`The licence details of ${nameOf(doctor)} (${doctor.systemId}) were saved.`)).toBeVisible();
      await expect(card.getByText(correctLicence, { exact: true })).toBeVisible();
      await expect(card.getByText('Rejected', { exact: true }).first()).toBeVisible();
    });

    await test.step('re-open and approve: the clinician moves to Approved', async () => {
      const card = queueCard(a.page, doctor);
      await card.getByRole('button', { name: `Re-open and approve ${nameOf(doctor)}` }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Re-open and approve' });
      await expect(dialog.getByText(/This re-opens a registration that was rejected\./)).toBeVisible();
      await expect(dialog.getByText(new RegExp(`Licence ${correctLicence}`))).toBeVisible();
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/doctors/${doctor.systemId}/verify`));
      await dialog.getByRole('button', { name: 'Re-open and approve' }).click();
      expect((await res).status()).toBe(200);
      await expect(queueCard(a.page, doctor)).toHaveCount(0);
      await openQueue(a.page, 'APPROVED');
      await expect((await revealQueueCard(a.page, doctor)).getByText(correctLicence, { exact: true })).toBeVisible();
    });

    await test.step('the doctor signs in, and the audit log holds the three decisions', async () => {
      const d = await flowPage(browser, doctor);
      pages.push(d);
      await expect(d.page).toHaveURL(/\/dashboard\/doctor$/);
      const rows = await admin.ok(admin.page(`/api/admin/users/${doctor.systemId}/audit-logs`, {}, 0, 100), 'audit rows');
      const actions = (rows.content as { action: string }[]).map(r => r.action);
      for (const action of ['DOCTOR_REJECT', 'LICENCE_UPDATE', 'DOCTOR_VERIFY']) expect(actions, action).toContain(action);
    });
  });
});
