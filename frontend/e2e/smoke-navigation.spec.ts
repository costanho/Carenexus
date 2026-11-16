import { expect, type Locator, type Page, test } from '@playwright/test';
import { Api } from './support/api';
import { ADMIN, type DemoUser, JAMES, type LoginRole, ROLE_HOME, SARAH, THOMAS } from './support/env';
import { LOGIN_PATH, escapeRe, login, type SectionParamsInput } from './support/ui';
import {
  ADMIN_DEV_SECTIONS, ADMIN_SECTIONS, DOCTOR_SECTIONS, HIDDEN_SECTIONS, PATIENT_SECTIONS, PROXY_SECTIONS,
  type MonitoredPage, type Section, apiIdle, expectNoArtifacts, homeUrl, marker, navButton, pageOverflow, signedInPage,
  visit, visitByUrl,
} from './support/smoke';

// Smoke: for each of the four roles (patient, doctor, caregiver, administrator), sign in through the login page,
// check the landing dashboard shows real data, open every section of the role's registry list in support/smoke.ts
// from the sidebar and again by its `?section=` URL (hidden sections included), the profile/account menu, reload
// (incl. the refresh-token path), sign out and the route guards, another role's dashboard URL, a 375 px pass,
// that every section title is a heading, and Help & Support's 'Contact support' (offered to patients, doctors and
// caregivers, not to administrators, whose Messages is the support inbox with Open / Closed tabs).
//
// Tables written: refresh_tokens, audit_log and notifications (sign-in alerts) for every sign-in. Nothing else:
// the sections are only opened, never edited.

interface RoleCase {
  user:     DemoUser;
  /** The sidebar sections, in order (support/smoke.ts follows the section registries). */
  sections: Section[];
  /**
   * Sections opened by URL only, with the params they need. `title` replaces the section's title when the params
   * open a record whose own name is the heading.
   */
  hidden:   { section: Section; params?: SectionParamsInput; title?: string }[];
  /** Real seed data that must be on the landing dashboard. */
  landing:  (page: Page) => Promise<void>;
  /** Opens the profile / account menu and checks where it leads. */
  profile:  (page: Page) => Promise<void>;
  signOut:  (page: Page) => Promise<void>;
}

const hidden = (role: LoginRole, key: string): Section => HIDDEN_SECTIONS[role].find(s => s.key === key)!;

