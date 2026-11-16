import { expect, type Locator, type Page, type Response } from '@playwright/test';
import { registerAccount, registerApprovedDoctor, loginAdmin, type RegisteredUser } from '../support/accounts';
import { Api, harareLocal, withQuery, type QueryParams } from '../support/api';
import { JAMES, THOMAS, type LoginRole } from '../support/env';
import { escapeRe, openSectionAt } from '../support/ui';

// Shared helpers of the clinical flow specs (flow-*.spec.ts): clinic wall-clock arithmetic, the throwaway accounts
// the flows need (registered once per worker and reused, because registration is rate limited per IP), caregiver
// grants from Thomas to the throwaway caregiver, notification and API polling helpers, and small UI helpers.
//
// Every free text these flows write starts with the e2e marker (runTag() from support/env), and every throwaway
// account is e2e-…@carenexus.test, so scripts/dev-reset-e2e-data.sh removes all of it.

// ── Clinic time (Africa/Harare, UTC+2 all year, no daylight saving) ───────────────────────────────────────────

export interface ClinicNow {
  /** Clinic calendar day, YYYY-MM-DD. */
  date:    string;
  /** Clinic wall-clock time, HH:mm. */
  time:    string;
  /** Minutes since clinic midnight. */
  minutes: number;
}

export function clinicNow(): ClinicNow {
  const local = harareLocal(new Date());
  const time = local.slice(11, 16);
  return { date: local.slice(0, 10), time, minutes: toMinutes(time) };
}

/** The clinic calendar day `days` after the clinic's today. */
export function clinicDayPlus(days: number): string {
  const [y, m, d] = clinicNow().date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** ISO weekday of a YYYY-MM-DD day: 1 = Monday ... 7 = Sunday. */
export function isoWeekday(date: string): number {
  const [y, m, d] = date.split('-').map(Number);
  const js = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return js === 0 ? 7 : js;
}

/** "HH:mm" to minutes since midnight. */
export function toMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

/** Minutes since midnight to "HH:mm" (24-hour, zero padded). */
export function hhmm(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Rounds minutes up to the next multiple of `step`. */
export function roundUp(minutes: number, step: number): number {
  return Math.ceil(minutes / step) * step;
}

/** The accessible name of a day in the booking calendar ("Monday, November 16, 2026"), as formatLongDate builds it. */
export function longDateLabel(date: string): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-US', {
    timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric', year: 'numeric',
  });
}

// ── Throwaway accounts (one of each per worker) ───────────────────────────────────────────────────────────────

/** A capitalised surname of letters only: `prefix` plus six random letters (unique enough to search for). */
export function uniqueSurname(prefix: string): string {
  let letters = '';
  for (let i = 0; i < 6; i++) letters += String.fromCharCode(97 + Math.floor(Math.random() * 26));
  return `${prefix}${letters}`;
}

const shared: Partial<Record<'doctor' | 'outsider' | 'patient' | 'caregiver', Promise<RegisteredUser>>> = {};

function once(key: keyof typeof shared, make: () => Promise<RegisteredUser>): Promise<RegisteredUser> {
  if (!shared[key]) {
    shared[key] = make().catch(e => { delete shared[key]; throw e; });
  }
  return shared[key]!;
}

async function approvedDoctor(lastName: string, specialization: string): Promise<RegisteredUser> {
  const admin = await loginAdmin();
  try {
    return await registerApprovedDoctor(admin, { lastName, clinician: { specialization } });
  } finally {
    await admin.dispose();
  }
}

/**
 * An approved throwaway doctor the flows work with: its weekly template is set by the booking flow, Thomas books
 * with it, and it is the platform specialist of the referral flow. It becomes related to Thomas.
 */
export function clinicalDoctor(): Promise<RegisteredUser> {
  return once('doctor', () => approvedDoctor(uniqueSurname('Slot'), 'Cardiology'));
}

/** An approved throwaway doctor that never gets a care relationship with any demo patient. */
export function outsiderDoctor(): Promise<RegisteredUser> {
  return once('outsider', () => approvedDoctor(uniqueSurname('Out'), 'Dermatology'));
}

/** A throwaway patient with no phone number on file. */
export function throwawayPatient(): Promise<RegisteredUser> {
  return once('patient', () => registerAccount({ role: 'patient', lastName: uniqueSurname('Pat') }));
}

