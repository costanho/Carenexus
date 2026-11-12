import { type Browser, type BrowserContext, expect, type Page, request } from '@playwright/test';
import { type RegisteredUser, throwawayPassword } from '../support/accounts';
import { type Api, fetchAllPages, type QueryParams, withQuery } from '../support/api';
import { API_URL, type DemoUser, ROLE_LOGIN_LABEL, throwawayEmail } from '../support/env';
import { escapeRe, LOGIN_PATH, login, mediaPermissions } from '../support/ui';

// Helpers shared by the account, administration, caregiver-access and billing flow specs (flow-*.spec.ts).
// Test data follows CONTRACTS section 13: free text starts with the run tag (`e2e-…`), throwaway accounts are
// e2e-…@carenexus.test, and scripts/dev-reset-e2e-data.sh removes both. Passwords and tokens stay in memory and
// are never logged or attached to a report.

export const REGISTER_PATH = '/auth/register/web/register-web';
export const FORGOT_PASSWORD_PATH = '/auth/forgot-password';
export const RESET_PASSWORD_PATH = '/auth/reset-password';
export const VERIFY_EMAIL_PATH = '/auth/verify-email';

// ── Names and free text ─────────────────────────────────────────────────────

/** `n` random lower-case letters: a unique, letters-only part of a person's name (names refuse digits). */
export function letters(n = 6): string {
  const abc = 'abcdefghijklmnopqrstuvwxyz';
  let out = '';
  for (let i = 0; i < n; i++) out += abc[Math.floor(Math.random() * abc.length)];
  return out;
}

/** A capitalised unique surname, so two throwaway accounts of one run never share a display name. */
export function uniqueSurname(prefix: string): string {
  const tail = letters(6);
  return `${prefix}${tail.charAt(0).toUpperCase()}${tail.slice(1)}`;
}

/** Free text carrying the run tag first, which is how the cleanup script recognises it. */
export function marked(tag: string, text: string): string {
  return `${tag} ${text}`;
}

// ── Raw sign-in (status and body, no assertion) ─────────────────────────────

export interface LoginAttempt { status: number; body: any }

/** POST /api/auth/login without failing the test: for refusals (pending, rejected, closed, wrong password). */
export async function tryLogin(email: string, password: string): Promise<LoginAttempt> {
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/login', { data: { email, password } });
    const text = await res.text();
    let body: any = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    return { status: res.status(), body };
  } finally {
    await ctx.dispose();
  }
}

/** A session opened through the API whose refresh token is kept, to prove later that it was ended. */
export interface RawSession { accessToken: string; refreshToken: string; sessionId: string; systemId: string }

export async function openSession(user: Pick<DemoUser, 'email' | 'password'>): Promise<RawSession> {
  const attempt = await tryLogin(user.email, user.password);
  expect(attempt.status, `sign in ${user.email}`).toBe(200);
  expect(attempt.body?.tokens, `sign in ${user.email} issued a session (no second step)`).toBeTruthy();
  return {
    accessToken:  attempt.body.tokens.accessToken,
    refreshToken: attempt.body.tokens.refreshToken,
    sessionId:    attempt.body.tokens.sessionId ?? '',
    systemId:     attempt.body.user?.system_id ?? '',
  };
}

/** Status of POST /api/auth/refresh with the session's refresh token: 200 while it lives, 401 once it was ended. */
export async function refreshStatus(session: RawSession): Promise<number> {
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post('/api/auth/refresh', { data: { refreshToken: session.refreshToken } });
    if (res.status() === 200) {
      // The refresh token rotates: keep the new one so a later check uses the live token, not a reused one.
      const body = await res.json().catch(() => null);
      if (body?.tokens?.refreshToken) session.refreshToken = body.tokens.refreshToken;
      if (body?.tokens?.accessToken) session.accessToken = body.tokens.accessToken;
    }
    return res.status();
  } finally {
    await ctx.dispose();
  }
}

