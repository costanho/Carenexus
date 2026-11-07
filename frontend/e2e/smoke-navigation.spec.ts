import { expect, type Page, test } from '@playwright/test';
import { type DemoUser, JAMES, ROLE_HOME, SARAH, THOMAS } from './support/env';
import { login } from './support/ui';
import {
  DOCTOR_COMING_SOON, DOCTOR_SECTIONS, PATIENT_COMING_SOON, PATIENT_SECTIONS, PROXY_COMING_SOON, PROXY_SECTIONS,
  type MonitoredPage, type Section, apiIdle, expectNoArtifacts, homeUrl, marker, navButton, pageOverflow, signedInPage, visit,
} from './support/smoke';

// Smoke: for each role, sign in through the login page, check the landing dashboard shows real
// data, open every sidebar section, check "Coming soon" items are disabled, the profile/account
// menu, reload (incl. the refresh-token path), sign out and the route guards, and a 375 px pass.

const LOGIN_PATH = '/auth/login/web/login-web';

interface RoleCase {
  user:       DemoUser;
  sections:   Section[];
  comingSoon: string[];
  /** Real seed data that must be on the landing dashboard. */
  landing:    (page: Page) => Promise<void>;
  /** Opens the profile / account menu and checks where it leads. */
  profile:    (page: Page) => Promise<void>;
  signOut:    (page: Page) => Promise<void>;
}

const ROLES: RoleCase[] = [
  {
    user: THOMAS,
    sections: PATIENT_SECTIONS,
    comingSoon: PATIENT_COMING_SOON,
    landing: async page => {
      await expect(page.getByText('Welcome back, Thomas!')).toBeVisible();
      await expect(page.getByText('Thomas Dube 👋')).toBeVisible();                // sidebar greeting
      await expect(page.getByText('Hypertension, Type 2 Diabetes')).toBeVisible();  // conditions (seed V6)
      await expect(page.getByText('Aspirin').first()).toBeVisible();                // allergy
      await expect(page.getByText('Amlodipine 5mg Tablets').first()).toBeVisible(); // current prescription
      await expect(page.getByRole('button', { name: /^Appointment with Dr\. .+ on \d{1,2} \w{3} \d{4} • \d{2}:\d{2}$/ }).first()).toBeVisible();
    },
    profile: async page => {
      await page.getByRole('button', { name: 'Thomas Dube, open profile and settings' }).click();
      await expect(page.getByText('Your personal and health information')).toBeVisible();
      await expect(page.getByText('thomas.dube@carenexus.com').first()).toBeVisible();
      await expect(page.getByText('10 June 1958')).toBeVisible();
      // Account details show the patient's identifier (a positive check: a missing, blank or "—"
      // value all fail, unlike looking only for the absence of "—").
      await expect(page.getByText('Patient ID', { exact: true }).first()).toBeVisible();
      await expect.soft(page.getByText(THOMAS.systemId, { exact: true }).first(), `Patient ID shows ${THOMAS.systemId}`).toBeVisible({ timeout: 5_000 });
    },
    signOut: async page => {
      await page.getByRole('button', { name: 'Sign out' }).first().click();
    },
  },
  {
    user: JAMES,
    sections: DOCTOR_SECTIONS,
    comingSoon: DOCTOR_COMING_SOON,
    landing: async page => {
      await expect(page.getByText(/^Good (morning|afternoon|evening), Dr\. James Banda$/)).toBeVisible();
      await expect(page.getByRole('heading', { name: "Today's Schedule" })).toBeVisible();
      await expect(page.getByText('My Patients', { exact: true })).toBeVisible();
      await expect(page.getByText('With appointments')).toBeVisible();
    },
    profile: async page => {
      await page.getByRole('button', { name: /Dr\. James Banda/ }).click();
      await expect(page.getByText('Professional Details')).toBeVisible();
      await expect(page.getByText('james.banda@carenexus.com')).toBeVisible();
      await expect(page.getByText('DOC-00002')).toBeVisible();
    },
    signOut: async page => {
      await page.getByRole('button', { name: 'Sign out' }).first().click();
    },
  },
  {
    user: SARAH,
    sections: PROXY_SECTIONS,
    comingSoon: PROXY_COMING_SOON,
    landing: async page => {
      await expect(page.getByText(/^Good (morning|afternoon|evening), Sarah!$/)).toBeVisible();
      await expect(page.getByText('Loved One Overview')).toBeVisible();
      await expect(page.getByText('Linked to your account')).toBeVisible();
      await expect(page.getByRole('button', { name: 'Open profile for Elias Dube' })).toBeVisible();
    },
    profile: async page => {
      const menu = page.getByRole('button', { name: /Sarah Dube/ });
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
      await menu.click();
      await expect(menu).toHaveAttribute('aria-expanded', 'true');
      await expect(page.getByRole('menuitem', { name: 'Log Out' })).toBeVisible();
      await page.getByRole('menuitem', { name: 'Profile & Settings' }).click();
      await expect(page.getByText('Patients you have been authorised to manage as proxy')).toBeVisible();
      await expect(page.getByText('sarah.dube@carenexus.com')).toBeVisible();
    },
    signOut: async page => {
      await page.getByRole('button', { name: /Sarah Dube/ }).click();
      await page.getByRole('menuitem', { name: 'Log Out' }).click();
    },
  },
];

