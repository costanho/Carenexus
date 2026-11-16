import { expect, type Page, test } from '@playwright/test';
import { JAMES, ROLE_HOME, ROLE_LOGIN_LABEL, THOMAS } from './support/env';
import { login } from './support/ui';
import { PageMonitor, SMOKE_PREFIX, apiIdle, expectNoArtifacts, smokeTag } from './support/smoke';

// Smoke: the sign-in page rejects a wrong password and a wrong role without signing anyone in, and
// the registration page validates its form, rejects a duplicate email, and creates a new patient
// account through the UI that lands signed in on the patient dashboard.

const LOGIN_PATH = '/auth/login/web/login-web';
const REGISTER_PATH = '/auth/register/web/register-web';

async function freshPage(browser: import('@playwright/test').Browser, who: string): Promise<{ page: Page; monitor: PageMonitor; close: () => Promise<void> }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('dialog', d => { d.accept().catch(() => {}); });
  const monitor = new PageMonitor(page, who);
  return { page, monitor, close: () => context.close() };
}

async function storedTokens(page: Page): Promise<(string | null)[]> {
  return page.evaluate(() => [sessionStorage.getItem('carenexus_access_token'), sessionStorage.getItem('carenexus_refresh_token')]);
}

test.describe('smoke auth pages', () => {
  test('sign in: wrong password and wrong role are refused with a message and no session', async ({ browser }) => {
    const { page, monitor, close } = await freshPage(browser, 'anonymous');
    try {
      // A wrong password should be 401; the API currently answers 400 (checked softly below), so both are allowed here.
      for (const status of [400, 401]) {
        monitor.allow({ method: 'POST', url: /^\/api\/auth\/login$/, status, why: 'the test signs in with a deliberately wrong password' });
      }
      await page.goto(LOGIN_PATH);
      await page.getByRole('radio', { name: ROLE_LOGIN_LABEL.patient, exact: true }).click();
      await page.getByLabel('Email address').fill(THOMAS.email);
      await page.getByLabel('Password', { exact: true }).fill(`${THOMAS.password}-wrong`);
      const res = page.waitForResponse(r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      expect.soft((await res).status(), 'wrong password is answered 401 Unauthorized').toBe(401);
      await expect(page.getByText(/^Invalid (credentials|email or password)\.?$/)).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
      expect(await storedTokens(page), 'no tokens stored after a failed sign in').toEqual([null, null]);
      monitor.expectClean('wrong password');

      // A doctor's account with the "Patient" role selected: refused, local session cleared.
      await page.getByLabel('Email address').fill(JAMES.email);
      await page.getByLabel('Password', { exact: true }).fill(JAMES.password);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      await expect(page.getByText('This account is registered as a Doctor. Please select the correct role.')).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
      await apiIdle(page);
      expect(await storedTokens(page), 'no tokens kept after a wrong-role sign in').toEqual([null, null]);
      await page.goto(ROLE_HOME.doctor);
      await expect(page, 'still signed out').toHaveURL(new RegExp(`${LOGIN_PATH}$`));
      monitor.expectClean('wrong role');
    } finally {
      await close();
    }
  });

  test('register: validation, duplicate email, then a new patient account signs in and reaches the dashboard', async ({ browser }) => {
    const { page, monitor, close } = await freshPage(browser, 'anonymous');
    try {
      await page.goto(LOGIN_PATH);
      await page.getByText('Create a new account', { exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`${REGISTER_PATH}$`));
      await expect(page.getByText('Create your account')).toBeVisible();

      await test.step('empty form shows field errors and sends nothing', async () => {
        let posted = false;
        page.on('request', r => { if (r.url().endsWith('/api/auth/register')) posted = true; });
        await page.getByText('Create Account', { exact: true }).click();
        for (const msg of ['First name is required', 'Last name is required', 'Email is required', 'You must accept the terms to continue']) {
          await expect(page.getByText(msg, { exact: true }), msg).toBeVisible();
        }
        expect(posted, 'no register request for an invalid form').toBe(false);
      });

      const tag = smokeTag();
      const email = `${tag}@carenexus.test`;
      const password = `Smoke-${crypto.randomUUID().slice(0, 8)}9`;
      const phone = `77${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`;
      // The login screen stays mounted (hidden) under the register screen, so only visible inputs count.
      const field = (placeholder: string) => page.getByPlaceholder(placeholder, { exact: true }).filter({ visible: true });
      const fill = async (mail: string) => {
        await field('John').fill('Smoke');
        await field('Smith').fill('Patient');
        await field('you@example.com').fill(mail);
        await field('Phone number').fill(phone);
        await field('Min. 8 characters').fill(password);
        await field('Re-enter your password').fill(password);
      };

      await test.step('duplicate email is refused with a clear message', async () => {
        monitor.allow({ method: 'POST', url: /^\/api\/auth\/register$/, status: 400, why: 'registering an email that already exists is refused' });
        monitor.allow({ method: 'POST', url: /^\/api\/auth\/register$/, status: 409, why: 'registering an email that already exists is refused' });
        await fill(THOMAS.email);
        await page.getByText(/^I agree to the/).click();
        const res = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
        await page.getByText('Create Account', { exact: true }).click();
        expect((await res).status(), 'duplicate email refused').toBeGreaterThanOrEqual(400);
        await expect(page.getByText(/^(Email already registered|An account with this email already exists\.)$/)).toBeVisible();
        await expect(page).toHaveURL(new RegExp(`${REGISTER_PATH}$`));
        monitor.expectClean('duplicate email');
      });

      await test.step('a new patient registers, is sent to sign in, and signs in to the patient dashboard', async () => {
        await field('you@example.com').fill(email);
        const dialogs: string[] = [];
        page.on('dialog', d => dialogs.push(d.message()));
        const res = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
        await page.getByText('Create Account', { exact: true }).click();
        expect((await res).status(), 'registration accepted').toBeLessThan(300);
        // Self-registration issues no session: the app confirms and sends the user to sign in.
        await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`), { timeout: 20_000 });
        await expect.poll(() => dialogs.join(' | '), { message: 'account-created confirmation shown' }).toMatch(/Account created/);
        await login(page, { email, password, role: 'patient', systemId: '', name: 'Smoke Patient' });
        await expect(page).toHaveURL(new RegExp(`${ROLE_HOME.patient}$`), { timeout: 20_000 });
        await expect(page.getByText('Welcome back, Smoke!')).toBeVisible();
        await expect(page.getByText('Health Overview', { exact: true })).toBeVisible();
        await apiIdle(page);
        await expectNoArtifacts(page, 'new patient dashboard');
        monitor.expectClean('new patient dashboard');
        expect(email.startsWith(SMOKE_PREFIX)).toBe(true);
      });
    } finally {
      await close();
    }
  });

  test('register form fields and role choice are exposed to assistive technology', async ({ browser }) => {
    const { page, close } = await freshPage(browser, 'anonymous');
    try {
      await page.goto(REGISTER_PATH);
      await expect(page.getByText('Create your account')).toBeVisible();
      await expect(page.getByPlaceholder('you@example.com')).toBeVisible();
      // The sign-in page labels its inputs (getByLabel works there); the registration form should too.
      // Counted once the form has rendered (no waiting per check).
      for (const label of ['First Name', 'Last Name', 'Email Address', 'Password', 'Confirm Password']) {
        expect.soft(await page.getByLabel(label, { exact: true }).count(), `"${label}" input has an accessible name`).toBe(1);
      }
      for (const role of ['Patient', 'Doctor', 'Proxy / Carer']) {
        expect.soft(await page.getByRole('radio', { name: new RegExp(`^${role.replace(/[/]/g, '\\/')}\\b`) }).count(), `role "${role}" is a radio`).toBe(1);
      }
      expect.soft(await page.getByRole('checkbox', { name: /I agree to the Terms of Service/ }).count(), 'terms consent is a checkbox').toBe(1);
    } finally {
      await close();
    }
  });
});