/** A public (unauthenticated) API call, for the account endpoints a signed-out person uses. */
export async function publicPost(path: string, data: unknown): Promise<{ status: number; body: any }> {
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.post(path, { data });
    const text = await res.text();
    let body: any = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    return { status: res.status(), body };
  } finally {
    await ctx.dispose();
  }
}

// ── Browser pages ───────────────────────────────────────────────────────────

export interface FlowPage { context: BrowserContext; page: Page }

/**
 * A page in its own browser context (its own session). Signs in through the sign-in page when `user` is given.
 * window.confirm prompts are accepted (the web app asks before destructive actions).
 */
export async function flowPage(browser: Browser, user?: DemoUser): Promise<FlowPage> {
  const context = await browser.newContext({ permissions: mediaPermissions(browser) });
  const page = await context.newPage();
  page.on('dialog', d => { d.accept().catch(() => {}); });
  if (user) await login(page, user);
  return { context, page };
}

export async function closeAll(pages: FlowPage[]): Promise<void> {
  for (const p of pages.splice(0)) await p.context.close().catch(() => {});
}

/** The stored session tokens of a page (sessionStorage), to prove a sign-out cleared them. */
export async function storedTokens(page: Page): Promise<(string | null)[]> {
  return page.evaluate(() => [sessionStorage.getItem('carenexus_access_token'), sessionStorage.getItem('carenexus_refresh_token')]);
}

