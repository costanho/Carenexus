import { expect, type Page, test } from '@playwright/test';
import { ADMIN, type DemoUser, JAMES, type LoginRole, ROLE_HOME, SARAH, THOMAS } from './support/env';
import { navButton } from './support/smoke';
import {
  LOGIN_PATH, finishSignIn, mediaPermissions, newUserPage, sectionMain, sectionUrl, submitLogin, urlShowsSection,
  type SectionParamsInput, type UserPage,
} from './support/ui';

// Deep links (CONTRACTS 7.1, 12.1; gap G16): every notification target URL the server builds opens the right
// dashboard section for each role, administrators included, and the section survives a reload and the browser's
// Back button. Also: a section link opened while signed out opens after sign-in, and an unknown section key
// falls back to the role's home.
//
// The links carry no resource id: they must open their section whatever data the dev seed holds. Caregiver links
// carry `patient=<Thomas>` (Sarah's loved one in the dev seed), like the ones the server builds for a caregiver;
// the doctor's per-patient targets carry it too.
//
// Tables written: none (read-only; sign-in only).

interface DeepLink {
  /** NotificationTarget the link is built for. */
  target:  string;
  section: string;
  params?: SectionParamsInput;
  /** Registry label of the section: the name of its main landmark. */
  label:   string;
}

interface RoleLinks { user: DemoUser; links: DeepLink[] }

const PATIENT = THOMAS.systemId;

const PATIENT_LINKS: DeepLink[] = [
  { target: 'APPOINTMENTS',        section: 'my-appointments',      label: 'My Appointments' },
  { target: 'CONSULTATIONS',       section: 'consultation-history', label: 'Consultation History' },
  { target: 'PRESCRIPTIONS',       section: 'prescriptions',        label: 'Prescriptions' },
  { target: 'MEDICATION_SCHEDULE', section: 'prescriptions',        label: 'Prescriptions', params: { tab: 'schedule' } },
  { target: 'LAB_RESULTS',         section: 'lab-results',          label: 'Lab Results' },
  { target: 'IMAGING_RESULTS',     section: 'imaging-results',      label: 'Imaging Results' },
  { target: 'MEDICAL_RECORDS',     section: 'medical-records',      label: 'Medical Records' },
  { target: 'DOCUMENTS',           section: 'documents',            label: 'Documents' },
  { target: 'REFERRALS',           section: 'referrals',            label: 'Referrals' },
  { target: 'CARE_PLANS',          section: 'care-plans',           label: 'Care Plans' },
  { target: 'HEALTH_TRACKING',     section: 'health-tracking',      label: 'Vitals & Symptoms' },
  { target: 'AI_ALERTS',           section: 'health-alerts',        label: 'Health Alerts' },
  { target: 'BILLING',             section: 'billing',              label: 'Billing & Payments' },
  { target: 'CAREGIVER_ACCESS',    section: 'my-proxies',           label: 'My Proxies (Caregivers)' },
  { target: 'CARE_TEAM',           section: 'care-team',            label: 'My Care Team' },
  { target: 'MESSAGES',            section: 'messages',             label: 'Messages' },
  { target: 'ACCOUNT_SECURITY',    section: 'profile-settings',     label: 'Profile & Settings', params: { tab: 'security' } },
  { target: 'CORRECTIONS',         section: 'medical-records',      label: 'Medical Records', params: { tab: 'corrections' } },
];

const DOCTOR_LINKS: DeepLink[] = [
  { target: 'APPOINTMENTS',        section: 'appointments',    label: 'Appointments' },
  { target: 'CONSULTATIONS',       section: 'consultations',   label: 'Consultations' },
  { target: 'PRESCRIPTIONS',       section: 'prescriptions',   label: 'Prescriptions' },
  { target: 'MEDICATION_SCHEDULE', section: 'prescriptions',   label: 'Prescriptions', params: { patient: PATIENT } },
  { target: 'LAB_RESULTS',         section: 'labs',            label: 'Labs & Results' },
  { target: 'IMAGING_RESULTS',     section: 'imaging',         label: 'Imaging' },
  { target: 'MEDICAL_RECORDS',     section: 'medical-records', label: 'Medical Records' },
  { target: 'DOCUMENTS',           section: 'documents',       label: 'Documents', params: { patient: PATIENT } },
  { target: 'REFERRALS',           section: 'referrals',       label: 'Referrals' },
  { target: 'CARE_PLANS',          section: 'care-plans',      label: 'Care Plans' },
  { target: 'HEALTH_TRACKING',     section: 'patients',        label: 'Patients', params: { tab: 'vitals', patient: PATIENT } },
  { target: 'AI_ALERTS',           section: 'ai-alerts',       label: 'Clinical Alerts' },
  { target: 'BILLING',             section: 'billing',         label: 'Billing' },
  { target: 'CARE_TEAM',           section: 'patients',        label: 'Patients', params: { patient: PATIENT } },
  { target: 'MESSAGES',            section: 'messages',        label: 'Messages' },
  { target: 'ACCOUNT_SECURITY',    section: 'profile',         label: 'Profile', params: { tab: 'security' } },
  { target: 'CORRECTIONS',         section: 'medical-records', label: 'Medical Records', params: { tab: 'corrections' } },
];