for (const rc of ROLES) {
  const role = rc.user.role;

  test.describe(`smoke navigation: ${role}`, () => {
    let up: MonitoredPage | undefined;

    test.afterEach(async () => {
      await up?.context.close().catch(() => {});
      up = undefined;
    });

    test(`${role}: UI sign in, landing data, every sidebar section, coming soon items`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;

      await expect(page).toHaveURL(homeUrl(rc.user));
      await rc.landing(page);
      await apiIdle(page);
      await expectNoArtifacts(page, `${role} landing`);
      monitor.expectClean('landing');

      for (const s of rc.sections) {
        await visit(page, s);
        await apiIdle(page);
        await expect(page.getByText('Unmatched Route')).toHaveCount(0);
        await expectNoArtifacts(page, `${role} > ${s.nav}`);
        monitor.expectClean(s.nav);
      }

      // "Coming soon" items: visible, announced, disabled, and clicking them changes nothing.
      const last = rc.sections[rc.sections.length - 1];
      for (const label of rc.comingSoon) {
        const item = page.getByRole('button', { name: `${label}, coming soon`, exact: true });
        await expect(item, `${label} is shown`).toBeVisible();
        await expect(item, `${label} is disabled`).toBeDisabled();
        await item.click({ force: true });
        await expect(marker(page, last), `clicking ${label} keeps the current section`).toBeVisible();
      }
      await expect(page.getByText('Coming soon').first()).toBeVisible();
      monitor.expectClean('coming soon');
    });

    test(`${role}: profile/account menu`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      await rc.profile(up.page);
      await apiIdle(up.page);
      await expectNoArtifacts(up.page, `${role} profile`);
      up.monitor.expectClean('profile');
    });

    test(`${role}: reload keeps the session, an invalid access token is refreshed`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;
      const first = rc.sections[0];
      await expect(marker(page, first)).toBeVisible();

      // Plain reload: still signed in, same dashboard, data loads.
      await page.reload();
      await expect(page).toHaveURL(homeUrl(rc.user));
      await rc.landing(page);
      await apiIdle(page);
      monitor.expectClean('reload');

      // Make the stored access token invalid (same claims, broken signature): the API answers 401
      // and the client must refresh with the refresh token and retry, without signing the user out.
      monitor.allow({
        url: /^\/api\//, status: 401,
        why: 'the test deliberately breaks the access token signature to force the refresh-token path',
      });
      await page.evaluate(() => {
        const key = 'carenexus_access_token';
        const t = sessionStorage.getItem(key) ?? '';
        const [h, p, s] = t.split('.');
        const flipped = (s[0] === 'A' ? 'B' : 'A') + s.slice(1);
        sessionStorage.setItem(key, `${h}.${p}.${flipped}`);
      });
      const refreshed = page.waitForResponse(r => r.url().endsWith('/api/auth/refresh') && r.request().method() === 'POST');
      await page.reload();
      expect((await refreshed).status(), 'refresh succeeds').toBe(200);
      await expect(page).toHaveURL(homeUrl(rc.user));
      await rc.landing(page);
      await apiIdle(page);
      const tokenOk = await page.evaluate(() => {
        const t = sessionStorage.getItem('carenexus_access_token') ?? '';
        return t.split('.').length === 3 && !!sessionStorage.getItem('carenexus_refresh_token');
      });
      expect(tokenOk, 'new tokens stored after refresh').toBe(true);
      monitor.expectClean('reload with invalid access token');
    });

    test(`${role}: sign out returns to login, protected routes redirect to login`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;

      await rc.signOut(page);
      await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
      await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
      const stored = await page.evaluate(() => [
        sessionStorage.getItem('carenexus_access_token'), sessionStorage.getItem('carenexus_refresh_token'),
      ]);
      expect(stored, 'tokens cleared on sign out').toEqual([null, null]);

      for (const path of [ROLE_HOME[rc.user.role], '/dashboard/patient/appointments/book', '/dashboard/call/1', '/']) {
        await page.goto(path);
        await expect(page, `${path} redirects to login when signed out`).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
        await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
      }
      // Back button after sign out does not show the dashboard again.
      await page.goBack();
      await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}$`));
      monitor.expectClean('sign out');

      // Signing in again works.
      await login(page, rc.user);
      await expect(marker(page, rc.sections[0])).toBeVisible();
      monitor.expectClean('sign in again');
    });

    test(`${role}: another role's dashboard URL redirects home without loading that dashboard`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;
      await expect(marker(page, rc.sections[0])).toBeVisible();
      await apiIdle(page);
      monitor.expectClean('signed in');
      const visited: string[] = [];
      page.on('framenavigated', f => { if (f === page.mainFrame()) visited.push(new URL(f.url()).pathname); });
      for (const other of Object.values(ROLE_HOME).filter(p => p !== ROLE_HOME[rc.user.role])) {
        visited.length = 0;
        await page.goto(other);
        await expect(page).toHaveURL(homeUrl(rc.user));
        await expect(marker(page, rc.sections[0])).toBeVisible();
        await apiIdle(page);
        // The other role's screens must not mount (their API calls would be refused with 403).
        monitor.expectClean(`goto ${other}`);
        expect.soft(visited.filter(p => p !== other && p !== ROLE_HOME[rc.user.role]), `goto ${other}: intermediate routes`).toEqual([]);
      }
    });

    test(`${role}: 375 px dashboard has no horizontal overflow`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user, { viewport: { width: 375, height: 812 } });
      const { page, monitor } = up;
      await expect(marker(page, rc.sections[0])).toBeVisible();
      await apiIdle(page);
      expect.soft(await pageOverflow(page), `${role} dashboard at 375 px`).toEqual([]);
      await expectNoArtifacts(page, `${role} dashboard 375 px`);

      // The navigation stays usable at phone width (patient: drawer menu; doctor/caregiver: icon rail).
      const menu = page.getByRole('button', { name: 'Open menu' });
      if (await menu.count()) {
        await menu.click();
        await expect(page.getByRole('button', { name: 'Close menu' })).toBeVisible();
      }
      const messages = rc.sections.find(s => s.nav === 'Messages')!;
      await navButton(page, 'Messages').click();
      await expect(marker(page, messages)).toBeVisible();
      await apiIdle(page);
      expect.soft(await pageOverflow(page), `${role} messages at 375 px`).toEqual([]);
      monitor.expectClean('375 px');
    });

    test(`${role}: every section title is exposed as a heading (WCAG 1.3.1)`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page } = up;
      const missing: string[] = [];
      for (const s of rc.sections) {
        await visit(page, s);
        // The marker (e.g. the greeting) can render before the section's data and cards, so give the
        // title time to appear; a title that is plain text still never matches the heading role.
        const heading = page.getByRole('heading', { name: s.title, exact: true }).first();
        if (!(await heading.waitFor({ timeout: 10_000 }).then(() => true, () => false))) missing.push(`${s.nav} ("${s.title}")`);
      }
      expect(missing, `${role}: section titles that are plain text instead of headings`).toEqual([]);
    });
  });
}
