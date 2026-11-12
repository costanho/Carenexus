import { expect, type Page, test } from '@playwright/test';
import { registerAccount } from './support/accounts';
import {
  Api, type Clients, admitByLabel, cancelLeftoverE2EAppointments, clientId, createLiveableVideoAppointment, disposeAll, loginAll,
  markThreadRead, messagesWithBody, privateThreadThomasJames, restoreSarahConsent,
} from './support/api';
import { ADMIN, type DemoUser, JAMES, LABELS, type LoginRole, SARAH, THOMAS, runTag, throwawayEmail } from './support/env';
import {
  ADMIN_DEV_SECTIONS, ADMIN_SECTIONS, DOCTOR_SECTIONS, HIDDEN_SECTIONS, PATIENT_SECTIONS, PROXY_SECTIONS, type Section,
  apiIdle, navButton, pageOverflow, smokeTag,
} from './support/smoke';
import { bell } from './support/fix-messaging';
import {
  ACCEPT_TERMS_PATH, CHANGE_PASSWORD_PATH, escapeRe, finishSignIn, horizontalOverflow, newUserPage, openMessages,
  openSection, openSectionAt, sectionUrl, seriousA11yViolations, submitLogin, type SectionParamsInput, type UserPage,
} from './support/ui';

// Accessibility: axe (WCAG 2.1 A/AA, serious + critical, with the visible-text-in-accessible-name rule
// label-content-name-mismatch turned on by support/ui.ts for every screen) and the 375 px horizontal-overflow check on:
//   - the sign-in page, its second step (the legal interstitial), the registration confirmation, the
//     forgot-password request and confirmation, the reset-password page (form and invalid link), the verify-email
//     and confirm-email-change pages (invalid link), the change-password page of a signed-in account, and the public
//     /legal/terms and /legal/privacy pages (the web-a11y-shared scope of docs/ui-ux/A11Y-PASS.md);
//   - every section of all four roles from the registry lists in support/smoke.ts, the tab variants and hidden
//     sections (opened by `?section=` URL), at desktop width and at 375 px, with the phone drawer open, and with
//     the top-bar notification bell's dialog open;
//   - the on-demand states of the doctor screens (expanded appointment card, reschedule panel, prescription panel
//     and dialogs, the patient panel on every tab, record / result / referral panels, the invoice form, the ICD-10
//     suggestions) and of the caregiver screens (booking, record panel, profile editors, add-dependent modes);
//   - the messages screens, the call lobby, waiting area and in-call view, and the patient's caregiver access screen;
// plus keyboard-only sign in and keyboard-only send.
//
// Tables written: users, patients, legal_acceptances, email_verification_tokens and outbound_messages (throwaway
// e2e-…@carenexus.test accounts and their emails); password_reset_tokens is not written (the reset is asked for an
// address with no account, and the reset, verify-email and confirm-email-change pages are opened with no token or
// with one that is never submitted); message_threads, thread_members, messages and notifications (an e2e-a11y care team
// conversation and keyboard-sent messages in the Thomas / Dr. Banda private thread); appointments, call_sessions,
// call_participants, call_jobs and provider_events (the live video appointment, cancelled in afterAll);
// refresh_tokens and audit_log for every sign-in. Opening the on-demand states never saves anything.

async function expectNoSeriousViolations(page: Page, screen: string) {
  const violations = await seriousA11yViolations(page);
  expect.soft(violations, `${screen}: serious/critical axe violations`).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page, screen: string) {
  expect.soft(await horizontalOverflow(page), `${screen}: horizontal overflow at 375 px`).toEqual([]);
}

/** Page-level overflow: content past the right edge that is not inside a scrollable strip. */
async function expectNoPageOverflow(page: Page, screen: string) {
  expect.soft(await pageOverflow(page), `${screen}: content past the right edge at 375 px`).toEqual([]);
}

/** Presses Tab until `target` has focus (bounded), so the test proves it is reachable by keyboard. */
async function tabTo(page: Page, isTarget: () => Promise<boolean>, what: string, max = 80) {
  for (let i = 0; i < max; i++) {
    if (await isTarget()) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`${what} is not reachable with the Tab key`);
}