/** Fills the sign-in form for `user` (role tab included) and presses Sign in; returns the login response. */
export async function submitSignIn(page: Page, user: Pick<DemoUser, 'email' | 'password' | 'role'>): Promise<{ status: number; body: any }> {
  await page.goto(LOGIN_PATH);
  await page.getByRole('radio', { name: ROLE_LOGIN_LABEL[user.role], exact: true }).click();
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill(user.password);
  const res = page.waitForResponse(r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  const response = await res;
  const body = await response.json().catch(() => null);
  return { status: response.status(), body };
}

/** Waits until a page that held a session has been sent back to the sign-in page. */
export async function expectSignedOut(page: Page, what: string): Promise<void> {
  await expect(page, what).toHaveURL(new RegExp(`${escapeRe(LOGIN_PATH)}`), { timeout: 45_000 });
  await expect.poll(() => storedTokens(page), { message: `${what}: stored tokens cleared`, timeout: 15_000 }).toEqual([null, null]);
}

// ── Registration through the web form ───────────────────────────────────────

export interface UiRegistration {
  role:       'patient' | 'doctor' | 'proxy';
  tag:        string;
  firstName?: string;
  lastName?:  string;
  /** Doctor only. */
  licenseNo?:      string;
  specialization?: string;
  licensingBody?:  string;
}

const REGISTER_ROLE_LABEL: Record<UiRegistration['role'], string> = {
  patient: 'Patient',
  doctor:  'Doctor',
  proxy:   'Proxy / Carer',
};

/** A nine-digit local number for the phone field (the form adds the country code). */
export function throwawayPhone(): string {
  return `77${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`;
}

/**
 * Registers a throwaway account through /auth/register (consent ticked, so the current Terms and Privacy
 * versions are sent) and waits for the "Check your inbox" view. Returns the account and the request body the
 * form sent (passwords removed).
 */
export async function registerThroughUi(page: Page, reg: UiRegistration): Promise<{ user: RegisteredUser; sent: Record<string, unknown> }> {
  const email = throwawayEmail(reg.tag);
  const password = throwawayPassword();
  const firstName = reg.firstName ?? 'E2e';
  const lastName = reg.lastName ?? uniqueSurname(REGISTER_ROLE_LABEL[reg.role].replace(/[^A-Za-z]/g, ''));

  await page.goto(REGISTER_PATH);
  await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
  const field = (label: string) => page.getByLabel(label, { exact: true }).filter({ visible: true });

  await page.getByRole('radio', { name: new RegExp(`^${escapeRe(REGISTER_ROLE_LABEL[reg.role])}\\b`) }).click();
  await field('First Name').fill(firstName);
  await field('Last Name').fill(lastName);
  await field('Email Address').fill(email);
  await page.getByPlaceholder('Phone number', { exact: true }).filter({ visible: true }).fill(throwawayPhone());
  if (reg.role === 'doctor') {
    await expect(page.getByRole('group', { name: 'Clinician details' })).toBeVisible();
    await field('Licence number').fill(reg.licenseNo ?? `E2E-${reg.tag.replace(/^e2e-/i, '').toUpperCase().slice(0, 40)}`);
    await field('Specialization').fill(reg.specialization ?? 'General Practice');
    if (reg.licensingBody) await field('Licensing body (optional)').fill(reg.licensingBody);
  }
  await field('Password').fill(password);
  await field('Confirm Password').fill(password);
  const consent = page.getByRole('checkbox', { name: /I agree to the Terms of Service/ });
  await consent.click();
  await expect(consent).toHaveAttribute('aria-checked', 'true');

  const posted = page.waitForRequest(r => r.url().endsWith('/api/auth/register') && r.method() === 'POST');
  const answered = page.waitForResponse(r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Create Account', exact: true }).click();
  const sentRaw = (await posted).postDataJSON() as Record<string, unknown>;
  const response = await answered;
  const text = await response.text();
  expect(response.status(), `register ${reg.role} ${email}: ${text.slice(0, 300)}`).toBe(201);
  const json = JSON.parse(text);
  await expect(page.getByRole('heading', { name: 'Check your inbox' })).toBeVisible();

  const sent = Object.fromEntries(Object.entries(sentRaw).filter(([key]) => !/password/i.test(key)));
  return {
    user: {
      email, password, role: reg.role, systemId: json.user?.system_id ?? '', name: `${firstName} ${lastName}`,
      tag: reg.tag, firstName, lastName, message: json.message ?? '',
    },
    sent,
  };
}

// ── Notifications ───────────────────────────────────────────────────────────

/** Every visible notification of the caller (newest first), read page by page. */
export async function notificationsOf(api: Api, params: QueryParams = {}): Promise<any[]> {
  const rows: any[] = [];
  for (let page = 0; page < 10; page++) {
    const body = await api.ok(api.get(withQuery('/api/notifications', { ...params, page, size: 50 })), 'list notifications');
    rows.push(...(body.content ?? []));
    if (page + 1 >= (body.totalPages ?? 0) || (body.content ?? []).length === 0) break;
  }
  return rows;
}

/** Waits until the caller has at least one notification matching `pred`, and returns the matches. */
export async function waitForNotifications(api: Api, pred: (n: any) => boolean, what: string, timeoutMs = 20_000): Promise<any[]> {
  let found: any[] = [];
  await expect.poll(async () => {
    found = (await notificationsOf(api)).filter(pred);
    return found.length;
  }, { message: `notification for ${api.user.email}: ${what}`, timeout: timeoutMs, intervals: [500, 1000, 2000] }).toBeGreaterThan(0);
  return found;
}

// ── Appointments ────────────────────────────────────────────────────────────

/** Clinic calendar date (Africa/Harare) `days` from now, YYYY-MM-DD. */
export function clinicDate(days: number): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Africa/Harare', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(Date.now() + days * 86_400_000));
}

/** The name the booking calendar gives a day button ("Monday, November 16, 2026"). */
export function calendarDayName(isoDate: string): string {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString('en-US', {
    timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
  });
}

export interface FreeSlot { date: string; start: string; time: string }

/**
 * The first free start time of a doctor on a weekday between `fromDays` and `toDays` days ahead, read from
 * GET /api/appointments/doctor/{id}/slots (the doctor's template, or clinic hours when there is none).
 */
export async function firstFreeSlot(
  api: Api, doctorSystemId: string, { fromDays = 2, toDays = 28, durationMinutes = 30, skip = new Set<string>() } = {},
): Promise<FreeSlot> {
  for (let days = fromDays; days <= toDays; days++) {
    const date = clinicDate(days);
    const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
    if (weekday === 0 || weekday === 6) continue;
    const r = await api.get(withQuery(`/api/appointments/doctor/${encodeURIComponent(doctorSystemId)}/slots`, { date, durationMinutes }));
    expect(r.status, `slots of ${doctorSystemId} on ${date}`).toBe(200);
    const slot = ((r.body?.slots ?? []) as { start: string; available: boolean }[])
      .find(s => s.available && !skip.has(s.start.slice(0, 16)));
    if (slot) return { date, start: slot.start, time: slot.start.slice(11, 16) };
  }
  throw new Error(`${doctorSystemId} has no free slot between ${fromDays} and ${toDays} days ahead`);
}

/** Books an in-person appointment through the API at the first free slot; returns its id. */
export async function bookFirstFreeSlot(
  api: Api, patientSystemId: string, doctorSystemId: string, reason: string, opts: { fromDays?: number; skip?: Set<string> } = {},
): Promise<{ appointmentId: number; slot: FreeSlot }> {
  let lastError = '';
  const skip = new Set(opts.skip ?? []);
  for (let attempt = 0; attempt < 4; attempt++) {
    const slot = await firstFreeSlot(api, doctorSystemId, { fromDays: opts.fromDays ?? 2, skip });
    const r = await api.post('/api/appointments', {
      patientSystemId, doctorSystemId, facilityId: null, scheduledAt: slot.start,
      durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: reason,
    });
    if (r.status === 201 || r.status === 200) return { appointmentId: r.body.appointmentId as number, slot };
    lastError = `${r.status} ${r.body?.message ?? ''}`;
    skip.add(slot.start.slice(0, 16));
  }
  throw new Error(`could not book ${patientSystemId} with ${doctorSystemId}: ${lastError}`);
}

/** Cancels the given appointments (cleanup; failures ignored). */
export async function cancelAppointments(api: Api | undefined, ids: number[]): Promise<void> {
  if (!api) return;
  for (const id of ids) await api.del(`/api/appointments/${id}`).catch(() => null);
}

// ── Caregiver grants ────────────────────────────────────────────────────────

export type GrantFlags = Partial<Record<
  | 'canViewRecords' | 'canViewAppointments' | 'canViewMedications' | 'canViewLabResults' | 'canViewImaging'
  | 'canViewConsultations' | 'canViewBilling' | 'canScheduleAppointments' | 'canManageCarePlan' | 'canManageBilling'
  | 'canMessageCareTeam' | 'canJoinCalls', boolean>>;

/** The patient grants a caregiver access (POST /api/patients/me/caregiver-access); returns the grant. */
export async function grantCaregiver(
  patient: Api, caregiverEmail: string, accessLevel: 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM', flags: GrantFlags, relationship = 'e2e relative',
): Promise<any> {
  return patient.ok(patient.post('/api/patients/me/caregiver-access', {
    caregiverEmail, relationship, accessLevel, ...flags,
  }), `grant ${caregiverEmail} ${accessLevel}`);
}

// ── Administrator actions through the API ───────────────────────────────────

/** Deactivates an account (POST /api/admin/users/{id}/deactivate) with a reason carrying the run tag. */
export async function deactivateAccount(admin: Api, systemId: string, reason: string): Promise<any> {
  return admin.ok(admin.post(`/api/admin/users/${encodeURIComponent(systemId)}/deactivate`, { reason }), `deactivate ${systemId}`);
}

/** Every audit row an administrator can see for the filters (GET /api/admin/audit-logs). */
export function auditRows(admin: Api, filters: QueryParams): Promise<any[]> {
  return fetchAllPages(admin, '/api/admin/audit-logs', filters, { pageSize: 100, maxPages: 5 });
}
