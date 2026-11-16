import { expect, type Page, test } from '@playwright/test';
import { loginAdmin } from './support/accounts';
import type { Api } from './support/api';
import { ADMIN, runTag, SARAH, THOMAS } from './support/env';
import { openSectionAt } from './support/ui';
import { closeAll, flowPage, type FlowPage, letters } from './flows/account-helpers';

// System settings: the administrator changes the clinic name and the support email on the System settings page,
// and the Help & Support page of a patient and of a caregiver shows the new values; an invalid email is refused,
// in the form (nothing is sent) and by the API, and the stored value stays as it was. The spec puts both settings
// back as it found them.
//
// Tables written: system_settings (two overrides, by the seeded administrator; their values start with "e2e" or
// end in @carenexus.test, which is how scripts/dev-reset-e2e-data.sh recognises them if the restore below did not
// run), audit_log.

test.describe.configure({ mode: 'serial' });

interface SettingRow { key: string; value: string; overridden: boolean }

const CLINIC = 'clinic.name';
const EMAIL = 'support.contact-email';

async function readSettings(admin: Api): Promise<Map<string, SettingRow>> {
  const rows = await admin.ok(admin.get<SettingRow[]>('/api/admin/settings'), 'system settings');
  return new Map(rows.map(r => [r.key, r]));
}

/** Saves one setting through its row on the System settings page. */
async function saveSetting(page: Page, label: string, key: string, value: string): Promise<void> {
  const field = page.getByLabel(`${label} value`, { exact: true });
  await field.fill(value);
  const res = page.waitForResponse(r => r.url().endsWith(`/api/admin/settings/${key}`) && r.request().method() === 'PUT');
  await page.getByRole('button', { name: `Save ${label}` }).click();
  expect((await res).status(), `PUT ${key}`).toBe(200);
  await expect(page.getByText(`Saved. ${label} is now ${value}.`)).toBeVisible();
}

test.describe('flow: administrator changes system settings', () => {
  let admin: Api;
  let before: Map<string, SettingRow>;
  const clinicName = `e2e Clinic ${letters(5)}`;
  const supportEmail = `${runTag()}@carenexus.test`;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    admin = await loginAdmin();
    before = await readSettings(admin);
    expect(before.has(CLINIC) && before.has(EMAIL), 'both settings are known to the server').toBe(true);
  });

  test.afterAll(async () => {
    await closeAll(pages);
    if (admin && before) {
      for (const key of [CLINIC, EMAIL]) {
        const original = before.get(key);
        if (!original) continue;
        if (original.overridden) await admin.put(`/api/admin/settings/${key}`, { value: original.value }).catch(() => null);
        else await admin.del(`/api/admin/settings/${key}`).catch(() => null);
      }
    }
    await admin?.dispose();
  });

  test('the clinic name and support email are changed and Help & Support shows them', async ({ browser }) => {
    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    await openSectionAt(a.page, 'admin', 'system-settings', {}, 'System settings');
    await expect(a.page.getByRole('heading', { name: 'Clinic name', exact: true })).toBeVisible({ timeout: 30_000 });

    await saveSetting(a.page, 'Clinic name', CLINIC, clinicName);
    await saveSetting(a.page, 'Support email', EMAIL, supportEmail);

    const stored = await readSettings(admin);
    expect(stored.get(CLINIC)).toMatchObject({ value: clinicName, overridden: true });
    expect(stored.get(EMAIL)).toMatchObject({ value: supportEmail, overridden: true });

    for (const user of [THOMAS, SARAH]) {
      await test.step(`${user.role} Help & Support shows the new contact`, async () => {
        const p = await flowPage(browser, user);
        pages.push(p);
        await openSectionAt(p.page, user.role, 'help-support', {}, 'Help & Support');
        await expect(p.page.getByText(clinicName, { exact: true })).toBeVisible({ timeout: 20_000 });
        await expect(p.page.getByRole('link', { name: `${supportEmail}, send an email` })).toBeVisible();
        await expect(p.page.getByText(new RegExp(`write to ${clinicName} support`)).first()).toBeVisible();
      });
    }

    await test.step('the administrator\'s own Help & Support shows them too', async () => {
      await openSectionAt(a.page, 'admin', 'help-support', {}, 'Help & Support');
      await expect(a.page.getByText(clinicName, { exact: true })).toBeVisible();
      await expect(a.page.getByRole('link', { name: `${supportEmail}, send an email` })).toBeVisible();
    });
  });

  test('an invalid support email is refused and the stored value is kept', async ({ browser }) => {
    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    await openSectionAt(a.page, 'admin', 'system-settings', {}, 'System settings');
    const field = a.page.getByLabel('Support email value', { exact: true });
    await expect(field).toHaveValue(supportEmail, { timeout: 30_000 });

    await test.step('the form refuses it without sending anything', async () => {
      let sent = false;
      const watch = (r: { url(): string; method(): string }) => { if (r.url().endsWith(`/api/admin/settings/${EMAIL}`) && r.method() === 'PUT') sent = true; };
      a.page.on('request', watch);
      await field.fill('not-an-email');
      await a.page.getByRole('button', { name: 'Save Support email' }).click();
      await expect(a.page.getByText('Enter a valid email address.', { exact: true })).toBeVisible();
      a.page.off('request', watch);
      expect(sent, 'no PUT for an invalid value').toBe(false);
    });

    await test.step('the API refuses it as well, and an over-long clinic name', async () => {
      const bad = await admin.put(`/api/admin/settings/${EMAIL}`, { value: 'not-an-email' });
      expect(bad.status).toBe(400);
      expect(bad.body?.message).toBe('value: must be a valid email address');
      const long = await admin.put(`/api/admin/settings/${CLINIC}`, { value: `e2e ${'x'.repeat(130)}` });
      expect(long.status).toBe(400);
      expect(long.body?.message).toBe('value: must be at most 120 characters');
      const blank = await admin.put(`/api/admin/settings/${CLINIC}`, { value: '   ' });
      expect(blank.status).toBe(400);
    });

    await test.step('the stored values did not change', async () => {
      const stored = await readSettings(admin);
      expect(stored.get(EMAIL)?.value).toBe(supportEmail);
      expect(stored.get(CLINIC)?.value).toBe(clinicName);
      await a.page.reload();
      await expect(a.page.getByLabel('Support email value', { exact: true })).toHaveValue(supportEmail, { timeout: 30_000 });
    });
  });
});