const focusedLabel = (page: Page) => page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '');

const PHONE = { width: 375, height: 812 };
const DESKTOP = { width: 1280, height: 800 };

// ── Screens of each role ────────────────────────────────────────────────────

/** One screen (or on-demand state) of a role, opened by its section URL. */
interface Screen {
  /** What the failure message calls it. */
  what:    string;
  key:     string;
  /** Name of the section's main landmark (its registry label). */
  label:   string;
  params?: SectionParamsInput;
  /**
   * Opens an on-demand state after the section has loaded (a panel, a dialog, a suggestions list). Returns false when
   * the dev seed offers nothing to open it on; the state is then recorded as an annotation instead of checked.
   */
  open?:   (page: Page) => Promise<boolean>;
}

function screenOf(s: Section, params?: SectionParamsInput, what?: string): Screen {
  const label = s.key === 'join-consultation' ? 'My Appointments' : s.nav;
  const suffix = params ? ` ${Object.entries(params).map(([k, v]) => `${k}=${v}`).join('&')}` : '';
  return { what: what ?? `${s.nav}${suffix}`, key: s.key!, label, params };
}

const byKey = (list: Section[], key: string) => list.find(s => s.key === key)!;
const hiddenSection = (role: LoginRole, key: string) => HIDDEN_SECTIONS[role].find(s => s.key === key)!;

/** Clicks the first control matching `name` when there is one; false when the seed offers none. */
async function clickFirst(page: Page, role: 'button' | 'tab', name: RegExp | string): Promise<boolean> {
  const control = page.getByRole(role, { name }).first();
  if (!(await control.isVisible().catch(() => false))) {
    await control.waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
  }
  if (!(await control.isVisible().catch(() => false))) return false;
  await control.click();
  return true;
}

/** Waits for the screen's loading states and requests to settle. */
async function settle(page: Page) {
  await expect(page.getByText(/^Loading\b.*(…|\.\.\.)$/)).toHaveCount(0, { timeout: 20_000 });
  await apiIdle(page);
}

const PATIENT_SCREENS: Screen[] = [
  ...PATIENT_SECTIONS.filter(s => s.key !== 'join-consultation').map(s => screenOf(s)),
  screenOf(byKey(PATIENT_SECTIONS, 'prescriptions'), { tab: 'schedule' }),
  screenOf(byKey(PATIENT_SECTIONS, 'prescriptions'), { tab: 'reminders' }),
  screenOf(byKey(PATIENT_SECTIONS, 'health-tracking'), { tab: 'overview' }),
  screenOf(byKey(PATIENT_SECTIONS, 'health-tracking'), { tab: 'readings' }),
  screenOf(byKey(PATIENT_SECTIONS, 'health-tracking'), { tab: 'symptoms' }),
  screenOf(byKey(PATIENT_SECTIONS, 'medical-records'), { tab: 'corrections' }),
  screenOf(byKey(PATIENT_SECTIONS, 'profile-settings'), { tab: 'security' }),
];

const DOCTOR_PATIENT_TABS = ['overview', 'history', 'care-team', 'vitals', 'alerts', 'care-plans', 'documents', 'referrals', 'reminders'];

