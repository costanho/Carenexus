import { expect, test } from '@playwright/test';
import { type Clients, callStatus, disposeAll, loginAll } from './support/api';
import { ADMIN, JAMES, ROLE_HOME, SARAH, THOMAS, type DemoUser } from './support/env';
import {
  acceptNextDialog, bookFixVideoAppointment, cancelLeftoverFixAppointments, cleanUpFixAppointment,
  expectCallStatusStays, fixPage, trackApi, trackUrls, type FixPage,
} from './support/fix-platform';
import { login } from './support/ui';

// Platform fixes: the dashboard role gate (administrators included), the register form's accessibility, 401 on a
// wrong password, the device check timeout, and that a call starts / ends only through explicit, confirmed actions.
// The two call tests need Chromium's fake camera and microphone and are tagged @chromium-only.
//
// Tables written: appointments ("e2e-fix-platform video call safety" VIDEO appointments, cancelled in afterAll) with
// their call_sessions, call_participants, call_jobs and provider_events; refresh_tokens and audit_log for every
// sign-in. Nothing for the wrong-password and register checks (the empty register form sends nothing).

const LOGIN_PATH    = '/auth/login/web/login-web';
const REGISTER_PATH = '/auth/register/web/register-web';

test.describe.configure({ mode: 'serial' });

