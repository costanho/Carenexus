import { type Browser, expect, type Page, test } from '@playwright/test';
import { ADMIN, JAMES, type LoginRole, ROLE_HOME, ROLE_LOGIN_LABEL, SARAH, THOMAS, throwawayEmail } from './support/env';
import { LOGIN_PATH, escapeRe, login } from './support/ui';
import { ADMIN_SECTIONS, PageMonitor, apiIdle, expectNoArtifacts, marker, signedInPage, smokeTag } from './support/smoke';

// Smoke: the sign-in page rejects a wrong password and a wrong role without signing anyone in; the registration
// page validates its form, refuses a duplicate email with 409 under the email field, and creates a new patient
// through the UI without issuing a session (it shows "Check your inbox"); that patient then signs in (email
// verification is not enforced on the dev profiles) and reaches the patient dashboard. The administrator lands on
// the administration home, and a signed-in user who opens another role's dashboard URL is sent back to their own.
//
// Tables written: users, patients, legal_acceptances, email_verification_tokens and outbound_messages (the new
// e2e-smoke-…@carenexus.test patient and its verification email); refresh_tokens, audit_log and notifications
// (sign-in alerts) for every sign-in. All of it is removed by scripts/dev-reset-e2e-data.sh.

const REGISTER_PATH = '/auth/register/web/register-web';