const DOCTOR_SCREENS: Screen[] = [
  ...DOCTOR_SECTIONS.map(s => screenOf(s)),
  screenOf(byKey(DOCTOR_SECTIONS, 'profile'), { tab: 'security' }),
  screenOf(byKey(DOCTOR_SECTIONS, 'medical-records'), { tab: 'corrections' }),
  // The patient panel on every tab.
  ...DOCTOR_PATIENT_TABS.map(tab => screenOf(byKey(DOCTOR_SECTIONS, 'patients'), { patient: THOMAS.systemId, tab }, `Patients: patient panel, ${tab} tab`)),
  // On-demand states.
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'appointments'), { patient: THOMAS.systemId }, 'Appointments: expanded appointment card'),
    open: async page => {
      if (!(await clickFirst(page, 'button', /show appointment details$/i))) return false;
      await expect(page.getByRole('button', { name: /hide appointment details$/i }).first()).toBeVisible();
      return true;
    },
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'appointments'), { patient: THOMAS.systemId }, 'Appointments: reschedule panel'),
    open: async page => {
      if (!(await clickFirst(page, 'button', /^Reschedule appointment with /))) return false;
      await expect(page.getByRole('heading', { name: 'Choose a new date and time' }).first()).toBeVisible();
      return true;
    },
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'prescriptions'), undefined, 'Prescriptions: New prescription panel'),
    open: page => clickFirst(page, 'button', 'New prescription'),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'prescriptions'), undefined, 'Prescriptions: Edit dialog'),
    open: page => clickFirst(page, 'button', /^Edit, /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'prescriptions'), undefined, 'Prescriptions: Edit schedule times dialog'),
    open: page => clickFirst(page, 'button', /^Edit schedule times, /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'prescriptions'), undefined, 'Prescriptions: status dialog'),
    open: page => clickFirst(page, 'button', /^(Hold|Resume|Complete|Cancel prescription), /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'medical-records'), undefined, 'Medical Records: record panel'),
    open: page => clickFirst(page, 'button', /^View full record: /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'medical-records'), undefined, 'Medical Records: ICD-10 suggestions'),
    open: async page => {
      if (!(await clickFirst(page, 'button', 'New record'))) return false;
      const icd = page.getByLabel(/^ICD-10 codes/).first();
      await icd.fill('hypertension');
      await expect(page.getByRole('listbox', { name: 'ICD-10 suggestions' })).toBeVisible({ timeout: 15_000 });
      return true;
    },
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'labs'), undefined, 'Labs & Results: result panel'),
    open: page => clickFirst(page, 'button', /^View details of /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'imaging'), undefined, 'Imaging: result panel'),
    open: page => clickFirst(page, 'button', /^View details of /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'referrals'), undefined, 'Referrals: referral panel'),
    open: page => clickFirst(page, 'button', /^View details of the referral for /),
  },
  {
    ...screenOf(byKey(DOCTOR_SECTIONS, 'billing'), undefined, 'Billing: invoice form'),
    open: page => clickFirst(page, 'button', /^(Create invoice for|Edit invoice) /),
  },
];

const PROXY_SCREENS: Screen[] = [
  ...PROXY_SECTIONS.map(s => screenOf(s)),
  screenOf(byKey(PROXY_SECTIONS, 'appointments'), { mode: 'book' }),
  screenOf(byKey(PROXY_SECTIONS, 'medications'), { tab: 'schedule' }),
  screenOf(byKey(PROXY_SECTIONS, 'medications'), { tab: 'reminders' }),
  ...['labs', 'imaging', 'records', 'consultations', 'documents', 'referrals', 'corrections']
    .map(tab => screenOf(byKey(PROXY_SECTIONS, 'documents-results'), { tab })),
  screenOf(byKey(PROXY_SECTIONS, 'profile-settings'), { tab: 'security' }),
  screenOf(hiddenSection('proxy', 'add-dependent')),
  ...['link', 'create', 'requests'].map(mode => screenOf(hiddenSection('proxy', 'add-dependent'), { mode })),
  screenOf(hiddenSection('proxy', 'patient-profile'), { patient: THOMAS.systemId }),
  screenOf(hiddenSection('proxy', 'access-requests')),
  // On-demand states.
  {
    ...screenOf(byKey(PROXY_SECTIONS, 'documents-results'), { tab: 'labs' }, 'Documents & Results: record panel'),
    open: page => clickFirst(page, 'button', /^View details, /),
  },
  ...([
    ['personal details editor', /^Edit personal details/],
    ['health profile editor', /^Edit health profile/],
    ['access editor', /^Reduce access/],
  ] as const).map(([what, name]): Screen => ({
    ...screenOf(hiddenSection('proxy', 'patient-profile'), { patient: THOMAS.systemId }, `Patient Profile: ${what}`),
    open: page => clickFirst(page, 'button', name),
  })),
];