/** A throwaway caregiver; each flow sets the grant Thomas gives it with grantThomasAccess(). */
export function throwawayCaregiver(): Promise<RegisteredUser> {
  return once('caregiver', () => registerAccount({ role: 'proxy', lastName: uniqueSurname('Care') }));
}

// ── Caregiver grants from Thomas ───────────────────────────────────────────────────────────────────────────────

export type AccessLevel = 'FULL_ACCESS' | 'VIEW_ONLY' | 'EDIT_ONLY' | 'CUSTOM';

export const DATA_FLAGS = [
  'canViewRecords', 'canViewAppointments', 'canViewMedications', 'canViewLabResults', 'canViewImaging',
  'canViewConsultations', 'canViewBilling', 'canScheduleAppointments', 'canManageCarePlan', 'canManageBilling',
] as const;
export type DataFlag = (typeof DATA_FLAGS)[number];

export interface GrantSpec {
  accessLevel: AccessLevel;
  /** Data flags to switch on; every other data flag is sent as false (CONTRACTS: omitted flags keep their value). */
  flags?:      readonly DataFlag[];
}

/**
 * Thomas gives `caregiver` exactly the access in `spec` (POST the first time, PATCH afterwards; a revoked grant is
 * reused by the server). Returns the grant's accessId.
 */
export async function grantThomasAccess(thomas: Api, caregiver: RegisteredUser, spec: GrantSpec): Promise<number> {
  const flags: Record<string, boolean> = {};
  for (const f of DATA_FLAGS) flags[f] = !!spec.flags?.includes(f);
  const body = { accessLevel: spec.accessLevel, ...flags };
  const grants = await thomas.ok(thomas.get<any[]>('/api/patients/me/caregiver-access'), 'list caregiver access');
  const existing = grants.find(g => g.caregiverSystemId === caregiver.systemId
    || String(g.caregiverEmail ?? '').toLowerCase() === caregiver.email.toLowerCase());
  const saved = existing
    ? await thomas.ok(thomas.patch(`/api/patients/me/caregiver-access/${existing.accessId}`, body), 'update caregiver grant')
    : await thomas.ok(thomas.post('/api/patients/me/caregiver-access', {
      caregiverEmail: caregiver.email, relationship: 'friend', ...body,
    }), 'grant caregiver access');
  expect(saved.accessLevel, 'stored access level').toBe(spec.accessLevel);
  return saved.accessId as number;
}

/** Revokes a grant (idempotent), so the throwaway caregiver does not stay in Thomas's caregiver list. */
export async function revokeThomasAccess(thomas: Api, accessId: number | undefined): Promise<void> {
  if (accessId === undefined) return;
  const r = await thomas.del(`/api/patients/me/caregiver-access/${accessId}`);
  expect(r.status, 'revoke caregiver grant').toBeLessThan(300);
}

// ── API helpers ────────────────────────────────────────────────────────────────────────────────────────────────

/** Signs a throwaway account in through the API. */
export function apiLogin(user: RegisteredUser): Promise<Api> {
  return Api.login(user);
}

/** The first page (up to `size`) of a paged endpoint's content. */
export async function firstPage<T = any>(api: Api, path: string, params: QueryParams = {}, size = 50): Promise<T[]> {
  const body = await api.ok(api.page<T>(path, params, 0, size), `GET ${withQuery(path, params)}`);
  expect(Array.isArray(body?.content), `${path}: paged envelope`).toBe(true);
  return body.content;
}

/**
 * The caller's notifications about one row (`resourceType` / `resourceId`), from the newest 150. Notifications are
 * written after the change commits, so callers poll this.
 */
export async function notificationsAbout(api: Api, resourceType: string, resourceId: number): Promise<any[]> {
  const out: any[] = [];
  for (let page = 0; page < 3; page++) {
    const body = await api.ok(api.page('/api/notifications', {}, page, 50), 'list notifications');
    out.push(...(body.content as any[]).filter(n => n.resourceType === resourceType && Number(n.resourceId) === resourceId));
    if (page + 1 >= body.totalPages) break;
  }
  return out;
}

/** Clinic booking rules (GET /api/appointments/booking-rules). */
export interface BookingRules {
  openHour: number; closeHour: number; allowWeekends: boolean; maxAdvanceDays: number; videoCallsAvailable: boolean;
}

