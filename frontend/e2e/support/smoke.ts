import { type Browser, type BrowserContext, type BrowserContextOptions, expect, type Locator, type Page, type Response } from '@playwright/test';
import { API_URL, type DemoUser, type LoginRole, ROLE_HOME } from './env';
import { escapeRe, login, mediaPermissions, sectionMain, sectionUrl, type SectionParamsInput, urlShowsSection } from './ui';

// Shared helpers for the smoke suite (smoke-*.spec.ts): a page monitor that fails on page errors,
// console errors and unexpected API statuses, the per-role section inventory, and small UI helpers.

/** Test data created by the smoke suite starts with this, so it is easy to find and clean up. */
export const SMOKE_PREFIX = 'e2e-smoke-';

export function smokeTag(): string {
  return `${SMOKE_PREFIX}${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

// ── Page monitor ────────────────────────────────────────────────────────────

/** An API response the app is expected to produce in a specific test, with the reason why. */
export interface AllowedResponse {
  method?: string;
  url:     RegExp;
  status:  number;
  why:     string;
}

const API_ORIGIN = new URL(API_URL).origin;

/**
 * Collects, for one page: uncaught page errors, console errors, and API (localhost:9000) responses
 * with status >= 500 or an unexpected 4xx. Tests call `expectClean()` at checkpoints.
 */
export class PageMonitor {
  readonly problems: string[] = [];
  readonly apiCalls: { method: string; path: string; status: number }[] = [];
  private allowed: AllowedResponse[] = [];

  constructor(readonly page: Page, readonly who: string) {
    page.on('pageerror', e => this.problems.push(`pageerror: ${e.message.split('\n')[0]}`));
    page.on('console', m => {
      if (m.type() !== 'error') return;
      const text = m.text();
      // The browser logs every non-2xx fetch as "Failed to load resource". Those are judged by
      // their response below (allowed or not), so the console line itself is not counted twice.
      if (/^Failed to load resource: the server responded with a status of \d+/.test(text) && m.location().url.startsWith(API_ORIGIN)) return;
      this.problems.push(`console.error: ${text.slice(0, 300)}`);
    });
    page.on('response', (r: Response) => {
      const url = r.url();
      if (!url.startsWith(API_ORIGIN)) return;
      const method = r.request().method();
      if (method === 'OPTIONS') return;
      const path = url.slice(API_ORIGIN.length);
      const status = r.status();
      this.apiCalls.push({ method, path, status });
      if (status < 400) return;
      if (status < 500 && this.allowed.some(a => a.status === status && a.url.test(path) && (!a.method || a.method === method))) return;
      this.problems.push(`api ${method} ${path} -> ${status}`);
    });
  }

  /** Allows one kind of 4xx response for the rest of this page's life. `why` documents it. */
  allow(rule: AllowedResponse): void {
    expect(rule.status, 'only 4xx responses can be allowed').toBeLessThan(500);
    expect(rule.why.length, 'every allowed response needs a reason').toBeGreaterThan(10);
    this.allowed.push(rule);
  }

  /** Fails (softly, so a test reports every screen) when anything went wrong since the last check. */
  expectClean(where: string): void {
    const found = this.problems.splice(0);
    expect.soft(found, `${this.who} @ ${where}: page errors, console errors or unexpected API statuses`).toEqual([]);
  }
}

export interface MonitoredPage { context: BrowserContext; page: Page; monitor: PageMonitor; user: DemoUser }

/** New context + page with a monitor attached before anything loads, then signs in through the UI. */
export async function signedInPage(browser: Browser, user: DemoUser, options: BrowserContextOptions = {}): Promise<MonitoredPage> {
  const context = await browser.newContext({ permissions: mediaPermissions(browser), ...options });
  const page = await context.newPage();
  page.on('dialog', d => { d.accept().catch(() => {}); });
  const monitor = new PageMonitor(page, user.role);
  await login(page, user);
  return { context, page, monitor, user };
}

// ── Rendering checks ────────────────────────────────────────────────────────

/** Text artifacts that mean a value was not rendered properly. */
const ARTIFACTS: { re: RegExp; what: string }[] = [
  { re: /\bundefined\b/,       what: 'undefined' },
  { re: /\bNaN\b/,             what: 'NaN' },
  { re: /\[object Object\]/,   what: '[object Object]' },
  { re: /\bInvalid Date\b/,    what: 'Invalid Date' },
  { re: /\bnull\b/,            what: 'null' },
  { re: /\bDr\.\s+Dr\./,       what: 'doubled "Dr." title' },
  { re: /Unmatched Route/,     what: 'Unmatched Route' },
  { re: /\bHello,\s+(\.\.\.|…)/, what: 'unresolved user name' },
];

/** Returns the rendering artifacts found in the page's visible text (with context). */
export async function textArtifacts(page: Page, root?: Locator): Promise<string[]> {
  const text = (await (root ?? page.locator('body')).innerText()).replace(/\s+/g, ' ');
  const out: string[] = [];
  for (const a of ARTIFACTS) {
    const m = a.re.exec(text);
    if (m) out.push(`${a.what}: "…${text.slice(Math.max(0, m.index - 60), m.index + 60)}…"`);
  }
  return out;
}

export async function expectNoArtifacts(page: Page, where: string): Promise<void> {
  expect.soft(await textArtifacts(page), `${where}: rendering artifacts`).toEqual([]);
}

// ── Navigation ──────────────────────────────────────────────────────────────

export interface Section {
  /** Sidebar button label (accessible name, without an unread or count suffix). */
  nav:     string;
  /** Text that only shows when this section is open (its title or subtitle). */
  marker:  string | RegExp;
  /** The visible section title, which should be exposed as a heading. */
  title:   string;
  /** Registry key (`?section=<key>`, CONTRACTS 12.1); the main landmark is named after `nav`. */
  key?:    string;
}

// The section lists follow the registries in src/components/web/sections/<role>.sections.tsx, in sidebar order.
// `marker` is the screen's subtitle (or another text only that screen shows), `title` its page heading.

/** Shared texts: the full notification list shows the "All types" filter (the bell panel does not). */
const NOTIFICATIONS_MARKER = 'All types';
const SETTINGS_MARKER = 'Choose what you are notified about and how. Changes are saved as soon as you make them.';
const HELP_MARKER = 'How to use CareNexus and where to get help';

export const PATIENT_SECTIONS: Section[] = [
  { key: 'dashboard',            nav: 'Dashboard',               marker: 'Health Overview',                                   title: 'Health Overview' },
  { key: 'book-appointment',     nav: 'Book Appointment',        marker: 'Schedule a visit with your healthcare provider',    title: 'Book Appointment' },
  { key: 'my-appointments',      nav: 'My Appointments',         marker: 'Track and manage all your healthcare appointments', title: 'My Appointments' },
  // "Join Consultation" opens My Appointments by design (calls are joined from the list).
  { key: 'join-consultation',    nav: 'Join Consultation',       marker: 'Track and manage all your healthcare appointments', title: 'My Appointments' },
  { key: 'consultation-history', nav: 'Consultation History',    marker: 'Your complete clinical record',                     title: 'Consultation History' },
  { key: 'prescriptions',        nav: 'Prescriptions',           marker: 'Your medication history',                           title: 'Prescriptions' },
  { key: 'health-tracking',      nav: 'Vitals & Symptoms',       marker: 'Record your readings and symptoms, and see how they change over time.', title: 'Vitals & Symptoms' },
  { key: 'care-plans',           nav: 'Care Plans',              marker: 'The goals and next steps your care team has set out with you', title: 'Care Plans' },
  { key: 'health-alerts',        nav: 'Health Alerts',           marker: 'Reminders from automated screening and results your doctor has confirmed', title: 'Health alerts' },
  { key: 'medical-records',      nav: 'Medical Records',         marker: 'Your complete health record',                       title: 'Medical Records' },
  { key: 'lab-results',          nav: 'Lab Results',             marker: 'Your laboratory test history',                      title: 'Lab Results' },
  { key: 'imaging-results',      nav: 'Imaging Results',         marker: 'Your radiology and imaging history',                title: 'Imaging Results' },
  { key: 'documents',            nav: 'Documents',               marker: 'Files in your health record: reports, letters, consent forms and insurance papers.', title: 'Documents' },
  { key: 'referrals',            nav: 'Referrals',               marker: 'Referrals your doctors have made to specialists, and how each one is progressing.', title: 'Referrals' },
  { key: 'care-team',            nav: 'My Care Team',            marker: /The doctors and other providers looking after you/, title: 'My Care Team' },
  { key: 'my-proxies',           nav: 'My Proxies (Caregivers)', marker: /Caregivers can message your care team/,             title: 'My caregivers' },
  { key: 'messages',             nav: 'Messages',                marker: 'Message your doctors and care team.',               title: 'Messages' },
  { key: 'notifications',        nav: 'Notifications',           marker: NOTIFICATIONS_MARKER,                                title: 'Notifications' },
  { key: 'billing',              nav: 'Billing & Payments',      marker: 'Your invoices, payments, insurance claims and saved payment methods', title: 'Billing & Payments' },
  { key: 'profile-settings',     nav: 'Profile & Settings',      marker: 'Your personal and health information',              title: 'Profile & Settings' },
  { key: 'settings',             nav: 'Settings',                marker: SETTINGS_MARKER,                                     title: 'Settings' },
  { key: 'help-support',         nav: 'Help & Support',          marker: HELP_MARKER,                                         title: 'Help & Support' },
];
/** Kept for the specs that import it: no patient section is "coming soon" any more. */
export const PATIENT_COMING_SOON: string[] = [];

export const DOCTOR_SECTIONS: Section[] = [
  { key: 'dashboard',       nav: 'Dashboard',       marker: "Here's your practice at a glance.",                  title: "Today's Schedule" },
  { key: 'schedule',        nav: 'Schedule',        marker: 'Manage your appointments and consultations',         title: 'My Schedule' },
  { key: 'availability',    nav: 'Availability',    marker: 'Set the hours patients can book you in and block time when you are away', title: 'Availability' },
  { key: 'consultations',   nav: 'Consultations',   marker: 'Your full consultation history and active sessions', title: 'Consultations' },
  { key: 'appointments',    nav: 'Appointments',    marker: 'Complete appointment history for your patients',     title: 'Appointments' },
  { key: 'patients',        nav: 'Patients',        marker: 'Patients on your care team, or with an appointment or consultation with you', title: 'My Patients' },
  { key: 'medical-records', nav: 'Medical Records', marker: 'All records for patients associated with you',       title: 'Medical Records' },
  { key: 'documents',       nav: 'Documents',       marker: "Files in a patient's record: reports, letters, consent forms and insurance papers.", title: 'Documents' },
  { key: 'prescriptions',   nav: 'Prescriptions',   marker: 'Issue prescriptions and manage the ones you have written', title: 'Prescriptions' },
  { key: 'labs',            nav: 'Labs & Results',  marker: 'Laboratory test results for all your patients',      title: 'Labs & Results' },
  { key: 'imaging',         nav: 'Imaging',         marker: 'Imaging results for all your patients',              title: 'Imaging' },
  { key: 'care-plans',      nav: 'Care Plans',      marker: 'Goals, interventions and review dates for the patients in your care', title: 'Care Plans' },
  { key: 'referrals',       nav: 'Referrals',       marker: 'Refer patients to specialists and respond to referrals addressed to you.', title: 'Referrals' },
  // The subtitle becomes the unacknowledged count once it has loaded.
  { key: 'ai-alerts',       nav: 'Clinical Alerts', marker: /^(Alerts raised by automated screening of results, readings and medication|\d+ unacknowledged alerts? for your patients)$/, title: 'Clinical Alerts' },
  { key: 'billing',         nav: 'Billing',         marker: 'Invoices for your consultations and the fees new invoices are priced from.', title: 'Billing' },
  { key: 'messages',        nav: 'Messages',        marker: 'Conversations with your patients and their caregivers.', title: 'Messages' },
  { key: 'notifications',   nav: 'Notifications',   marker: NOTIFICATIONS_MARKER,                                 title: 'Notifications' },
  { key: 'profile',         nav: 'Profile',         marker: 'Your professional profile and contact details',     title: 'Profile' },
  { key: 'settings',        nav: 'Settings',        marker: SETTINGS_MARKER,                                      title: 'Settings' },
  { key: 'help-support',    nav: 'Help & Support',  marker: HELP_MARKER,                                          title: 'Help & Support' },
];
/** Kept for the specs that import it: no doctor section is "coming soon" any more. */
export const DOCTOR_COMING_SOON: string[] = [];

export const PROXY_SECTIONS: Section[] = [
  { key: 'dashboard',             nav: 'Dashboard',             marker: 'Loved One Overview',                                   title: 'Loved One Overview' },
  { key: 'appointments',          nav: 'Appointments',          marker: 'Appointments for your linked patients',                title: 'Appointments' },
  { key: 'medications',           nav: 'Medications',           marker: 'Prescriptions for your linked patients',               title: 'Medications' },
  { key: 'care-plans',            nav: 'Care Plans',            marker: 'Care plans for the people you care for',               title: 'Care Plans' },
  { key: 'messages',              nav: 'Messages',              marker: "Message your loved ones' care teams.",                 title: 'Messages' },
  { key: 'notifications',         nav: 'Notifications',         marker: NOTIFICATIONS_MARKER,                                   title: 'Notifications' },
  { key: 'documents-results',     nav: 'Documents & Results',   marker: 'Lab results, imaging, medical records and consultations your loved ones share with you', title: 'Documents & Results' },
  { key: 'health-summary',        nav: 'Health Summary',        marker: 'Health overview for your linked patients',             title: 'Health Summary' },
  { key: 'health-alerts',         nav: 'Health Alerts',         marker: 'Reminders from automated screening and results a doctor has confirmed, for the people you care for', title: 'Health alerts' },
  { key: 'billing',               nav: 'Billing & Payments',    marker: 'Invoices, payments and insurance claims of the people you care for', title: 'Billing & Payments' },
  { key: 'profile-settings',      nav: 'Profile & Settings',    marker: 'Patients you have been authorised to manage as proxy', title: 'Linked Patients' },
  { key: 'notification-settings', nav: 'Notification Settings', marker: 'Choose how you receive notifications',                 title: 'Notification Settings' },
  { key: 'help-support',          nav: 'Help & Support',        marker: HELP_MARKER,                                            title: 'Help & Support' },
];
/** Kept for the specs that import it: no caregiver section is "coming soon" any more. */
export const PROXY_COMING_SOON: string[] = [];

/** Administrator sections in sidebar order (the dev-only Outbox is ADMIN_DEV_SECTIONS). */
export const ADMIN_SECTIONS: Section[] = [
  { key: 'dashboard',        nav: 'Dashboard',              marker: 'What is waiting for an administrator, and every administration tool.', title: 'Administration' },
  { key: 'users',            nav: 'Users',                  marker: 'Every CareNexus account. Search, review an account, or deactivate and reactivate it.', title: 'Users' },
  { key: 'clinician-queue',  nav: 'Clinician verification', marker: /^Check each clinician's licence before they can sign in\./, title: 'Clinician verification' },
  { key: 'account-requests', nav: 'Account requests',       marker: /^Data exports, account closures and deletion requests\./, title: 'Account requests' },
  { key: 'corrections',      nav: 'Correction requests',    marker: /^Requests from patients and caregivers to correct a clinical record\./, title: 'Correction requests' },
  { key: 'billing',          nav: 'Billing',                marker: "Invoices, payments, insurance claims and the clinic's default fees", title: 'Billing' },
  { key: 'messages',         nav: 'Messages',               marker: 'Conversations people opened from Help & Support.',     title: 'Support messages' },
  { key: 'notifications',    nav: 'Notifications',          marker: NOTIFICATIONS_MARKER,                                   title: 'Notifications' },
  { key: 'help-support',     nav: 'Help & Support',         marker: HELP_MARKER,                                            title: 'Help & Support' },
  { key: 'audit-log',        nav: 'Audit log',              marker: /^Who did what, and when\./,                            title: 'Audit log' },
  { key: 'legal',            nav: 'Legal documents',        marker: /^The Terms of Service and the Privacy Policy people accept\./, title: 'Legal documents' },
  { key: 'facilities',       nav: 'Facilities',             marker: /^Locations that can be chosen for in-person appointments and care teams\./, title: 'Facilities' },
  { key: 'system-settings',  nav: 'System settings',        marker: /^Values the clinic can change without a new deployment\./, title: 'System settings' },
  { key: 'account',          nav: 'Account security',       marker: /^Your password, sign-in email, two-factor authentication/, title: 'Account security' },
];
/** Listed in the sidebar only where the development outbox endpoint answers (local, docker and test profiles). */
export const ADMIN_DEV_SECTIONS: Section[] = [
  { key: 'outbox', nav: 'Outbox', marker: /^Development installations only\./, title: 'Outbox' },
];
/** Sections opened by URL only (nav: false in the registries), with the label their main landmark carries. */
export const HIDDEN_SECTIONS: Record<LoginRole, Section[]> = {
  patient: [],
  doctor:  [],
  proxy: [
    { key: 'add-dependent',   nav: 'Add a Loved One', marker: 'Who are you adding?',                                        title: 'Add Loved One' },
    // Needs `patient=<PAT id>` of a loved one.
    { key: 'patient-profile', nav: 'Patient Profile', marker: /^What you can see and do for /,                              title: 'Patient Profile' },
    { key: 'access-requests', nav: 'Access Requests', marker: 'Requests you sent to patients who already have their own account', title: 'Access Requests' },
  ],
  admin: [
    // `id=<system id>` opens one account; the subtitle is that id. `title` is the heading without an id or for an
    // unknown id: an account that resolves is titled with its holder's name, so a spec that opens one passes it.
    { key: 'user-detail', nav: 'User details', marker: /^(No account selected|Account not found|[A-Z]{3}-\d+)$/, title: 'Account' },
  ],
};

/** Every sidebar section of a role. */
export const SECTIONS_BY_ROLE: Record<LoginRole, Section[]> = {
  patient: PATIENT_SECTIONS,
  doctor:  DOCTOR_SECTIONS,
  proxy:   PROXY_SECTIONS,
  admin:   ADMIN_SECTIONS,
};

/**
 * The navigation button for a section (its name may carry an unread suffix). Pass `within` to look only inside
 * one container, such as the phone drawer: the main content can hold a same-named button (the administrator's
 * home has a "Messages" tool tile) that comes first in the page and lies under the open drawer.
 */
export function navButton(page: Page, label: string, within?: Locator): Locator {
  return (within ?? page).getByRole('button', { name: new RegExp(`^${escapeRe(label)}(, \\d+ unread)?$`) }).first();
}

/**
 * Opens a section by its URL (`?section=<key>` plus params) instead of the sidebar, then waits for its main
 * landmark, its marker text and the end of its loading state. The section needs a `key`.
 */
export async function visitByUrl(page: Page, role: LoginRole, s: Section, params: SectionParamsInput = {}): Promise<void> {
  expect(s.key, `section "${s.nav}" has a registry key`).toBeTruthy();
  await page.goto(sectionUrl(role, s.key, params));
  await page.waitForURL(u => urlShowsSection(u, s.key === 'join-consultation' ? 'my-appointments' : s.key), { timeout: 30_000 });
  await expect(sectionMain(page, s.nav === 'Join Consultation' ? 'My Appointments' : s.nav), `section "${s.nav}" main landmark`).toBeVisible();
  await expect(marker(page, s), `section "${s.nav}" renders`).toBeVisible();
  await expect(page.getByText(/^Loading\b.*(…|\.\.\.)$/)).toHaveCount(0, { timeout: 20_000 });
}

export function marker(page: Page, s: Section): Locator {
  return page.getByText(s.marker, { exact: typeof s.marker === 'string' }).first();
}

/** Opens a section from the sidebar and waits for its marker text. */
export async function visit(page: Page, s: Section): Promise<void> {
  await navButton(page, s.nav).click();
  await expect(marker(page, s), `section "${s.nav}" renders`).toBeVisible();
  // Wait for the section's own loading state to settle so errors from its requests are caught here.
  await expect(page.getByText(/^Loading\b.*(…|\.\.\.)$/)).toHaveCount(0, { timeout: 20_000 });
}

/** Waits until no request to the API is in flight for a short quiet period. */
export async function apiIdle(page: Page, quietMs = 600, timeoutMs = 20_000): Promise<void> {
  let inFlight = 0;
  let last = Date.now();
  const on  = (r: { url(): string }) => { if (r.url().startsWith(API_ORIGIN)) { inFlight++; last = Date.now(); } };
  const off = (r: { url(): string }) => { if (r.url().startsWith(API_ORIGIN)) { inFlight = Math.max(0, inFlight - 1); last = Date.now(); } };
  const onReq = (r: any) => on(r);
  const onDone = (r: any) => off(r);
  page.on('request', onReq);
  page.on('requestfinished', onDone);
  page.on('requestfailed', onDone);
  try {
    await expect.poll(() => inFlight === 0 && Date.now() - last >= quietMs, { timeout: timeoutMs, intervals: [100] }).toBe(true);
  } finally {
    page.off('request', onReq);
    page.off('requestfinished', onDone);
    page.off('requestfailed', onDone);
  }
}

export function homeUrl(user: DemoUser): RegExp {
  return new RegExp(`${escapeRe(ROLE_HOME[user.role])}$`);
}

/** The innermost element that contains `text` and `inner` (e.g. one appointment card). */
export function cardWith(page: Page, text: string | RegExp, inner?: Locator): Locator {
  let loc = page.locator('div').filter({ has: page.getByText(text) });
  if (inner) loc = loc.filter({ has: inner });
  return loc.last();
}

/** Clinic-local calendar date (Africa/Harare) `days` from now as { iso: YYYY-MM-DD, label } for the date picker. */
export function futureDay(days: number): { iso: string; label: string; date: Date } {
  const d = new Date(Date.now() + days * 86_400_000);
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Harare', year: 'numeric', month: '2-digit', day: '2-digit' }).format(d);
  const [y, m, day] = parts.split('-').map(Number);
  const local = new Date(y, m - 1, day);
  return {
    iso: parts,
    date: local,
    label: local.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' }),
  };
}

/**
 * Page-level horizontal overflow at the current viewport: the document scrolls sideways, or a
 * visible element sticks out past the right edge and is not inside a horizontally scrollable
 * container (a carousel the user can swipe is fine; content cut off at the screen edge is not).
 */
export async function pageOverflow(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const vw = document.documentElement.clientWidth;
    const out: string[] = [];
    const doc = document.scrollingElement ?? document.documentElement;
    if (doc.scrollWidth > vw + 1) out.push(`document scrollWidth ${doc.scrollWidth} > ${vw}`);
    const inScroller = (el: HTMLElement) => {
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const ox = getComputedStyle(p).overflowX;
        if ((ox === 'auto' || ox === 'scroll') && p.getBoundingClientRect().right <= vw + 1) return true;
      }
      return false;
    };
    const describe = (h: HTMLElement) => {
      const label = h.getAttribute('aria-label') ?? (h.innerText ?? '').trim().replace(/\s+/g, ' ').slice(0, 50);
      return `${h.tagName.toLowerCase()}${label ? ` "${label}"` : ''}`;
    };
    for (const el of Array.from(document.querySelectorAll<HTMLElement>('body *'))) {
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || r.right <= vw + 1 || r.left >= vw) continue;
      const parent = el.parentElement?.getBoundingClientRect();
      if (parent && parent.right > vw + 1) continue; // only the outermost offender
      if (inScroller(el)) continue;
      out.push(`${describe(el)} right ${Math.round(r.right)} > ${vw}`);
    }
    return out.slice(0, 15);
  });
}