function adminScreens(adminId: string, devOutbox: boolean): Screen[] {
  return [
    ...ADMIN_SECTIONS.map(s => screenOf(s)),
    ...(devOutbox ? ADMIN_DEV_SECTIONS.map(s => screenOf(s)) : []),
    screenOf(hiddenSection('admin', 'user-detail'), { id: adminId }),
  ];
}

/** Opens one screen by URL, then its on-demand state; returns false when the state could not be opened. */
async function openScreen(page: Page, role: LoginRole, s: Screen): Promise<boolean> {
  await openSectionAt(page, role, s.key, s.params ?? {}, s.label);
  await settle(page);
  if (!s.open) return true;
  const shown = await s.open(page);
  if (!shown) {
    test.info().annotations.push({ type: 'not checked', description: `${role}: ${s.what} (nothing in the dev seed to open it on)` });
    return false;
  }
  await settle(page);
  return true;
}

/** At phone width the sidebar is a drawer behind "Open menu" (where the role has one). */
async function openDrawer(page: Page): Promise<boolean> {
  const menu = page.getByRole('button', { name: 'Open menu' });
  if (!(await menu.count())) return false;
  await menu.first().click();
  await expect(page.getByRole('button', { name: 'Close menu' }).first()).toBeVisible();
  return true;
}

async function closeDrawer(page: Page): Promise<void> {
  const close = page.getByRole('button', { name: 'Close menu' }).first();
  if (await close.isVisible().catch(() => false)) await close.click();
}

/**
 * Opens the top-bar notification bell over the role's home and checks the open dialog (axe on the page with the
 * dialog open; at 375 px also the overflow check), then closes it with Escape.
 */
async function checkBellDialog(page: Page, role: LoginRole, phone: boolean): Promise<void> {
  const width = phone ? ' (375 px)' : '';
  await page.goto(sectionUrl(role));
  await settle(page);
  const closed = bell(page);
  await expect(closed).toBeVisible({ timeout: 20_000 });
  await closed.click();
  const dialog = page.getByRole('dialog', { name: 'Notifications' });
  await expect(dialog).toBeVisible();
  await expect(bell(page, true)).toBeVisible();
  await settle(page);
  if (phone) await expectNoPageOverflow(page, `${role}: notification bell dialog`);
  await expectNoSeriousViolations(page, `${role}: notification bell dialog${width}`);
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
}

/** Drops the page's session (cookies and the tab's stored session) so the next page opens signed out. */
async function signOutPage(page: Page): Promise<void> {
  await page.context().clearCookies();
  await page.evaluate(() => { sessionStorage.clear(); });
}

/** A public account page (outside the dashboard): waits for its level-1 heading and settles it, then checks it. */
async function checkAccountPage(page: Page, path: string, heading: string | RegExp, screen: string, phone: boolean) {
  await page.goto(path);
  await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible({ timeout: 20_000 });
  await settle(page);
  if (phone) await expectNoPageOverflow(page, screen);
  await expectNoSeriousViolations(page, `${screen}${phone ? ' (375 px)' : ''}`);
}