const PROXY_LINKS: DeepLink[] = [
  { target: 'APPOINTMENTS',        section: 'appointments',      label: 'Appointments',        params: { patient: PATIENT } },
  { target: 'CONSULTATIONS',       section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'consultations' } },
  { target: 'PRESCRIPTIONS',       section: 'medications',       label: 'Medications',         params: { patient: PATIENT } },
  { target: 'MEDICATION_SCHEDULE', section: 'medications',       label: 'Medications',         params: { patient: PATIENT, tab: 'schedule' } },
  { target: 'LAB_RESULTS',         section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'labs' } },
  { target: 'IMAGING_RESULTS',     section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'imaging' } },
  { target: 'MEDICAL_RECORDS',     section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'records' } },
  { target: 'DOCUMENTS',           section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'documents' } },
  { target: 'REFERRALS',           section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'referrals' } },
  { target: 'CARE_PLANS',          section: 'care-plans',        label: 'Care Plans',          params: { patient: PATIENT } },
  { target: 'HEALTH_TRACKING',     section: 'health-summary',    label: 'Health Summary',      params: { patient: PATIENT } },
  { target: 'AI_ALERTS',           section: 'health-alerts',     label: 'Health Alerts',       params: { patient: PATIENT } },
  { target: 'BILLING',             section: 'billing',           label: 'Billing & Payments',  params: { patient: PATIENT } },
  { target: 'CAREGIVER_ACCESS',    section: 'profile-settings',  label: 'Profile & Settings',  params: { patient: PATIENT } },
  { target: 'CAREGIVER_ACCESS (access request)', section: 'access-requests', label: 'Access Requests' },
  { target: 'CARE_TEAM',           section: 'patient-profile',   label: 'Patient Profile',     params: { patient: PATIENT } },
  { target: 'MESSAGES',            section: 'messages',          label: 'Messages',            params: { patient: PATIENT } },
  { target: 'ACCOUNT_SECURITY',    section: 'profile-settings',  label: 'Profile & Settings',  params: { tab: 'security' } },
  { target: 'CORRECTIONS',         section: 'documents-results', label: 'Documents & Results', params: { patient: PATIENT, tab: 'corrections' } },
];

const ADMIN_LINKS: DeepLink[] = [
  { target: 'BILLING',                section: 'billing',          label: 'Billing' },
  { target: 'MESSAGES',               section: 'messages',         label: 'Messages' },
  { target: 'ACCOUNT_SECURITY',       section: 'account',          label: 'Account security' },
  { target: 'CORRECTIONS',            section: 'corrections',      label: 'Correction requests' },
  { target: 'ADMIN_CLINICIAN_QUEUE',  section: 'clinician-queue',  label: 'Clinician verification' },
  { target: 'ADMIN_ACCOUNT_REQUESTS', section: 'account-requests', label: 'Account requests' },
  { target: 'ADMIN_BILLING',          section: 'billing',          label: 'Billing' },
];

const ROLES: RoleLinks[] = [
  { user: THOMAS, links: PATIENT_LINKS },
  { user: JAMES,  links: DOCTOR_LINKS },
  { user: SARAH,  links: PROXY_LINKS },
  { user: ADMIN,  links: ADMIN_LINKS },
];

const softExpect = expect.configure({ soft: true });

/** Page errors (uncaught exceptions) since the last call. */
function pageErrors(page: Page): () => string[] {
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message.split('\n')[0]));
  return () => errors.splice(0);
}

