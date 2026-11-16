import { expect, test } from '@playwright/test';
import { loginAdmin, registerAccount, type RegisteredUser } from './support/accounts';
import { Api } from './support/api';
import { ADMIN, ROLE_HOME, runTag, THOMAS } from './support/env';
import { ADMIN_SECTIONS, marker } from './support/smoke';
import { openSectionAt, sectionMain } from './support/ui';
import {
  closeAll, expectSignedOut, flowPage, type FlowPage, openSession, refreshStatus, submitSignIn, tryLogin, uniqueSurname,
} from './flows/account-helpers';

// Administrator user controls: the administrator signs in through the normal sign-in page and lands on the admin
// home; finds a throwaway patient with the Users search; deactivates the account with a reason, which ends the
// patient's open browser session and every API session; the audit log shows the deactivation; the account is
// reactivated afterwards. A patient who opens the admin URL stays on their own dashboard and the admin API
// answers 403.
//
// Tables written: users (is_active, deactivation_reason), patients, user_settings, legal_acceptances,
// refresh_tokens, audit_log, outbound_messages (none expected for deactivation). All rows belong to the throwaway
// account e2e-…@carenexus.test and are removed by scripts/dev-reset-e2e-data.sh.

test.describe.configure({ mode: 'serial' });

test.describe('flow: administrator user controls', () => {
  let admin: Api;
  let target: RegisteredUser;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    admin = await loginAdmin();
    target = await registerAccount({ role: 'patient', lastName: uniqueSurname('Deactivate') });
  });

  test.afterAll(async () => {
    await closeAll(pages);
    // Leave the throwaway account active again if the test stopped half way (the cleanup removes it anyway).
    if (admin && target?.systemId) await admin.post(`/api/admin/users/${target.systemId}/activate`).catch(() => null);
    await admin?.dispose();
  });

  test('the administrator signs in on the normal sign-in page and lands on the admin home', async ({ browser }) => {
    const a = await flowPage(browser);
    pages.push(a);
    const answer = await submitSignIn(a.page, ADMIN);
    expect(answer.status).toBe(200);
    expect(answer.body?.user?.role).toBe('admin');
    await a.page.waitForURL(u => u.pathname === ROLE_HOME.admin, { timeout: 30_000 });
    const home = ADMIN_SECTIONS.find(s => s.key === 'dashboard')!;
    await expect(sectionMain(a.page, home.nav)).toBeVisible();
    await expect(marker(a.page, home)).toBeVisible();
    await expect(a.page.getByRole('heading', { name: home.title, exact: true })).toBeVisible();
  });

  test('search a user, deactivate the account: its sessions end and the audit log records it', async ({ browser }) => {
    const reason = `${runTag()} account reported as compromised`;
    const name = `${target.firstName} ${target.lastName}`;

    // The patient is signed in in a browser and through the API before the deactivation.
    const patient = await flowPage(browser, target);
    pages.push(patient);
    const apiSession = await openSession(target);

    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    const users = sectionMain(a.page, 'Users');
    await test.step('the Users search finds the account by email', async () => {
      await openSectionAt(a.page, 'admin', 'users', {}, 'Users');
      const search = users.getByLabel('Search', { exact: true });
      await search.fill(target.email);
      await expect(users.getByRole('link', { name: `${name}, ${target.systemId}, open account` })).toBeVisible();
      await expect(users.getByText(target.email, { exact: true })).toBeVisible();
      await expect(users.getByRole('link', { name: /, open account$/ }), 'the search narrows the list to the one account').toHaveCount(1);
    });

    await test.step('the account is deactivated with a reason', async () => {
      await users.getByRole('button', { name: `Deactivate ${name}` }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Deactivate account' });
      await expect(dialog.getByText(new RegExp(`${name} \\(${target.systemId}\\) will be signed out everywhere`))).toBeVisible();
      await dialog.getByLabel('Reason', { exact: true }).fill(reason);
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/users/${target.systemId}/deactivate`));
      await dialog.getByRole('button', { name: 'Deactivate account' }).click();
      expect((await res).status()).toBe(200);
      await expect(a.page.getByText(`${name} (${target.systemId}): Account deactivated. The user has been signed out everywhere.`)).toBeVisible();
    });

    await test.step('the patient\'s browser session ends and signing in is refused', async () => {
      await patient.page.reload();
      await expectSignedOut(patient.page, 'the deactivated patient\'s page');
      expect(await refreshStatus(apiSession), 'API session of the deactivated account').toBe(401);
      const attempt = await tryLogin(target.email, target.password);
      expect(attempt.status).toBe(400);
      expect(String(attempt.body?.message ?? '')).toMatch(/inactive/i);
    });

    await test.step('the account page shows the state and the reason', async () => {
      await openSectionAt(a.page, 'admin', 'user-detail', { id: target.systemId }, 'User details');
      await expect(a.page.getByText(reason, { exact: true })).toBeVisible();
      await expect(a.page.getByRole('button', { name: 'Reactivate account' })).toBeVisible();
    });

    await test.step('the audit log shows the deactivation by this administrator', async () => {
      await openSectionAt(a.page, 'admin', 'audit-log', { action: 'USER_DEACTIVATE', actor: ADMIN.systemId }, 'Audit log');
      const table = a.page.getByRole('table', { name: 'Audit entries' }).or(a.page.getByRole('list', { name: 'Audit entries' }));
      await expect(table.getByText(`Account ${target.systemId}`).first()).toBeVisible();
      await expect(table.getByText('USER_DEACTIVATE').first()).toBeVisible();
    });

    await test.step('the account is reactivated and can sign in again', async () => {
      await openSectionAt(a.page, 'admin', 'user-detail', { id: target.systemId }, 'User details');
      await a.page.getByRole('button', { name: 'Reactivate account' }).click();
      const dialog = a.page.getByRole('dialog', { name: 'Reactivate account' });
      const res = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/users/${target.systemId}/activate`));
      await dialog.getByRole('button', { name: 'Reactivate account' }).click();
      expect((await res).status()).toBe(200);
      await expect(a.page.getByRole('button', { name: 'Deactivate account' })).toBeVisible();
      expect((await tryLogin(target.email, target.password)).status).toBe(200);
      const audit = await admin.ok(admin.page(`/api/admin/users/${target.systemId}/audit-logs`, {}, 0, 100), 'account audit');
      const actions = (audit.content as { action: string }[]).map(r => r.action);
      expect(actions).toContain('USER_DEACTIVATE');
      expect(actions).toContain('USER_ACTIVATE');
    });
  });

  test('a patient cannot open the admin area: the URL goes back home and the admin API answers 403', async ({ browser }) => {
    const p = await flowPage(browser, THOMAS);
    pages.push(p);
    const adminCalls: number[] = [];
    p.page.on('response', r => { if (new URL(r.url()).pathname.startsWith('/api/admin/')) adminCalls.push(r.status()); });
    await p.page.goto(`${ROLE_HOME.admin}?section=users`);
    await expect(p.page).toHaveURL(new RegExp(`${ROLE_HOME.patient}$`), { timeout: 20_000 });
    await expect(p.page.getByRole('heading', { name: 'Users', exact: true })).toHaveCount(0);
    expect(adminCalls, 'the admin screens never mounted for a patient').toEqual([]);

    const thomas = await Api.login(THOMAS);
    try {
      for (const path of ['/api/admin/users', `/api/admin/users/${target.systemId}`, '/api/admin/audit-logs']) {
        expect((await thomas.get(path)).status, path).toBe(403);
      }
      expect((await thomas.post(`/api/admin/users/${target.systemId}/deactivate`, { reason: `${runTag()} not allowed` })).status).toBe(403);
    } finally {
      await thomas.dispose();
    }
  });
});