test.describe('fix-platform', () => {
  let api: Clients;
  const pages: FixPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await cancelLeftoverFixAppointments(api.james);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) await cancelLeftoverFixAppointments(api.james).catch(() => {});
    await disposeAll(api);
  });

  // ── Dashboard role gate ────────────────────────────────────────────────────

  const otherRoleCases: [DemoUser, string][] = [
    [THOMAS, ROLE_HOME.doctor],
    [JAMES, ROLE_HOME.patient],
    [SARAH, ROLE_HOME.doctor],
    [THOMAS, ROLE_HOME.admin],
    [ADMIN, ROLE_HOME.doctor],
    [ADMIN, ROLE_HOME.patient],
  ];

  for (const [user, target] of otherRoleCases) {
    test(`${user.role} opening ${target} lands on their own dashboard without mounting it`, async ({ browser }) => {
      const p = await fixPage(browser, user);
      pages.push(p);
      const urls = trackUrls(p.page);
      const calls = trackApi(p.page);
      const statuses: number[] = [];
      p.page.on('response', r => { if (new URL(r.url()).pathname.startsWith('/api/')) statuses.push(r.status()); });

      await p.page.goto(target);
      await expect(p.page).toHaveURL(new RegExp(`${ROLE_HOME[user.role]}$`), { timeout: 20_000 });
      await p.page.waitForLoadState('networkidle');

      expect(urls.filter(u => u.startsWith('/dashboard/call')), 'never passes through the call route').toEqual([]);
      if (target === ROLE_HOME.doctor) {
        // The bookable-doctor directory and public doctor profiles are open to every role (the patient and caregiver
        // screens list doctors too); any other /api/doctors/* call comes from the doctor dashboard.
        const shared = ['/api/doctors/profile/list/active', '/api/doctors/profile/directory', '/api/doctors/profile/public/'];
        const doctorOnly = calls.filter(c => c.includes('/api/doctors/') && !shared.some(p => c.includes(p)));
        expect(doctorOnly, 'the doctor dashboard never mounted').toEqual([]);
      }
      if (target === ROLE_HOME.admin) {
        expect(calls.filter(c => c.includes('/api/admin/')), 'the administration dashboard never mounted').toEqual([]);
      }
      // The other role's screens would call its APIs with this user's token (403)
      expect(statuses.filter(s => s === 403), 'no 403 responses').toEqual([]);
    });
  }

  test('a call link without a valid id shows "Call not available" and goes back to the own dashboard', async ({ browser }) => {
    const p = await fixPage(browser, THOMAS);
    pages.push(p);
    const calls = trackApi(p.page);

    await p.page.goto('/dashboard/call/undefined');
    await expect(p.page.getByRole('heading', { name: 'Call not available' })).toBeVisible();
    await p.page.getByRole('button', { name: 'Back to dashboard' }).click();
    await expect(p.page).toHaveURL(new RegExp(`${ROLE_HOME.patient}$`));
    expect(calls.filter(c => c.includes('/undefined')), 'no API call with an undefined id').toEqual([]);
  });

  // ── Auth ───────────────────────────────────────────────────────────────────

  test('wrong password: 401 and a generic message', async ({ browser }) => {
    const p = await fixPage(browser, null);
    pages.push(p);
    await p.page.goto(LOGIN_PATH);
    await p.page.getByRole('radio', { name: 'Patient', exact: true }).click();
    await p.page.getByLabel('Email address').fill(THOMAS.email);
    await p.page.getByLabel('Password', { exact: true }).fill(`${THOMAS.password}-e2e-fix-wrong`);
    const res = p.page.waitForResponse(r => r.url().endsWith('/api/auth/login'));
    await p.page.getByRole('button', { name: 'Sign in', exact: true }).click();
    expect((await res).status()).toBe(401);
    await expect(p.page.getByText('Invalid email or password', { exact: true })).toBeVisible();
    await expect(p.page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
  });

  test('register form: labelled fields, role radios, terms checkbox, Enter submits', async ({ browser }) => {
    const p = await fixPage(browser, null);
    pages.push(p);
    let posted = false;
    p.page.on('request', r => { if (r.url().endsWith('/api/auth/register')) posted = true; });
    await p.page.goto(REGISTER_PATH);
    await expect(p.page.getByRole('heading', { name: 'Create your account' })).toBeVisible();

    for (const label of ['First Name', 'Last Name', 'Email Address', 'Password', 'Confirm Password']) {
      await expect(p.page.getByLabel(label, { exact: true }), label).toHaveCount(1);
    }

    const patient = p.page.getByRole('radio', { name: /^Patient\b/ });
    const proxy   = p.page.getByRole('radio', { name: /^Proxy \/ Carer\b/ });
    await expect(patient).toHaveAttribute('aria-checked', 'true');
    await expect(proxy).toHaveAttribute('aria-checked', 'false');
    await proxy.click();
    await expect(proxy).toHaveAttribute('aria-checked', 'true');
    await expect(patient).toHaveAttribute('aria-checked', 'false');

    const terms = p.page.getByRole('checkbox', { name: /I agree to the Terms of Service/ });
    await expect(terms).toHaveAttribute('aria-checked', 'false');
    await terms.click();
    await expect(terms).toHaveAttribute('aria-checked', 'true');
    await terms.click();
    await expect(terms).toHaveAttribute('aria-checked', 'false');

    // Enter in a field submits: the empty form is validated (and nothing is sent)
    await p.page.getByLabel('Confirm Password', { exact: true }).press('Enter');
    await expect(p.page.getByText('First name is required', { exact: true })).toBeVisible();
    await expect(p.page.getByText('You must accept the terms to continue', { exact: true })).toBeVisible();
    expect(posted).toBe(false);
  });

  // ── Calls ──────────────────────────────────────────────────────────────────

  test('a microphone that never answers shows a recovery state after the timeout @chromium-only', async ({ browser }) => {
    const id = await bookFixVideoAppointment(api.james);
    const p = await fixPage(browser, null);
    pages.push(p);
    // The browser never answers microphone requests (a prompt hidden behind another window behaves the same).
    await p.context.addInitScript(() => {
      const md = navigator.mediaDevices;
      if (!md?.getUserMedia) return;
      const real = md.getUserMedia.bind(md);
      md.getUserMedia = (c?: MediaStreamConstraints) => (c && c.audio ? new Promise<MediaStream>(() => {}) : real(c));
    });
    await login(p.page, JAMES);

    await p.page.goto(`/dashboard/call/${id}`);
    await expect(p.page.getByRole('heading', { name: 'Camera and microphone' })).toBeVisible();
    await expect(p.page.getByText('Could not access the microphone')).toBeVisible({ timeout: 20_000 });
    await expect(p.page.getByRole('button', { name: 'Check again: microphone' })).toBeVisible();
    await p.page.getByRole('button', { name: 'Join without microphone' }).click();
    await expect(p.page.getByText('Could not access the microphone')).toHaveCount(0);
    await expect(p.page.getByText('Turned off')).toBeVisible();

    // Nothing in the device check started the call
    expect((await callStatus(api.james, id)).body.callStatus).toBe('READY');
    await cleanUpFixAppointment(api.james, id);
  });

  test('the call starts and ends only on explicit, confirmed actions; Leave never ends it @chromium-only', async ({ browser }) => {
    const id = await bookFixVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;
    const doctor = await fixPage(browser, JAMES);
    pages.push(doctor);
    const calls = trackApi(doctor.page);

    // 1. Lobby: wait through several status polls and press keys; nothing starts the call.
    await doctor.page.goto(route);
    await expect(doctor.page.getByRole('heading', { name: 'Video consultation' })).toBeVisible();
    await expect(doctor.page.getByRole('button', { name: 'Start call', exact: true })).toBeVisible();
    await doctor.page.keyboard.press('Enter');
    await doctor.page.keyboard.press('Space');
    await expectCallStatusStays(api.james, id, 'READY', 8_000);
    await doctor.page.getByRole('button', { name: 'Back to dashboard' }).first().click();
    await expect(doctor.page).toHaveURL(new RegExp(`${ROLE_HOME.doctor}$`));
    await expectCallStatusStays(api.james, id, 'READY', 3_000);
    expect(calls.filter(c => /\/call\/(start|end)$/.test(c)), 'no start/end request from the lobby').toEqual([]);

    // 2. Camera turned off in the device check stays off in the call.
    await doctor.page.goto(route);
    await expect(doctor.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await doctor.page.getByRole('switch', { name: 'Camera on' }).click();
    await expect(doctor.page.getByRole('switch', { name: 'Join without camera' })).toBeVisible();
    await doctor.page.getByRole('button', { name: 'Start call', exact: true }).click();
    await expect(doctor.page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible({ timeout: 45_000 });
    await expect(doctor.page.getByRole('button', { name: 'Start camera' })).toBeVisible({ timeout: 20_000 });
    expect((await callStatus(api.james, id)).body.callStatus).toBe('LIVE');

    // 3. Leave keeps the call LIVE for the doctor too.
    await doctor.page.getByRole('button', { name: 'Leave', exact: true }).click();
    await expect(doctor.page).toHaveURL(new RegExp(`${ROLE_HOME.doctor}$`));
    await expectCallStatusStays(api.james, id, 'LIVE', 5_000);
    expect(calls.filter(c => c.endsWith('/call/end')), 'Leave never calls /end').toEqual([]);

    // 4. Back in the lobby the camera choice is remembered and waiting does not rejoin or end anything.
    await doctor.page.goto(route);
    await expect(doctor.page.getByRole('switch', { name: 'Join without camera' })).toBeVisible();
    await expect(doctor.page.getByRole('button', { name: 'Rejoin call', exact: true })).toBeVisible();
    await expectCallStatusStays(api.james, id, 'LIVE', 5_000);

    // 5. End call needs the confirmation: dismissing it keeps the call LIVE, accepting ends it.
    await doctor.page.getByRole('button', { name: 'Rejoin call', exact: true }).click();
    await expect(doctor.page.getByRole('button', { name: 'End call', exact: true })).toBeEnabled({ timeout: 45_000 });
    await doctor.page.getByRole('button', { name: 'End call', exact: true }).click();
    await expect.poll(() => doctor.dialogs.length).toBe(1);
    expect(doctor.dialogs[0]).toContain('End call for everyone?');
    await expectCallStatusStays(api.james, id, 'LIVE', 3_000);

    acceptNextDialog(doctor.page);
    await doctor.page.getByRole('button', { name: 'End call', exact: true }).click();
    await expect(doctor.page.getByRole('heading', { name: 'The call has ended' })).toBeVisible({ timeout: 20_000 });
    expect((await callStatus(api.james, id)).body.callStatus).toBe('ENDED');

    await cleanUpFixAppointment(api.james, id);
  });
});
