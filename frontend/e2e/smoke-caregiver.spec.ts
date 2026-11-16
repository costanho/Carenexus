import { expect, type Page, test } from '@playwright/test';
import { registerAccount } from './support/accounts';
import { Api, type Clients, disposeAll, loginAll, restoreSarahConsent, sarahGrant } from './support/api';
import { type DemoUser, ROLE_HOME, SARAH, THOMAS, throwawayEmail } from './support/env';
import { CHANGE_PASSWORD_PATH, finishSignIn, submitLogin } from './support/ui';
import {
  type MonitoredPage, PATIENT_SECTIONS, PROXY_SECTIONS, type Section, apiIdle, expectNoArtifacts, navButton, signedInPage,
  smokeTag, visit,
} from './support/smoke';

// Smoke: the patient grants, changes and revokes caregiver access through "My Proxies (Caregivers)" and the caregiver
// sees each change; the caregiver switches between the people they care for, adds a new loved one through the
// "Create a new account" flow (that person can then sign in), and changes a notification setting, which is saved on
// click. Sarah's demo grant is put back (full access, messaging + calls approved) afterwards.
//
// Tables written: users, legal_acceptances and email_verification_tokens / outbound_messages (throwaway
// e2e-smoke-…@carenexus.test caregivers and the loved ones they create, with their patients rows); dependent_access,
// consent_records and caregiver_activity_log (grants given, changed and removed; Sarah's grant is restored);
// user_settings (the throwaway caregiver's lab-results switch); refresh_tokens, audit_log and notifications (access
// changes, sign-in alerts). scripts/dev-reset-e2e-data.sh removes the throwaway accounts and their rows.

const find = (list: Section[], nav: string) => list.find(s => s.nav === nav)!;

/** Puts Sarah's grant back to the dev-seed state: full access, messaging + calls approved. */
async function restoreSarahFully(api: Clients): Promise<void> {
  const g = await sarahGrant(api.thomas);
  if (g.accessLevel !== 'FULL_ACCESS') {
    await api.thomas.ok(api.thomas.patch(`/api/patients/me/caregiver-access/${g.accessId}`, { accessLevel: 'FULL_ACCESS' }), 'restore Sarah access level');
  }
  await restoreSarahConsent(api.thomas);
}

/** A throwaway caregiver registered like the web form does (current legal versions accepted). */
async function registerSmokeCaregiver(): Promise<DemoUser> {
  return registerAccount({ role: 'proxy', tag: smokeTag(), firstName: 'Smoke', lastName: 'Carer' });
}

/**
 * Signs in with a temporary password. When the server asks for a new password first (flags.mustChangePassword) the
 * change-password page is completed; pending legal documents are accepted on the way.
 */
async function signInWithTemporaryPassword(page: Page, user: DemoUser): Promise<void> {
  await submitLogin(page, user);
  await page.waitForURL(u => u.pathname === ROLE_HOME[user.role] || u.pathname === CHANGE_PASSWORD_PATH || u.pathname === '/auth/accept-terms',
    { timeout: 30_000 });
  if (new URL(page.url()).pathname === CHANGE_PASSWORD_PATH) {
    const next = `E2e-${crypto.randomUUID()}`;
    await page.getByLabel('Current (temporary) password', { exact: true }).fill(user.password);
    await page.getByLabel('New password', { exact: true }).fill(next);
    await page.getByLabel('Confirm new password', { exact: true }).fill(next);
    await page.getByRole('button', { name: 'Save password and continue' }).click();
    user.password = next;
  }
  await finishSignIn(page, user);
}