const ROLES: RoleCase[] = [
  {
    user: THOMAS,
    sections: PATIENT_SECTIONS,
    hidden: [],
    landing: async page => {
      await expect(page.getByText('Welcome back, Thomas!')).toBeVisible();
      await expect(page.getByText('Thomas Dube 👋')).toBeVisible();                // sidebar greeting
      await expect(page.getByText('Hypertension, Type 2 Diabetes')).toBeVisible();  // conditions (seed V6)
      await expect(page.getByText('Aspirin').first()).toBeVisible();                // allergy
      // Current Prescriptions: the newest active prescriptions, read from the server because flow specs issue
      // (and later cancel) prescriptions for Thomas, so which seed rows are among the newest three varies.
      const current = await newestActivePrescriptions();
      expect(current.length, 'Thomas has active prescriptions (seed V6)').toBeGreaterThan(0);
      const rx = page.getByRole('heading', { name: 'Current Prescriptions', exact: true });
      await expect(rx).toBeVisible();
      for (const p of current) {
        const name = `${p.medicationName}${p.dosage ? ` ${p.dosage}` : ''}`;
        await expect(page.getByRole('button', { name: new RegExp(`^${escapeRe(name)}, .*\\. Open prescription$`) }).first(),
          `current prescription "${name}"`).toBeVisible();
      }
      // Upcoming appointment cards are named "<doctor>, <date>, <time> CAT, open appointment".
      await expect(page.getByRole('button', {
        name: /^Dr\. .+, \w{3} \d{1,2} \w{3} \d{4}, \d{2}:\d{2} CAT, open appointment$/,
      }).first()).toBeVisible();
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
    hidden: [],
    landing: async page => {
      await expect(page.getByText(/^Good (morning|afternoon|evening), Dr\. James Banda$/)).toBeVisible();
      await expect(page.getByRole('heading', { name: "Today's Schedule" })).toBeVisible();
      await expect(page.getByText('My Patients', { exact: true })).toBeVisible();
      await expect(page.getByText('In your care', { exact: true })).toBeVisible();
    },
    profile: async page => {
      await page.getByRole('button', { name: /Dr\. James Banda/ }).click();
      const profile = DOCTOR_SECTIONS.find(s => s.key === 'profile')!;
      await expect(marker(page, profile)).toBeVisible();
      await expect(page.getByText('Professional Details', { exact: true })).toBeVisible();
      await expect(page.getByText('james.banda@carenexus.com').first()).toBeVisible();
      await expect(page.getByText(JAMES.systemId, { exact: true }).first()).toBeVisible();
    },
    signOut: async page => {
      await page.getByRole('button', { name: 'Sign out' }).first().click();
    },
  },
  {
    user: SARAH,
    sections: PROXY_SECTIONS,
    hidden: [
      { section: hidden('proxy', 'add-dependent') },
      { section: hidden('proxy', 'patient-profile'), params: { patient: THOMAS.systemId } },
      { section: hidden('proxy', 'access-requests') },
    ],
    landing: async page => {
      await expect(page.getByText(/^Good (morning|afternoon|evening), Sarah!$/)).toBeVisible();
      await expect(page.getByText('Loved One Overview')).toBeVisible();
      await expect(page.getByText('Linked to your account')).toBeVisible();
      await expect(page.getByRole('button', { name: /^Elias Dube, .*open profile$/ })).toBeVisible();
    },
    profile: async page => {
      const menu = page.getByRole('button', { name: /Sarah Dube/ });
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
      await menu.click();
      await expect(menu).toHaveAttribute('aria-expanded', 'true');
      const account = page.getByRole('menu', { name: 'Account' });
      await expect(account.getByRole('menuitem', { name: 'Log Out' })).toBeVisible();
      await account.getByRole('menuitem', { name: 'Profile & Settings' }).click();
      await expect(page.getByText('Patients you have been authorised to manage as proxy')).toBeVisible();
      await expect(page.getByText('sarah.dube@carenexus.com')).toBeVisible();
    },
    signOut: async page => {
      await page.getByRole('button', { name: /Sarah Dube/ }).click();
      await page.getByRole('menuitem', { name: 'Log Out' }).click();
    },
  },
  {
    user: ADMIN,
    sections: ADMIN_SECTIONS,
    // `id` is filled in at run time with the administrator's own system id (known after sign-in).
    // The account resolves, so its heading is the holder's name (the section title "Account" is only the heading
    // without an id or for an unknown one).
    hidden: [{ section: hidden('admin', 'user-detail'), params: { id: 'self' }, title: ADMIN.name }],
    landing: async page => {
      await expect(page.getByRole('heading', { name: 'Administration', exact: true }).first()).toBeVisible();
      await expect(page.getByText('What is waiting for an administrator, and every administration tool.')).toBeVisible();
      await expect(page.getByRole('button', { name: /^Pending clinician verifications: (none|\d+|loading)/ })).toBeVisible();
      await expect(page.getByText(ADMIN.email, { exact: true })).toBeVisible();
    },
    profile: async page => {
      // The "Your account" card's button (the sidebar entry has the same name).
      const accountCard = page.locator('div').filter({ has: page.getByText('Your account', { exact: true }) })
        .filter({ has: page.getByText(ADMIN.email, { exact: true }) }).last();
      await accountCard.getByRole('button', { name: 'Account security' }).click();
      const account = ADMIN_SECTIONS.find(s => s.key === 'account')!;
      await expect(marker(page, account)).toBeVisible();
      await expect(page).toHaveURL(/[?&]section=account\b/);
    },
    signOut: async page => {
      await page.getByRole('button', { name: 'Sign out' }).first().click();
    },
  },
];

/**
 * Thomas's newest active prescriptions as his landing card asks for them (newest first, effective status ACTIVE,
 * three rows). Read through an API sign-in (no device fields, so no sign-in alert).
 */
async function newestActivePrescriptions(): Promise<{ medicationName: string; dosage?: string | null }[]> {
  const api = await Api.login(THOMAS);
  try {
    const body = await api.ok(api.page('/api/prescriptions', { patientSystemId: THOMAS.systemId, status: 'ACTIVE' }, 0, 3),
      'Thomas\'s newest active prescriptions');
    return body.content;
  } finally {
    await api.dispose();
  }
}

/** The params of a hidden section, with the administrator's own id where `self` stands. */
function paramsFor(entry: { params?: SectionParamsInput }, user: DemoUser): SectionParamsInput {
  const out: SectionParamsInput = { ...(entry.params ?? {}) };
  for (const [k, v] of Object.entries(out)) if (v === 'self') out[k] = user.systemId;
  return out;
}

/** The administrator's system id depends on insert order: read it from an API sign-in (no device fields, so no alert). */
async function knowOwnId(user: DemoUser): Promise<void> {
  if (user.systemId) return;
  const api = await Api.login(user);
  user.systemId = api.systemId;
  await api.dispose();
}

for (const rc of ROLES) {
  const role = rc.user.role;

  test.describe(`smoke navigation: ${role}`, () => {
    let up: MonitoredPage | undefined;

    test.afterEach(async () => {
      await up?.context.close().catch(() => {});
      up = undefined;
    });

    test(`${role}: UI sign in, landing data, every section from the sidebar and by URL`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;

      await expect(page).toHaveURL(homeUrl(rc.user));
      await rc.landing(page);
      await apiIdle(page);
      await expectNoArtifacts(page, `${role} landing`);
      monitor.expectClean('landing');

      await test.step('every sidebar section', async () => {
        for (const s of rc.sections) {
          await visit(page, s);
          await apiIdle(page);
          await expect(page.getByText('Unmatched Route')).toHaveCount(0);
          await expectNoArtifacts(page, `${role} > ${s.nav}`);
          monitor.expectClean(s.nav);
        }
        // The development outbox is listed only where its endpoint answers (local, docker and test profiles).
        if (role === 'admin') {
          for (const s of ADMIN_DEV_SECTIONS) {
            if (!(await navButton(page, s.nav).count())) continue;
            await visit(page, s);
            await apiIdle(page);
            monitor.expectClean(s.nav);
          }
        }
        // No sidebar entry is "coming soon" any more: every one opens a real section.
        await expect(page.getByRole('button', { name: /, coming soon$/i })).toHaveCount(0);
      });

      await test.step('every section by its ?section= URL, hidden ones included', async () => {
        await knowOwnId(rc.user);
        for (const s of rc.sections) {
          await visitByUrl(page, role, s);
          await apiIdle(page);
          await expectNoArtifacts(page, `${role} ?section=${s.key}`);
          monitor.expectClean(`?section=${s.key}`);
        }
        for (const entry of rc.hidden) {
          await visitByUrl(page, role, entry.section, paramsFor(entry, rc.user));
          await apiIdle(page);
          await expectNoArtifacts(page, `${role} ?section=${entry.section.key}`);
          monitor.expectClean(`?section=${entry.section.key}`);
        }
      });
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

      for (const path of [ROLE_HOME[rc.user.role], `${ROLE_HOME[rc.user.role]}?section=messages`, '/dashboard/patient/appointments/book', '/dashboard/call/1', '/']) {
        await page.goto(path);
        await expect(page, `${path} redirects to login when signed out`).toHaveURL(new RegExp(`${LOGIN_PATH}`));
        await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
      }
      // Back button after sign out does not show the dashboard again.
      await page.goBack();
      await expect(page).toHaveURL(new RegExp(`${LOGIN_PATH}`));
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
        for (const target of [other, `${other}?section=messages`]) {
          visited.length = 0;
          await page.goto(target);
          await expect(page).toHaveURL(homeUrl(rc.user));
          await expect(marker(page, rc.sections[0])).toBeVisible();
          await apiIdle(page);
          // The other role's screens must not mount (their API calls would be refused with 403).
          monitor.expectClean(`goto ${target}`);
          expect.soft(visited.filter(p => p !== other && p !== ROLE_HOME[rc.user.role]), `goto ${target}: intermediate routes`).toEqual([]);
        }
      }
    });

    test(`${role}: 375 px dashboard has no horizontal overflow`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user, { viewport: { width: 375, height: 812 } });
      const { page, monitor } = up;
      await expect(marker(page, rc.sections[0])).toBeVisible();
      await apiIdle(page);
      expect.soft(await pageOverflow(page), `${role} dashboard at 375 px`).toEqual([]);
      await expectNoArtifacts(page, `${role} dashboard 375 px`);

      // The navigation stays usable at phone width (drawer menu or icon rail).
      // With the drawer open, its entry is pressed (the administrator's home also has a "Messages" tile, under it).
      const menu = page.getByRole('button', { name: 'Open menu' });
      let drawer: Locator | undefined;
      if (await menu.count()) {
        await menu.first().click();
        await expect(page.getByRole('button', { name: 'Close menu' }).first()).toBeVisible();
        drawer = page.getByRole('dialog', { name: 'Navigation' });
        await expect(drawer).toBeVisible();
      }
      const messages = rc.sections.find(s => s.key === 'messages')!;
      await navButton(page, messages.nav, drawer).click();
      await expect(marker(page, messages)).toBeVisible();
      await apiIdle(page);
      expect.soft(await pageOverflow(page), `${role} messages at 375 px`).toEqual([]);
      monitor.expectClean('375 px');
    });

    test(`${role}: Help & Support ${role === 'admin' ? 'has no Contact support; the support inbox has Open and Closed tabs' : 'offers Contact support'}`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page, monitor } = up;
      const help = rc.sections.find(s => s.key === 'help-support')!;
      await visitByUrl(page, role, help);
      await apiIdle(page);
      await expect(page.getByRole('heading', { name: 'Contact support', exact: true })).toBeVisible();
      // Only checked, never pressed: pressing it opens (or reopens) a support conversation.
      const contact = page.getByRole('button', { name: 'Contact support', exact: true });
      if (role !== 'admin') {
        await expect(contact).toBeVisible();
        await expect(contact).toBeEnabled();
      } else {
        await expect(contact).toHaveCount(0);
        const messages = rc.sections.find(s => s.key === 'messages')!;
        await visitByUrl(page, role, messages);
        await apiIdle(page);
        const tabs = page.getByRole('tablist', { name: 'Support conversation status' });
        const open = tabs.getByRole('tab', { name: 'Open conversations', exact: true });
        const closed = tabs.getByRole('tab', { name: 'Closed conversations', exact: true });
        await expect(open).toHaveAttribute('aria-selected', 'true');
        await expect(closed).toHaveAttribute('aria-selected', 'false');
        await closed.click();
        await expect(closed).toHaveAttribute('aria-selected', 'true');
        await expect(open).toHaveAttribute('aria-selected', 'false');
        await apiIdle(page);
        await open.click();
        await expect(open).toHaveAttribute('aria-selected', 'true');
        await apiIdle(page);
      }
      await expectNoArtifacts(page, `${role} help & support`);
      monitor.expectClean('help & support');
    });

    test(`${role}: every section title is exposed as a heading (WCAG 1.3.1)`, async ({ browser }) => {
      up = await signedInPage(browser, rc.user);
      const { page } = up;
      await knowOwnId(rc.user);
      const missing: string[] = [];
      const check = async (s: Section, title = s.title) => {
        // The marker (e.g. the greeting) can render before the section's data and cards, so give the
        // title time to appear; a title that is plain text still never matches the heading role.
        const heading = page.getByRole('heading', { name: title, exact: true }).first();
        if (!(await heading.waitFor({ timeout: 10_000 }).then(() => true, () => false))) missing.push(`${s.nav} ("${title}")`);
      };
      for (const s of rc.sections) {
        await visit(page, s);
        await check(s);
      }
      for (const entry of rc.hidden) {
        await visitByUrl(page, role, entry.section, paramsFor(entry, rc.user));
        await check(entry.section, entry.title);
      }
      expect(missing, `${role}: section titles that are plain text instead of headings`).toEqual([]);
    });
  });
}
