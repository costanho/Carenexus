import { type Browser, type BrowserContext, type BrowserContextOptions, expect, type Locator, type Page, type Response } from '@playwright/test';
import { API_URL, type DemoUser, ROLE_HOME } from './env';
import { escapeRe, login } from './ui';

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
  const context = await browser.newContext({ permissions: ['camera', 'microphone'], ...options });
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
  /** Sidebar button label (accessible name, without an unread suffix). */
  nav:     string;
  /** Text that only shows when this section is open (its title or subtitle). */
  marker:  string | RegExp;
  /** The visible section title, which should be exposed as a heading. */
  title:   string;
}

export const PATIENT_SECTIONS: Section[] = [
  { nav: 'Dashboard',               marker: 'Health Overview',                                   title: 'Health Overview' },
  { nav: 'Book Appointment',        marker: 'Schedule a visit with your healthcare provider',    title: 'Book Appointment' },
  { nav: 'My Appointments',         marker: 'Track and manage all your healthcare appointments', title: 'My Appointments' },
  // "Join Consultation" opens My Appointments by design (calls are joined from the list).
  { nav: 'Join Consultation',       marker: 'Track and manage all your healthcare appointments', title: 'My Appointments' },
  { nav: 'Consultation History',    marker: 'Your complete clinical record',                     title: 'Consultation History' },
  { nav: 'Medical Records',         marker: 'Your complete health record',                       title: 'Medical Records' },
  { nav: 'Prescriptions',           marker: 'Your medication history',                           title: 'Prescriptions' },
  { nav: 'Lab Results',             marker: 'Your laboratory test history',                      title: 'Lab Results' },
  { nav: 'Imaging Results',         marker: 'Your radiology and imaging history',                title: 'Imaging Results' },
  { nav: 'My Proxies (Caregivers)', marker: /Caregivers can message your care team/,             title: 'My caregivers' },
  { nav: 'Messages',                marker: 'Message your doctors and care team.',               title: 'Messages' },
  { nav: 'Notifications',           marker: /^(New message|Urgent message|No notifications)/,    title: 'Notifications' },
  { nav: 'Profile & Settings',      marker: 'Your personal and health information',              title: 'Profile & Settings' },
];
export const PATIENT_COMING_SOON = ['Referrals'];

export const DOCTOR_SECTIONS: Section[] = [
  { nav: 'Dashboard',       marker: "Here's your practice at a glance.",                  title: "Today's Schedule" },
  { nav: 'Schedule',        marker: 'Manage your appointments and consultations',         title: 'My Schedule' },
  { nav: 'Consultations',   marker: 'Your full consultation history and active sessions', title: 'Consultations' },
  { nav: 'Appointments',    marker: 'Complete appointment history for your patients',     title: 'Appointments' },
  { nav: 'Patients',        marker: 'Patients who have had appointments with you',        title: 'My Patients' },
  { nav: 'Medical Records', marker: 'All records for patients associated with you',       title: 'Medical Records' },
  { nav: 'Prescriptions',   marker: 'Medication prescriptions for all your patients',     title: 'Prescriptions' },
  { nav: 'Labs & Results',  marker: 'Laboratory test results for all your patients',      title: 'Labs & Results' },
  { nav: 'Messages',        marker: 'Conversations with your patients and their caregivers.', title: 'Messages' },
  { nav: 'Notifications',   marker: /^(New message|Urgent message|No notifications)/,    title: 'Notifications' },
  { nav: 'Profile',         marker: 'Professional Details',                               title: 'Professional Details' },
];
export const DOCTOR_COMING_SOON = ['Documents', 'Referrals', 'Settings', 'Help & Support'];

export const PROXY_SECTIONS: Section[] = [
  { nav: 'Dashboard',             marker: 'Loved One Overview',                                   title: 'Loved One Overview' },
  { nav: 'Appointments',          marker: 'Appointments for your linked patients',                title: 'Appointments' },
  { nav: 'Medications',           marker: 'Prescriptions for your linked patients',               title: 'Medications' },
  { nav: 'Messages',              marker: "Message your loved ones' care teams.",                 title: 'Messages' },
  { nav: 'Notifications',         marker: /^(New message|Urgent message|No notifications)/,    title: 'Notifications' },
  { nav: 'Documents & Results',   marker: 'Lab results for your linked patients',                 title: 'Documents & Results' },
  { nav: 'Health Summary',        marker: 'Health overview for your linked patients',             title: 'Health Summary' },
  { nav: 'Profile & Settings',    marker: 'Patients you have been authorised to manage as proxy', title: 'Linked Patients' },
  { nav: 'Notification Settings', marker: 'Choose how you receive notifications',                 title: 'Notification Settings' },
];
export const PROXY_COMING_SOON = ['Care Plans'];

/** The left-sidebar button for a section (its name may carry an unread suffix). */
export function navButton(page: Page, label: string): Locator {
  return page.getByRole('button', { name: new RegExp(`^${escapeRe(label)}(, \\d+ unread)?$`) }).first();
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