/** The section of `link` is on screen: URL, main landmark, and no error or not-found page in its place. */
async function expectSection(page: Page, role: LoginRole, link: DeepLink, when: string): Promise<void> {
  const where = `${role} ${link.target} -> ?section=${link.section} (${when})`;
  // Soft, so one wrong link reports and the remaining links are still checked.
  await softExpect.poll(() => urlShowsSection(page.url(), link.section), { message: `${where}: URL keeps the section`, timeout: 20_000 }).toBe(true);
  await expect.soft(sectionMain(page, link.label), `${where}: main landmark "${link.label}"`).toBeVisible({ timeout: 20_000 });
  // The tab is part of where the link points (a filter param such as `patient` may be consumed by the screen).
  if (link.params?.tab) {
    expect.soft(urlShowsSection(page.url(), link.section, { tab: link.params.tab }), `${where}: URL keeps tab=${link.params.tab}`).toBe(true);
  }
  await expect.soft(page.getByRole('heading', { name: 'Something went wrong' }), `${where}: no error boundary`).toHaveCount(0);
  await expect.soft(page.getByRole('heading', { name: 'Page not found' }), `${where}: not the not-found page`).toHaveCount(0);
  await expect.soft(page.getByText('Unmatched Route'), `${where}: no unmatched route`).toHaveCount(0);
}

// One test per link, so each link has its own timeout and its own result: a wrong link (whose soft waits can take
// a minute or more) neither runs the role's other links out of time nor hides them. The role signs in once per
// worker in beforeAll and the links reuse that page. The describe is deliberately not serial: after a failed link
// Playwright starts a fresh worker, beforeAll signs in again, and the remaining links still run.
for (const { user, links } of ROLES) {
  const role = user.role;

  test.describe(`deep links: ${role}`, () => {
    let up: UserPage | undefined;
    let errors: () => string[] = () => [];

    test.beforeAll(async ({ browser }) => {
      up = await newUserPage(browser, user);
      errors = pageErrors(up.page);
      await expect(up.page).toHaveURL(new RegExp(`${ROLE_HOME[role]}$`));
    });

    test.afterAll(async () => {
      await up?.context.close().catch(() => {});
      up = undefined;
    });

    for (const link of links) {
      const url = sectionUrl(role, link.section, link.params);

      test(`${role}: ${link.target} -> ${url} opens its section, and survives reload and Back`, async () => {
        if (!up) throw new Error(`${role}: not signed in (beforeAll failed)`);
        const { page } = up;
        errors(); // errors left over from the previous link belong to that link

        await page.goto(url);
        await expectSection(page, role, link, 'opened from the link');

        await page.reload();
        await expectSection(page, role, link, 'after reload');

        // Leave through the sidebar's home entry, then come back with the browser's Back button.
        await navButton(page, 'Dashboard').click();
        await page.waitForURL(u => urlShowsSection(u, undefined), { timeout: 20_000 });
        await page.goBack();
        await expectSection(page, role, link, 'after Back');

        expect.soft(errors(), `${role} ${link.target}: uncaught page errors`).toEqual([]);
      });
    }
  });
}

test.describe('deep links: signed out and unknown sections', () => {
  test('a section link opened while signed out opens after sign-in', async ({ browser }) => {
    const context = await browser.newContext({ permissions: mediaPermissions(browser) });
    try {
      const page = await context.newPage();
      const link = PATIENT_LINKS.find(l => l.target === 'REFERRALS')!;
      await page.goto(sectionUrl('patient', link.section, link.params));
      await page.waitForURL(u => u.pathname === LOGIN_PATH, { timeout: 30_000 });

      await submitLogin(page, THOMAS, { goto: false });
      await finishSignIn(page, THOMAS, u => urlShowsSection(u, link.section) && u.pathname === ROLE_HOME.patient);
      await expectSection(page, 'patient', link, 'after sign-in');
    } finally {
      await context.close();
    }
  });

  test('an unknown section key falls back to the home section', async ({ browser }) => {
    const up = await newUserPage(browser, THOMAS);
    try {
      const { page } = up;
      await page.goto(sectionUrl('patient', 'no-such-section'));
      // The URL is rewritten in place to what is shown: the bare role home.
      await page.waitForURL(u => urlShowsSection(u, undefined) && u.pathname === ROLE_HOME.patient, { timeout: 20_000 });
      await expect(sectionMain(page, 'Dashboard')).toBeVisible();
      await expect(page.getByRole('heading', { name: 'Page not found' })).toHaveCount(0);
    } finally {
      await up.context.close();
    }
  });
});