test.describe('accessibility', () => {
  let api: Clients;
  const pages: UserPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) await cancelLeftoverE2EAppointments(api.james).catch(() => {});
    await disposeAll(api);
  });

  test('sign-in: axe, selected role announced, keyboard-only sign in', async ({ browser }) => {
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      await page.goto('/auth/login/web/login-web');
      await expect(page.getByRole('radio', { name: 'Patient', exact: true })).toHaveAttribute('aria-checked', 'true');
      await expectNoSeriousViolations(page, 'sign in');

      await page.locator('body').click({ position: { x: 1, y: 1 } });
      await tabTo(page, async () => (await focusedLabel(page)) === 'Doctor', 'the Doctor role');
      // react-native-web activates role="radio" pressables with Enter only (Space works for
      // button roles only); Enter is what keyboard users can rely on here.
      await page.keyboard.press('Enter');
      await expect(page.getByRole('radio', { name: 'Doctor', exact: true })).toHaveAttribute('aria-checked', 'true');
      await expect(page.getByRole('radio', { name: 'Patient', exact: true })).toHaveAttribute('aria-checked', 'false');
      await tabTo(page, async () => (await focusedLabel(page)) === 'Email address', 'the email box');
      await page.keyboard.type(JAMES.email);
      await tabTo(page, async () => (await focusedLabel(page)) === 'Password', 'the password box');
      await page.keyboard.type(JAMES.password);
      await page.keyboard.press('Enter'); // Enter in the password box signs in
      await page.waitForURL('**/dashboard/doctor', { timeout: 30_000 });
    } finally {
      await context.close();
    }
  });

  test('account pages (sign-in second step, registration, password reset, email links, change password, legal): axe and 375 px', async ({ browser }) => {
    test.setTimeout(5 * 60_000);
    for (const viewport of [DESKTOP, PHONE]) {
      const width = viewport.width === PHONE.width ? ' (375 px)' : '';
      const context = await browser.newContext({ viewport });
      try {
        const page = await context.newPage();
        page.on('dialog', d => { d.accept().catch(() => {}); });

        await test.step(`legal interstitial after sign-in${width}`, async () => {
          // A throwaway patient registered without accepting the legal documents is sent to /auth/accept-terms.
          const user = await registerAccount({ role: 'patient', acceptLegal: false });
          await submitLogin(page, user);
          await page.waitForURL(u => u.pathname === ACCEPT_TERMS_PATH, { timeout: 30_000 });
          await expect(page.getByRole('button', { name: /^Accept / }).first()).toBeVisible({ timeout: 20_000 });
          if (width) await expectNoPageOverflow(page, 'accept terms');
          await expectNoSeriousViolations(page, `accept terms${width}`);
        });

        await test.step(`registration confirmation${width}`, async () => {
          await signOutPage(page);
          await page.goto('/auth/register/web/register-web');
          await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
          const field = (placeholder: string) => page.getByPlaceholder(placeholder, { exact: true }).filter({ visible: true });
          const password = `A11y-${crypto.randomUUID().slice(0, 8)}9`;
          await field('John').fill('A11y');
          await field('Smith').fill('Patient');
          await field('you@example.com').fill(throwawayEmail(smokeTag()));
          await field('Phone number').fill(`77${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`);
          await field('Min. 8 characters').fill(password);
          await field('Re-enter your password').fill(password);
          await page.getByRole('checkbox', { name: /I agree to the Terms of Service/ }).click();
          await page.getByRole('button', { name: 'Create Account', exact: true }).click();
          await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible({ timeout: 20_000 });
          if (width) await expectNoPageOverflow(page, 'registration confirmation');
          await expectNoSeriousViolations(page, `registration confirmation${width}`);
        });

        await test.step(`password reset request and confirmation${width}`, async () => {
          await page.goto('/auth/forgot-password');
          const email = page.getByLabel('Email address').filter({ visible: true }).first();
          await expect(email).toBeVisible();
          await expectNoSeriousViolations(page, `forgot password${width}`);
          // An address with no account: the answer is the same, and no reset token is created.
          await email.fill(throwawayEmail(`e2e-a11y-${Date.now().toString(36)}`));
          await page.getByRole('button', { name: 'Send reset link' }).click();
          await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible({ timeout: 20_000 });
          if (width) await expectNoPageOverflow(page, 'password reset confirmation');
          await expectNoSeriousViolations(page, `password reset confirmation${width}`);
        });

        const phone = Boolean(width);

        await test.step(`reset password: invalid link and form${width}`, async () => {
          // No token: the invalid-link state, no request is sent.
          await checkAccountPage(page, '/auth/reset-password', 'This link is invalid or has expired', 'reset password (invalid link)', phone);
          // A token that is never submitted: the form (the server is asked only on Save).
          await checkAccountPage(page, `/auth/reset-password?token=e2e-a11y-${Date.now().toString(36)}`, 'Set a new password',
            'reset password (form)', phone);
          await expect(page.getByLabel('New password', { exact: true }).first()).toBeVisible();
        });

        await test.step(`verify email and confirm email change: invalid link${width}`, async () => {
          await checkAccountPage(page, '/auth/verify-email', 'This verification link is invalid or has expired',
            'verify email (invalid link)', phone);
          await expect(page.getByRole('button', { name: 'Send a new link' })).toBeVisible();
          await checkAccountPage(page, '/auth/confirm-email-change', 'This confirmation link is invalid or has expired',
            'confirm email change (invalid link)', phone);
        });

        await test.step(`legal documents${width}`, async () => {
          for (const [path, title] of [['/legal/terms', /Terms/], ['/legal/privacy', /Privacy/]] as const) {
            await page.goto(path);
            await settle(page);
            await expect(page.getByText('We could not load this document. Please try again.')).toHaveCount(0);
            await expect(page.getByRole('heading', { name: title, level: 1 })).toBeVisible({ timeout: 20_000 });
            if (phone) await expectNoPageOverflow(page, path);
            await expectNoSeriousViolations(page, `${path}${width}`);
          }
        });

        await test.step(`change password, signed in${width}`, async () => {
          const user = await registerAccount({ role: 'patient' });
          await signOutPage(page);
          await submitLogin(page, user);
          await finishSignIn(page, user);
          await page.goto(CHANGE_PASSWORD_PATH);
          await expect(page.getByRole('heading', { name: 'Change your password', level: 1 })).toBeVisible({ timeout: 20_000 });
          await expect(page.getByLabel('Current password', { exact: true }).first()).toBeVisible();
          await settle(page);
          if (phone) await expectNoPageOverflow(page, 'change password');
          await expectNoSeriousViolations(page, `change password${width}`);
        });
      } finally {
        await context.close();
      }
    }
  });

  // ── Every section of every role, desktop and 375 px ───────────────────────

  const ROLE_SCREENS: { user: DemoUser; screens: () => Promise<Screen[]>; drawerEverywhere: boolean }[] = [
    { user: THOMAS, screens: async () => PATIENT_SCREENS, drawerEverywhere: true },
    { user: JAMES,  screens: async () => DOCTOR_SCREENS, drawerEverywhere: false },
    { user: SARAH,  screens: async () => PROXY_SCREENS, drawerEverywhere: false },
    {
      user: ADMIN,
      screens: async () => {
        const admin = await Api.login(ADMIN);
        try {
          // The outbox is listed only where its development endpoint answers (local, docker and test profiles).
          const probe = await admin.get('/api/admin/dev/outbound-messages?page=0&size=1');
          return adminScreens(admin.systemId, probe.status === 200);
        } finally {
          await admin.dispose();
        }
      },
      drawerEverywhere: false,
    },
  ];

  for (const { user, screens, drawerEverywhere } of ROLE_SCREENS) {
    const role = user.role;

    test(`${role}: every section, tab and on-demand state passes axe at desktop width`, async ({ browser }) => {
      test.setTimeout(20 * 60_000);
      const list = await screens();
      const up = await newUserPage(browser, user, { viewport: DESKTOP });
      pages.push(up);
      for (const s of list) {
        await test.step(s.what, async () => {
          if (await openScreen(up.page, role, s)) await expectNoSeriousViolations(up.page, `${role}: ${s.what}`);
        });
      }
      await test.step('notification bell dialog', () => checkBellDialog(up.page, role, false));
    });

    test(`${role}: every section, tab and on-demand state at 375 px: no overflow, axe, phone drawer`, async ({ browser }) => {
      test.setTimeout(20 * 60_000);
      const list = await screens();
      const up = await newUserPage(browser, user, { viewport: PHONE });
      pages.push(up);
      const { page } = up;
      for (const s of list) {
        await test.step(s.what, async () => {
          if (!(await openScreen(page, role, s))) return;
          await expectNoPageOverflow(page, `${role}: ${s.what}`);
          await expectNoSeriousViolations(page, `${role}: ${s.what} (375 px)`);
          // The drawer over a section: the patient's on every section, the other roles' over their home.
          if (!s.open && (drawerEverywhere || s.key === 'dashboard') && await openDrawer(page)) {
            await expectNoPageOverflow(page, `${role}: ${s.what}, drawer open`);
            await expectNoSeriousViolations(page, `${role}: ${s.what} (375 px, drawer open)`);
            await closeDrawer(page);
          }
        });
      }
      await test.step('notification bell dialog', () => checkBellDialog(page, role, true));
    });
  }

  // ── Messages, calls, caregiver access ─────────────────────────────────────

  test('messages screen: axe, keyboard-only send, 375 px', async ({ browser }) => {
    const thread = await privateThreadThomasJames(api.thomas);
    await markThreadRead(api.thomas, thread.threadId);
    const patient = await newUserPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;

    await openMessages(page);
    await expectNoSeriousViolations(page, 'messages (list)');

    // Keyboard only: Tab to the thread, open it with Enter, Tab to the composer, type, Enter sends.
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    const rowName = new RegExp(`^${escapeRe(LABELS.doctor)}, .*, Private, `);
    await tabTo(page, async () => rowName.test(await focusedLabel(page)), 'the private thread row');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();
    await tabTo(page, async () => (await focusedLabel(page)) === 'Message', 'the message box');
    const body = `Sent with the keyboard only ${runTag()}`;
    await page.keyboard.type(body);
    await page.keyboard.press('Enter');
    await expect(page.getByText(body, { exact: true })).toBeVisible();
    await expect.poll(async () => (await messagesWithBody(api.thomas, thread.threadId, body)).length).toBe(1);
    await expect(page.getByLabel('Message', { exact: true })).toHaveValue('');

    // Sending with the Send button from the keyboard keeps focus in the message box (the button
    // turns disabled once the box is empty, which used to drop focus to the page body).
    const body2 = `Sent with the Send button ${runTag()}`;
    await page.keyboard.type(body2);
    await page.keyboard.press('Tab');
    expect(await focusedLabel(page)).toBe('Send message');
    await page.keyboard.press('Enter');
    await expect(page.getByText(body2, { exact: true })).toBeVisible();
    await expect(page.getByLabel('Message', { exact: true })).toBeFocused();

    await expectNoSeriousViolations(page, 'messages (thread open)');

    // 375 px: list, then thread (single pane with a back button).
    await page.setViewportSize(PHONE);
    await page.reload();
    await openMessagesNarrow(page);
    await expectNoHorizontalOverflow(page, 'messages list');
    await page.getByRole('button', { name: rowName }).click();
    await expect(page.getByRole('button', { name: 'Back to conversations' }).first()).toBeVisible();
    await expectNoHorizontalOverflow(page, 'messages thread');
    await expectNoSeriousViolations(page, 'messages (375 px thread)');
  });

  test('doctor and caregiver messages screens: axe and 375 px', async ({ browser }) => {
    // Both inboxes need a conversation to show: the global e2e cleanup leaves none behind, so open
    // the Thomas-James private thread and a care-team thread with Sarah (tagged e2e-, cleaned up).
    await privateThreadThomasJames(api.thomas);
    await api.sarah.ok(api.sarah.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject: `e2e-a11y ${runTag()}`,
      memberSystemIds: [JAMES.systemId], clientThreadId: clientId(),
    }), 'create care team thread for Sarah');
    for (const user of [JAMES, SARAH]) {
      const up = await newUserPage(browser, user);
      pages.push(up);
      await openMessages(up.page);
      await expect(up.page.getByRole('button', { name: /, (Private|Care team)/ }).first()).toBeVisible();
      await expectNoSeriousViolations(up.page, `messages (${user.role})`);
      await up.page.setViewportSize(PHONE);
      await up.page.reload();
      await openMessagesNarrow(up.page);
      await expectNoHorizontalOverflow(up.page, `messages list (${user.role})`);
      await expectNoSeriousViolations(up.page, `messages (${user.role}, 375 px)`);
    }
  });

  // Needs the fake camera and microphone that only the Chromium launch flags provide.
  test('call lobby, waiting area and in-call view: axe and 375 px @chromium-only', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;

    const patient = await newUserPage(browser, THOMAS);
    const caregiver = await newUserPage(browser, SARAH, { viewport: PHONE });
    pages.push(patient, caregiver);

    // Lobby before the doctor starts.
    await patient.page.goto(route);
    await expect(patient.page.getByText('Waiting for the doctor to start the call.')).toBeVisible();
    await expect(patient.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoSeriousViolations(patient.page, 'call lobby');

    await api.james.ok(api.james.post(`/api/appointments/${id}/call/start`), 'start');

    // Caregiver at 375 px: lobby with the join choices, then the waiting area.
    await caregiver.page.goto(route);
    await expect(caregiver.page.getByRole('button', { name: 'Ask to join', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(caregiver.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoHorizontalOverflow(caregiver.page, 'call lobby (caregiver)');
    await expectNoSeriousViolations(caregiver.page, 'call lobby (caregiver, 375 px)');
    await caregiver.page.getByRole('button', { name: 'Ask to join', exact: true }).click();
    await expect(caregiver.page.getByText('Waiting for the doctor to let you in...')).toBeVisible();
    await expectNoHorizontalOverflow(caregiver.page, 'waiting area');
    await expectNoSeriousViolations(caregiver.page, 'waiting area (375 px)');

    // Patient waiting area at desktop width.
    await patient.page.getByRole('button', { name: 'Ask to join', exact: true }).click({ timeout: 20_000 });
    await expect(patient.page.getByText('Waiting for the doctor to let you in...')).toBeVisible();
    await expectNoSeriousViolations(patient.page, 'waiting area');

    // Doctor: lobby (call already live) and in-call view with two people waiting in the admission panel.
    const doctor = await newUserPage(browser, JAMES);
    pages.push(doctor);
    await doctor.page.goto(route);
    await expect(doctor.page.getByRole('button', { name: 'Rejoin call', exact: true })).toBeVisible();
    await expect(doctor.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoSeriousViolations(doctor.page, 'call lobby (doctor)');
    await doctor.page.getByRole('button', { name: 'Rejoin call', exact: true }).click();
    await expect(doctor.page.getByRole('button', { name: `Admit ${LABELS.caregiverInCall}` })).toBeVisible({ timeout: 45_000 });
    await expect(doctor.page.getByRole('button', { name: `Admit ${LABELS.patientInCall}` })).toBeVisible();
    await expect(doctor.page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
    await expectNoSeriousViolations(doctor.page, 'in call (doctor, admission panel)');

    // In call.
    await admitByLabel(api.james, id, LABELS.patientInCall);
    await admitByLabel(api.james, id, LABELS.caregiverInCall);
    for (const up of [patient, caregiver]) {
      await expect(up.page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible({ timeout: 45_000 });
      await expect(up.page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
    }
    await expectNoSeriousViolations(patient.page, 'in call');
    await expectNoHorizontalOverflow(caregiver.page, 'in call');
    await expectNoSeriousViolations(caregiver.page, 'in call (375 px)');

    await api.james.ok(api.james.post(`/api/appointments/${id}/call/end`), 'end');
  });

  test('patient caregiver-access screen: axe and 375 px', async ({ browser }) => {
    const patient = await newUserPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;
    await openSection(page, 'My Proxies (Caregivers)');
    await expect(page.getByRole('heading', { name: 'My caregivers' })).toBeVisible();
    await expect(page.getByRole('button', { name: `Remove access, ${SARAH.name}` })).toBeVisible();
    await expectNoSeriousViolations(page, 'caregiver access');

    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await expect(page.getByLabel('Caregiver email')).toBeVisible();
    await expectNoSeriousViolations(page, 'caregiver access (grant form)');

    await page.setViewportSize(PHONE);
    await page.reload();
    await openSectionNarrow(page, 'My Proxies (Caregivers)');
    await expect(page.getByRole('heading', { name: 'My caregivers' })).toBeVisible();
    await expectNoHorizontalOverflow(page, 'caregiver access');
    await expectNoSeriousViolations(page, 'caregiver access (375 px)');
  });
});

/** At phone width the sidebar sits behind a menu button. */
async function openSectionNarrow(page: Page, label: string) {
  const menu = page.getByRole('button', { name: /^(Open menu|Menu|Open navigation)$/ });
  if (await menu.count()) await menu.first().click();
  await navButton(page, label).click();
}

async function openMessagesNarrow(page: Page) {
  await openSectionNarrow(page, 'Messages');
  await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
}