async function freshPage(browser: Browser, who: string): Promise<{ page: Page; monitor: PageMonitor; close: () => Promise<void> }> {
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
      monitor.allow({ method: 'POST', url: /^\/api\/auth\/login$/, status: 401, why: 'the test signs in with a deliberately wrong password' });
      await page.goto(LOGIN_PATH);
      await page.getByRole('radio', { name: ROLE_LOGIN_LABEL.patient, exact: true }).click();
      await page.getByLabel('Email address').fill(THOMAS.email);
      await page.getByLabel('Password', { exact: true }).fill(`${THOMAS.password}-wrong`);
      const res = page.waitForResponse(r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      expect((await res).status(), 'wrong password is answered 401 Unauthorized').toBe(401);
      await expect(page.getByText(/^Invalid email or password\.?$/)).toBeVisible();
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

  test('register: validation, duplicate email (409), then a new patient gets no session, signs in and reaches the dashboard', async ({ browser }) => {
    const { page, monitor, close } = await freshPage(browser, 'anonymous');
    try {
      await page.goto(LOGIN_PATH);
      await page.getByText('Create a new account', { exact: true }).click();
      await expect(page).toHaveURL(new RegExp(`${REGISTER_PATH}$`));
      await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();

      await test.step('empty form shows field errors and sends nothing', async () => {
        let posted = false;
        page.on('request', r => { if (r.url().endsWith('/api/auth/register')) posted = true; });
        await page.getByRole('button', { name: 'Create Account', exact: true }).click();
        for (const msg of ['First name is required', 'Last name is required', 'Email is required', 'You must accept the terms to continue']) {
          await expect(page.getByText(msg, { exact: true }), msg).toBeVisible();
        }
        expect(posted, 'no register request for an invalid form').toBe(false);
      });

      const email = throwawayEmail(smokeTag());
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

      await test.step('duplicate email is refused with 409 and the message sits under the email field', async () => {
        monitor.allow({ method: 'POST', url: /^\/api\/auth\/register$/, status: 409, why: 'registering an email that already exists is refused with 409' });
        await fill(THOMAS.email);
        const terms = page.getByRole('checkbox', { name: /I agree to the Terms of Service/ });
        if ((await terms.getAttribute('aria-checked')) !== 'true') await terms.click();
        const res = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
        await page.getByRole('button', { name: 'Create Account', exact: true }).click();
        expect((await res).status(), 'duplicate email refused').toBe(409);
        await expect(page.getByText('An account with this email already exists.', { exact: true })).toBeVisible();
        await expect(page.getByLabel('Email Address', { exact: true })).toHaveAttribute('aria-invalid', 'true');
        await expect(page).toHaveURL(new RegExp(`${REGISTER_PATH}$`));
        monitor.expectClean('duplicate email');
      });

      await test.step('a new patient registers with the current legal versions and gets no session', async () => {
        await field('you@example.com').fill(email);
        const res = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
        await page.getByRole('button', { name: 'Create Account', exact: true }).click();
        const response = await res;
        expect(response.status(), 'registration accepted').toBe(201);
        const body = await response.json();
        // The contract defines `tokens` on the register response as present and always null.
        expect(body.tokens ?? null, 'self-registration issues no session').toBeNull();
        const sent = response.request().postDataJSON();
        expect(sent.acceptedTermsVersion, 'the form sends the Terms version it showed').toBeTruthy();
        expect(sent.acceptedPrivacyVersion, 'the form sends the Privacy version it showed').toBeTruthy();
        expect(await storedTokens(page), 'no tokens stored after registering').toEqual([null, null]);

        await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();
        await expect(page.getByText(email, { exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Go to sign in' }).click();
        await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`), { timeout: 20_000 });
        monitor.expectClean('registered');
      });

      await test.step('the new patient signs in (verification is off in dev) and reaches the patient dashboard', async () => {
        await login(page, { email, password, role: 'patient', systemId: '', name: 'Smoke Patient' });
        await expect(page).toHaveURL(new RegExp(`${ROLE_HOME.patient}$`), { timeout: 20_000 });
        await expect(page.getByText('Welcome back, Smoke!')).toBeVisible();
        await expect(page.getByText('Health Overview', { exact: true })).toBeVisible();
        await apiIdle(page);
        await expectNoArtifacts(page, 'new patient dashboard');
        monitor.expectClean('new patient dashboard');
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

  test('administrator: signs in to the administration home', async ({ browser }) => {
    const up = await signedInPage(browser, ADMIN);
    try {
      const { page, monitor } = up;
      await expect(page).toHaveURL(new RegExp(`${ROLE_HOME.admin}$`));
      const home = ADMIN_SECTIONS.find(s => s.key === 'dashboard')!;
      await expect(marker(page, home)).toBeVisible();
      await expect(page.getByRole('heading', { name: home.title, exact: true }).first()).toBeVisible();
      // Two "Your account" headings are expected: the account card (h2) and the Tools group of that name (h3).
      const accountHeading = page.getByRole('heading', { name: 'Your account', level: 2, exact: true });
      await expect(accountHeading).toBeVisible();
      await expect(page.getByText(ADMIN.email, { exact: true })).toBeVisible();
      // The overview tiles, each named '<what is counted>: <count>. <where it leads>' (count 'none', a number,
      // 'loading' or 'not available'), and the Refresh button over them.
      for (const [title, link] of [
        ['Pending clinician verifications', 'Open the verification queue'],
        ['Open account requests',           'Review deletion requests'],
        ['Open insurance claims',           'Review claims'],
        ['Overdue invoices',                'View overdue invoices'],
        ['Unassigned correction requests',  'Assign correction requests'],
        ['Open support conversations',      'Open the support inbox'],
      ] as const) {
        await expect(page.getByRole('button', { name: new RegExp(`^${escapeRe(title)}: .+\\. ${escapeRe(link)}$`) }), `${title} tile`)
          .toBeVisible();
      }
      await expect(page.getByRole('button', { name: 'Refresh the overview counts', exact: true })).toBeVisible();
      // The "Your account" card has its own Account security button (the sidebar has one too).
      const accountCard = page.locator('div').filter({ has: accountHeading })
        .filter({ has: page.getByText(ADMIN.email, { exact: true }) }).last();
      await expect(accountCard.getByRole('button', { name: 'Account security' })).toBeVisible();
      await apiIdle(page);
      await expectNoArtifacts(page, 'admin home');
      monitor.expectClean('admin home');
    } finally {
      await up.context.close();
    }
  });

  // Every signed-in role, administrators included: another role's dashboard URL lands on the own home.
  for (const user of [THOMAS, JAMES, SARAH, ADMIN]) {
    test(`${user.role}: another role's dashboard URL redirects to the own dashboard`, async ({ browser }) => {
      const up = await signedInPage(browser, user);
      try {
        const { page, monitor } = up;
        await apiIdle(page);
        for (const other of (Object.keys(ROLE_HOME) as LoginRole[]).filter(r => r !== user.role)) {
          await page.goto(ROLE_HOME[other]);
          await expect(page, `${user.role} opening ${ROLE_HOME[other]}`).toHaveURL(new RegExp(`${ROLE_HOME[user.role]}$`), { timeout: 20_000 });
          await apiIdle(page);
          // The other role's screens never mounted: their requests would have been refused with 403.
          monitor.expectClean(`goto ${ROLE_HOME[other]}`);
        }
      } finally {
        await up.context.close();
      }
    });
  }
});