export async function bookingRules(api: Api): Promise<BookingRules> {
  const r = await api.ok(api.get<BookingRules>('/api/appointments/booking-rules'), 'booking rules');
  return {
    openHour: Number(r.openHour), closeHour: Number(r.closeHour), allowWeekends: !!r.allowWeekends,
    maxAdvanceDays: Number(r.maxAdvanceDays), videoCallsAvailable: !!r.videoCallsAvailable,
  };
}

export interface DoctorSlot { start: string; available: boolean }

/** Bookable start times of a doctor on one clinic day. */
export async function doctorSlots(api: Api, doctorSystemId: string, date: string, durationMinutes = 30): Promise<DoctorSlot[]> {
  const body = await api.ok(api.get(withQuery(`/api/appointments/doctor/${doctorSystemId}/slots`, { date, durationMinutes })), `slots ${date}`);
  return body.slots as DoctorSlot[];
}

/**
 * Dr. Banda books a 30-minute IN_PERSON visit for Thomas on the first free slot of his template 20 to 40 days ahead
 * (a doctor may book into his own schedule). Returns the appointment id.
 */
export async function jamesBooksThomas(james: Api, reason: string): Promise<number> {
  let last = '';
  for (let days = 20; days < 40; days++) {
    const date = clinicDayPlus(days);
    const slots = (await doctorSlots(james, JAMES.systemId, date)).filter(s => s.available);
    for (const slot of slots.slice(0, 4)) {
      const r = await james.post('/api/appointments', {
        patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId, scheduledAt: slot.start,
        durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: reason,
      });
      if (r.status === 201 || r.status === 200) return r.body.appointmentId as number;
      last = `${r.status} ${r.body?.message ?? ''}`;
    }
  }
  throw new Error(`Dr. Banda could not book a visit for Thomas: ${last}`);
}

/** A small but real PDF (the local scanner detects the %PDF- signature). */
export function samplePdf(label: string): Buffer {
  return Buffer.from([
    '%PDF-1.4',
    '1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj',
    '2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj',
    `% ${label.replace(/[^\x20-\x7e]/g, ' ')}`,
    'trailer << /Root 1 0 R >>',
    '%%EOF',
    '',
  ].join('\n'), 'latin1');
}

// ── UI helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

/** A modal or side panel by its title (both are dialogs named after their title). */
export function dialog(page: Page, title: string): Locator {
  return page.getByRole('dialog', { name: title, exact: true });
}

/**
 * The last element that holds `text` (exact) and `inner`, for example one card of a list, to act on a control of
 * that card. Use it only when `inner` is sure to be in the card: if it is not, an outer container matches. To
 * assert what a card shows, locate the card with cardHolding() and look inside it.
 */
export function cardOf(page: Page, text: string, inner: Locator): Locator {
  return page.locator('div').filter({ has: page.getByText(text, { exact: true }) }).filter({ has: inner }).last();
}

/**
 * The innermost elements that hold every one of `anchors` (and have no descendant that holds them all): one card
 * when the anchors are things only that card shows, such as its own reason or title and its own controls. Assert
 * the card's content inside the result (`card.getByText(...)`), so content elsewhere on the screen cannot count.
 */
export function cardHolding(page: Page, ...anchors: Locator[]): Locator {
  const holdingAll = () => anchors.reduce((l, a) => l.filter({ has: a }), page.locator('div'));
  return holdingAll().filter({ hasNot: holdingAll() });
}

/** Waits for the API response of one request (method and path, query ignored). */
export function apiResponse(page: Page, method: string, path: RegExp): Promise<Response> {
  return page.waitForResponse(r => r.request().method() === method && path.test(new URL(r.url()).pathname), { timeout: 30_000 });
}

/** Opens a dashboard section by URL and waits for its main landmark named `label` and the end of its loading state. */
export async function openAt(page: Page, role: LoginRole, section: string, label: string, params: Record<string, string | number | undefined> = {}): Promise<void> {
  await openSectionAt(page, role, section, params, label);
  await expect(page.getByText(/^Loading\b.*(…|\.\.\.)$/)).toHaveCount(0, { timeout: 20_000 });
}

/** A button whose accessible name starts with `prefix`. */
export function buttonStarting(scope: Page | Locator, prefix: string): Locator {
  return scope.getByRole('button', { name: new RegExp(`^${escapeRe(prefix)}`) });
}