test.describe('smoke caregiver access', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahFully(api);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      await restoreSarahFully(api).catch(() => {});
      // Remove grants to throwaway smoke caregivers left by an aborted run.
      const grants = await api.thomas.get<any[]>('/api/patients/me/caregiver-access');
      for (const g of (grants.body ?? []) as any[]) {
        if (String(g.caregiverEmail ?? '').startsWith('e2e-smoke-')) await api.thomas.del(`/api/patients/me/caregiver-access/${g.accessId}`);
      }
    }
    await disposeAll(api);
  });

  test('patient grants access to a new caregiver, who then sees the patient; revoking removes it', async ({ browser }) => {
    const carer = await registerSmokeCaregiver();
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;

    await visit(page, find(PATIENT_SECTIONS, 'My Proxies (Caregivers)'));
    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await page.getByLabel('Caregiver email').fill(carer.email);
    await page.getByLabel('Relationship').fill('Neighbour');
    // Access level radios are named "<level>. <what it allows>".
    await page.getByRole('radio', { name: /^View only\./ }).click();
    const granted = page.waitForResponse(r => r.url().endsWith('/api/patients/me/caregiver-access') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Grant access', exact: true }).click();
    expect((await granted).status(), 'grant accepted').toBeLessThan(300);
    await expect(page.getByText(/^Access granted to /)).toBeVisible();
    await expect(page.getByRole('button', { name: `Remove access, ${carer.name}` })).toBeVisible();
    monitor.expectClean('grant');

    // The new caregiver signs in and sees Thomas among their loved ones.
    const cg = await signedInPage(browser, carer);
    pages.push(cg);
    await expect(cg.page.getByRole('button', { name: /Thomas Dube/ }).first()).toBeVisible();
    await expect(cg.page.getByText(/^View only$/i).first()).toBeVisible();
    await apiIdle(cg.page);
    await expectNoArtifacts(cg.page, 'new caregiver dashboard');
    cg.monitor.expectClean('new caregiver dashboard');

    // Thomas revokes it from the UI (confirm dialog accepted).
    const revoked = page.waitForResponse(r => /\/api\/patients\/me\/caregiver-access\/\d+$/.test(r.url()) && r.request().method() === 'DELETE');
    await page.getByRole('button', { name: `Remove access, ${carer.name}` }).click();
    expect((await revoked).status(), 'revoke accepted').toBeLessThan(300);
    await expect(page.getByText(/^Access removed for /)).toBeVisible();
    await expect(page.getByRole('button', { name: `Remove access, ${carer.name}` })).toHaveCount(0);
    const grants = await api.thomas.ok(api.thomas.get<any[]>('/api/patients/me/caregiver-access'), 'grants');
    expect(grants.some(g => g.caregiverEmail === carer.email), 'grant gone on the server').toBe(false);
    monitor.expectClean('revoke');

    // The caregiver no longer sees Thomas after a reload.
    await cg.page.reload();
    await expect(cg.page.getByText('No linked patients yet.').first()).toBeVisible();
    await expect(cg.page.getByRole('button', { name: /Thomas Dube/ })).toHaveCount(0);
    cg.monitor.expectClean('new caregiver after revoke');
  });

  test('patient changes Sarah\'s messaging and access level; Sarah sees each change', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    const sarah = await signedInPage(browser, SARAH);
    pages.push(patient, sarah);
    const { page, monitor } = patient;

    await visit(page, find(PATIENT_SECTIONS, 'My Proxies (Caregivers)'));
    // Sarah's card: the innermost box with her email and the consent switches (stable in edit mode too).
    const card = page.locator('div').filter({ has: page.getByText(SARAH.email, { exact: true }) })
      .filter({ has: page.getByRole('switch', { name: 'Message my care team' }) }).last();
    const messaging = card.getByRole('switch', { name: 'Message my care team' });
    await expect(messaging).toHaveAttribute('aria-checked', 'true');

    await test.step('turn messaging off: Sarah cannot start a conversation about Thomas', async () => {
      await messaging.click();
      await expect(messaging).toHaveAttribute('aria-checked', 'false');
      await expect.poll(async () => (await sarahGrant(api.thomas)).messagingAllowed).toBe(false);

      await visit(sarah.page, find(PROXY_SECTIONS, 'Messages'));
      await sarah.page.getByRole('button', { name: 'New conversation', exact: true }).first().click();
      const thomas = sarah.page.getByRole('radio', { name: /^Thomas Dube\. (Messaging is not allowed for your access|Waiting for Thomas Dube to approve messaging)$/ });
      await expect(thomas).toBeVisible();
      await expect(thomas).toBeDisabled();
      sarah.monitor.expectClean('messaging off');
    });

    await test.step('turn messaging back on: Sarah can pick Thomas again', async () => {
      await messaging.click();
      await expect(messaging).toHaveAttribute('aria-checked', 'true');
      await expect.poll(async () => (await sarahGrant(api.thomas)).messagingAllowed).toBe(true);
      await sarah.page.reload();
      await visit(sarah.page, find(PROXY_SECTIONS, 'Messages'));
      await sarah.page.getByRole('button', { name: 'New conversation', exact: true }).first().click();
      const thomas = sarah.page.getByRole('radio', { name: 'Thomas Dube', exact: true });
      await expect(thomas).toBeEnabled();
      await sarah.page.getByRole('button', { name: 'Cancel new conversation' }).click();
      sarah.monitor.expectClean('messaging on');
    });

    await test.step('change the access level to View only, then back to Full access', async () => {
      for (const [level, badge] of [['View only', 'View Only'], ['Full access', 'Full Access']] as const) {
        await card.getByRole('button', { name: `Change permissions, ${SARAH.name}` }).click();
        await card.getByRole('radio', { name: new RegExp(`^${level}\\.`) }).click();
        const saved = page.waitForResponse(r => /\/api\/patients\/me\/caregiver-access\/\d+$/.test(r.url()) && r.request().method() === 'PATCH');
        await card.getByRole('button', { name: 'Save changes', exact: true }).click();
        expect((await saved).status(), `save ${level}`).toBeLessThan(300);
        await expect(card.getByText(level, { exact: true }).first()).toBeVisible();

        // Sarah's linked-patient list shows the new level for Thomas.
        await sarah.page.reload();
        await visit(sarah.page, find(PROXY_SECTIONS, 'Profile & Settings'));
        // Each linked patient is a list item (role="listitem" renders as <li>), so scope to Thomas's item.
        const row = sarah.page.getByRole('listitem').filter({ has: sarah.page.getByText('Thomas Dube', { exact: true }) })
          .filter({ has: sarah.page.getByRole('button', { name: 'Manage access for Thomas Dube' }) });
        await expect(row.getByText(badge, { exact: true }), `Sarah sees ${badge}`).toBeVisible();
      }
      const g = await sarahGrant(api.thomas);
      expect(g.accessLevel).toBe('FULL_ACCESS');
      monitor.expectClean('access level');
      sarah.monitor.expectClean('access level');
    });
  });

  test('caregiver switches between the people they care for', async ({ browser }) => {
    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    const deps = await api.sarah.ok(api.sarah.get<any[]>('/api/caregiver/dependents'), 'dependents');
    expect(deps.length, 'Sarah cares for more than one person (dev seed)').toBeGreaterThan(1);

    await page.getByRole('button', { name: /^Elias Dube, .*open profile$/ }).click();
    await expect(page.getByRole('heading', { name: 'Patient Profile', exact: true })).toBeVisible();
    await expect(page.getByText('What you can see and do for Elias Dube', { exact: true })).toBeVisible();
    await expect(page.getByText('elias.dube@carenexus.com').first()).toBeVisible();
    await apiIdle(page);
    await expectNoArtifacts(page, 'Elias profile');

    // Thomas is behind "+N more" in the sidebar.
    await page.getByRole('button', { name: /\+\d+ more$/ }).first().click();
    await page.getByRole('button', { name: /^(TD )?Thomas Dube\b/ }).first().click();
    await expect(page.getByText('What you can see and do for Thomas Dube', { exact: true })).toBeVisible();
    await expect(page.getByText('thomas.dube@carenexus.com').first()).toBeVisible();
    await expect(page.getByText('elias.dube@carenexus.com')).toHaveCount(0);
    await apiIdle(page);
    await expectNoArtifacts(page, 'Thomas profile');

    // Back to the dashboard from the profile.
    await page.getByRole('button', { name: 'Back to Dashboard' }).click();
    await expect(page.getByText('Loved One Overview')).toBeVisible();
    await navButton(page, 'Dashboard').click();
    monitor.expectClean('switch dependents');
  });

  // Run in the clinic's own time zone (Harare, UTC+2, the project default) and in UTC: date fields
  // must be accepted in both. Each run is the full flow, so a failure in one zone does not hide the rest.
  for (const timezoneId of ['Africa/Harare', 'UTC']) {
    test(`caregiver creates an account for a new loved one in 3 steps; that person can sign in (${timezoneId})`, async ({ browser }) => {
      // A throwaway caregiver, so Sarah's demo list of loved ones is not changed.
      const carer = await registerSmokeCaregiver();
      const cg = await signedInPage(browser, carer, { timezoneId });
      pages.push(cg);
      const { page, monitor } = cg;
      const depEmail = throwawayEmail(`${smokeTag()}-dep`);

      await page.getByRole('button', { name: 'Add Another Loved One' }).first().click();
      await expect(page.getByRole('heading', { name: 'Add Loved One', exact: true })).toBeVisible();
      await expect(page.getByText('Who are you adding?')).toBeVisible();
      await page.getByRole('button', { name: /^Create a new account\./ }).click();
      await expect(page.getByText('Create an account for someone in your care')).toBeVisible();

      // Step 1: personal details. A temporary password is generated as soon as an email is typed.
      await expect(page.getByText(/^Step 1 of 3: /)).toBeVisible();
      await page.getByLabel('First name').fill('Smoke');
      await page.getByLabel('Last name').fill('Dependent');
      await page.getByLabel('Phone number (optional)').fill(`+26371${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`);
      await page.getByLabel('Email address (optional)').fill(depEmail);
      const passwordBox = page.getByLabel('Temporary password', { exact: true });
      await expect(passwordBox).not.toHaveValue('');
      const depPassword = await passwordBox.inputValue();
      expect(depPassword.length, 'a temporary password is generated').toBeGreaterThanOrEqual(8);
      await page.getByRole('button', { name: 'Continue', exact: true }).click();

      // Step 2: health profile (every field optional).
      await expect(page.getByText(/^Step 2 of 3: /)).toBeVisible();
      await page.getByLabel('Date of birth').fill('1950-03-15');
      await page.getByLabel('Gender').selectOption({ label: 'Female' });
      await page.getByLabel('Blood type').selectOption({ label: 'B+' });
      await page.getByRole('button', { name: 'Continue', exact: true }).click();

      // Step 3: access settings, the guardian confirmation, then submit.
      await expect(page.getByText(/^Step 3 of 3: /)).toBeVisible();
      await expect(page.getByText('Define your proxy relationship and what you are permitted to access')).toBeVisible();
      await page.getByLabel('Your relationship to them').fill('Mother');
      await page.getByLabel('Access level').selectOption({ label: 'View only' });
      await page.getByRole('checkbox', { name: /^I am this person's parent or legal guardian\./ }).click();
      const created = page.waitForResponse(r => r.url().endsWith('/api/caregiver/dependents') && r.request().method() === 'POST');
      await page.getByRole('button', { name: 'Add Loved One', exact: true }).click();
      expect((await created).status(), 'dependent created').toBeLessThan(300);
      await expect(page.getByRole('heading', { name: 'Loved One Added!' })).toBeVisible();
      await expect(page.getByText(depEmail, { exact: true })).toBeVisible();
      await expect(page.getByText(depPassword, { exact: true }), 'password hidden until revealed').toHaveCount(0);
      monitor.expectClean('add loved one');

      await page.getByRole('button', { name: 'Back to Dashboard' }).click();
      await expect(page.getByRole('button', { name: /Smoke Dependent/ }).first(), 'new loved one in the sidebar').toBeVisible();
      await apiIdle(page);
      await expectNoArtifacts(page, 'caregiver dashboard after add');
      monitor.expectClean('dashboard after add');

      // Server side: the caregiver now has this dependent with the chosen access level and health profile.
      const capi = await Api.login(carer);
      try {
        const deps = await capi.ok(capi.get<any[]>('/api/caregiver/dependents'), 'dependents');
        const dep = deps.find(d => d.patientEmail === depEmail);
        expect(dep, 'dependent linked on the server').toBeTruthy();
        expect(dep.accessLevel).toBe('VIEW_ONLY');
        expect(dep.patientBloodType).toBe('B+');
        expect(dep.patientDateOfBirth, 'date of birth stored as entered').toBe('1950-03-15');
      } finally {
        await capi.dispose();
      }

      // The new patient can sign in with the temporary password and sees their own dashboard.
      const ctx = await browser.newContext();
      try {
        const p2 = await ctx.newPage();
        await signInWithTemporaryPassword(p2, { email: depEmail, password: depPassword, role: 'patient', systemId: '', name: 'Smoke Dependent' });
        await expect(p2).toHaveURL(new RegExp(`${ROLE_HOME.patient}$`));
        await expect(p2.getByText('Welcome back, Smoke!')).toBeVisible();
      } finally {
        await ctx.close();
      }
    });
  }

  test('patient grant form accepts an end date (clinic time zone)', async ({ browser }) => {
    const carer = await registerSmokeCaregiver();
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;
    const end = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Harare', year: 'numeric', month: '2-digit', day: '2-digit' })
      .format(new Date(Date.now() + 365 * 86_400_000));
    await visit(page, find(PATIENT_SECTIONS, 'My Proxies (Caregivers)'));
    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await page.getByLabel('Caregiver email').fill(carer.email);
    await page.getByLabel('Relationship').fill('Neighbour');
    await page.getByRole('radio', { name: /^View only\./ }).click();
    await page.getByLabel('Access ends on (optional)').fill(end);
    const granted = page.waitForResponse(r => r.url().endsWith('/api/patients/me/caregiver-access') && r.request().method() === 'POST', { timeout: 10_000 });
    await page.getByRole('button', { name: 'Grant access', exact: true }).click();
    expect((await granted).status(), 'grant with end date accepted').toBeLessThan(300);
    await expect(page.getByText(/^Access granted to /)).toBeVisible();
    const g = (await api.thomas.ok(api.thomas.get<any[]>('/api/patients/me/caregiver-access'), 'grants')).find(x => x.caregiverEmail === carer.email);
    expect(String(g?.expiresAt ?? '').slice(0, 10), 'end date stored').toBe(end);
    monitor.expectClean('grant with end date');
    // afterAll removes grants to e2e-smoke- caregivers.
  });

  test('caregiver notification settings: a switch is saved on click and survives a reload', async ({ browser }) => {
    const carer = await registerSmokeCaregiver();
    const cg = await signedInPage(browser, carer);
    pages.push(cg);
    const { page, monitor } = cg;
    const section = find(PROXY_SECTIONS, 'Notification Settings');
    // An enforced setting: lab result notifications for the loved ones (labResultAlerts).
    const toggle = page.getByRole('switch', { name: 'Lab results', exact: true });

    await visit(page, section);
    await apiIdle(page);
    const before = (await toggle.getAttribute('aria-checked')) === 'true';
    const saved = page.waitForResponse(r => r.url().endsWith('/api/profile/settings') && r.request().method() === 'PATCH');
    await toggle.click();
    const res = await saved;
    expect(res.status(), 'setting saved on click').toBeLessThan(300);
    expect(res.request().postDataJSON(), 'only the changed setting is sent').toEqual({ labResultAlerts: !before });
    await expect(toggle).toHaveAttribute('aria-checked', String(!before));
    await expect(page.getByText('Changes saved', { exact: true })).toBeVisible();
    monitor.expectClean('save setting');

    await page.reload();
    await visit(page, section);
    await apiIdle(page);
    await expect(toggle, 'saved value after reload').toHaveAttribute('aria-checked', String(!before));
    const capi = await Api.login(carer);
    try {
      const settings = await capi.ok(capi.get<any>('/api/profile/settings'), 'settings');
      expect(settings.labResultAlerts, 'labResultAlerts stored on the server').toBe(!before);
      expect(settings, 'retired settings are no longer returned').not.toHaveProperty('weeklySummary');
    } finally {
      await capi.dispose();
    }
    monitor.expectClean('settings after reload');
  });
});
