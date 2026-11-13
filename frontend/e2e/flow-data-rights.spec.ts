import { readFile } from 'node:fs/promises';
import { expect, test } from '@playwright/test';
import { loginAdmin, registerAccount, type RegisteredUser } from './support/accounts';
import { Api } from './support/api';
import { THOMAS } from './support/env';
import { sectionUrl } from './support/ui';
import {
  closeAll, flowPage, type FlowPage, openSession, refreshStatus, submitSignIn, tryLogin, uniqueSurname,
} from './flows/account-helpers';

// Data rights: a throwaway patient requests an export of their data from Profile & Settings, downloads it (a
// JSON file of their own account), and nobody else can download it; then the patient closes the account with the
// password and the typed confirmation, is signed out everywhere, and can no longer sign in.
//
// Tables written: users (closed_at, closure_reason, is_active), patients, user_settings, legal_acceptances,
// refresh_tokens, account_requests (EXPORT and CLOSURE rows) and the stored export file, outbound_messages,
// notifications, audit_log. All of it belongs to throwaway accounts e2e-…@carenexus.test and is removed by
// scripts/dev-reset-e2e-data.sh (the export file is deleted from storage after the commit).

test.describe.configure({ mode: 'serial' });

test.describe('flow: data export and account closure', () => {
  let user: RegisteredUser;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    user = await registerAccount({ role: 'patient', lastName: uniqueSurname('Rights') });
  });

  test.afterAll(async () => {
    await closeAll(pages);
  });

  test('request a data export and download it; nobody else can download it', async ({ browser }) => {
    const p = await flowPage(browser, user);
    pages.push(p);
    await p.page.goto(sectionUrl('patient', 'profile-settings', { tab: 'security' }));
    await expect(p.page.getByRole('heading', { name: 'Privacy and data' })).toBeVisible({ timeout: 30_000 });

    let requestId = 0;
    await test.step('the export is prepared after the password is confirmed', async () => {
      await p.page.getByRole('button', { name: 'Request data export' }).click();
      const dialog = p.page.getByRole('dialog', { name: 'Request a data export' });
      await dialog.getByLabel('Password', { exact: true }).fill(user.password);
      const res = p.page.waitForResponse(r => r.url().endsWith('/api/users/me/data-export') && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Prepare export' }).click();
      const response = await res;
      expect(response.status()).toBe(201);
      const created = await response.json();
      requestId = created.requestId;
      expect(created.type).toBe('EXPORT');
      await expect(p.page.getByText(/Your export (is ready to download|is being prepared)/)).toBeVisible();
    });

    await test.step('the download is a JSON file of this account', async () => {
      const exports = p.page.getByRole('list', { name: 'Data exports' });
      const button = exports.getByRole('button', { name: /^Download the export requested / }).first();
      await expect(button).toBeVisible({ timeout: 30_000 });
      const downloaded = p.page.waitForEvent('download');
      const res = p.page.waitForResponse(r => r.url().endsWith(`/api/users/me/data-export/${requestId}/download`));
      await button.click();
      const response = await res;
      expect(response.status()).toBe(200);
      const download = await downloaded;
      expect(download.suggestedFilename()).toMatch(new RegExp(`^carenexus-data-export-${user.systemId}-\\d{8}\\.json$`));
      const bundle = JSON.parse(await readFile((await download.path())!, 'utf8'));
      expect(bundle.exportVersion).toBe(1);
      expect(bundle.systemId).toBe(user.systemId);
      expect(bundle.role).toBe('PATIENT');
      expect(JSON.stringify(bundle.sections ?? {}), 'the bundle holds this account\'s own data').toContain(user.email);
    });

    await test.step('another patient cannot download it', async () => {
      const thomas = await Api.login(THOMAS);
      try {
        const status = (await thomas.get(`/api/users/me/data-export/${requestId}/download`)).status;
        expect([403, 404], `another account downloading export ${requestId} got ${status}`).toContain(status);
      } finally {
        await thomas.dispose();
      }
    });
  });

  test('close the account: signed out everywhere and sign-in refused', async ({ browser }) => {
    const reason = 'e2e closing this test account';
    const elsewhere = await openSession(user);
    const p = await flowPage(browser, user);
    pages.push(p);
    await p.page.goto(sectionUrl('patient', 'profile-settings', { tab: 'security' }));
    await expect(p.page.getByRole('heading', { name: 'Privacy and data' })).toBeVisible({ timeout: 30_000 });

    await p.page.getByRole('button', { name: 'Close account', exact: true }).click();
    const dialog = p.page.getByRole('dialog', { name: 'Close your account' });
    await test.step('the typed confirmation is required', async () => {
      await dialog.getByLabel('Password', { exact: true }).fill(user.password);
      let posted = false;
      const watch = (r: { url(): string }) => { if (r.url().endsWith('/api/users/me/close')) posted = true; };
      p.page.on('request', watch);
      await dialog.getByRole('button', { name: 'Close my account' }).click();
      await expect(dialog.getByRole('alert').first()).toBeVisible();
      p.page.off('request', watch);
      expect(posted, 'nothing is sent without the confirmation word').toBe(false);
    });

    await dialog.getByLabel('Reason (optional)', { exact: true }).fill(reason);
    await dialog.getByLabel('Type CLOSE to confirm', { exact: true }).fill('CLOSE');
    const res = p.page.waitForResponse(r => r.url().endsWith('/api/users/me/close'));
    await dialog.getByRole('button', { name: 'Close my account' }).click();
    expect((await res).status()).toBe(200);
    const closed = p.page.getByRole('dialog', { name: 'Your account is closed' });
    await expect(closed.getByText(/You have been signed out on every device\./)).toBeVisible();
    await closed.getByRole('button', { name: 'Go to sign in' }).click();
    await expect(p.page).toHaveURL(/\/auth\/login\/web\/login-web$/);

    await test.step('every session ended and signing in is refused as closed', async () => {
      expect(await refreshStatus(elsewhere), 'session opened elsewhere').toBe(401);
      const attempt = await tryLogin(user.email, user.password);
      expect(attempt.status).toBe(403);
      expect(attempt.body?.code).toBe('ACCOUNT_CLOSED');
      const answer = await submitSignIn(p.page, user);
      expect(answer.status).toBe(403);
      await expect(p.page.getByText(/This account has been closed/)).toBeVisible();
    });

    await test.step('the administrator sees the closure and its reason on the account', async () => {
      const admin = await loginAdmin();
      try {
        const account = await admin.ok(admin.get(`/api/admin/users/${user.systemId}`), 'closed account');
        expect(account.closedAt).toBeTruthy();
        expect(account.closureReason).toBe(reason);
        expect(account.isActive).toBe(false);
      } finally {
        await admin.dispose();
      }
    });
  });
});
