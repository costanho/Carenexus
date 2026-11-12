import { type APIRequestContext, expect, request } from '@playwright/test';
import { API_URL, type DemoUser, JAMES, SARAH, THOMAS } from './env';

// Thin API client for test setup and server-side assertions. Tokens stay in memory and are
// never logged or attached to reports.

export interface ApiResult<T = any> { status: number; body: T }

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

/** Session flags from the sign-in response (CONTRACTS 12.4); absent members mean false. */
export interface SessionFlags {
  mustChangePassword?:  boolean;
  emailVerified?:       boolean;
  legalActionRequired?: boolean;
  mfaEnabled?:          boolean;
}

export class Api {
  private token = '';
  systemId = '';
  /** The role the server reports for the signed-in account (`user.role` of the login response: lower-case, CAREGIVER as proxy). */
  role = '';
  /** Flags of the session this client signed in with. */
  flags: SessionFlags = {};

  private constructor(readonly user: DemoUser, private readonly ctx: APIRequestContext) {}

  static async login(user: DemoUser): Promise<Api> {
    const ctx = await request.newContext({ baseURL: API_URL });
    const api = new Api(user, ctx);
    const res = await ctx.post('/api/auth/login', { data: { email: user.email, password: user.password } });
    if (res.status() !== 200) await ctx.dispose();
    expect(res.status(), `login ${user.email}`).toBe(200);
    const body = await res.json();
    if (!body.tokens) {
      await ctx.dispose();
      // An MFA challenge: dev profiles keep mfa-required-roles empty, so this only happens on a misconfigured stack.
      throw new Error(`login ${user.email}: the server asked for ${body.challenge?.type ?? 'another step'} instead of issuing a session`);
    }
    api.token = body.tokens.accessToken;
    api.systemId = body.user.system_id;
    api.role = String(body.user.role ?? '');
    api.flags = body.flags ?? {};
    return api;
  }

  async dispose(): Promise<void> {
    await this.ctx.dispose();
  }

  private async call<T>(method: HttpMethod, path: string, data?: unknown, headers: Record<string, string> = {}): Promise<ApiResult<T>> {
    const res = await this.ctx.fetch(path, {
      method,
      headers: { ...headers, Authorization: `Bearer ${this.token}` },
      data: data === undefined ? undefined : data,
    });
    const text = await res.text();
    let body: any = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    return { status: res.status(), body };
  }

  get<T = any>(path: string)                   { return this.call<T>('GET', path); }
  post<T = any>(path: string, data?: unknown)  { return this.call<T>('POST', path, data ?? {}); }
  patch<T = any>(path: string, data?: unknown) { return this.call<T>('PATCH', path, data ?? {}); }
  put<T = any>(path: string, data?: unknown)   { return this.call<T>('PUT', path, data ?? {}); }
  del<T = any>(path: string)                   { return this.call<T>('DELETE', path); }

  /** Any call with extra request headers, for example `Idempotency-Key` (never Authorization). */
  send<T = any>(method: HttpMethod, path: string, options: { data?: unknown; headers?: Record<string, string> } = {}) {
    const data = options.data === undefined && method !== 'GET' && method !== 'DELETE' ? {} : options.data;
    return this.call<T>(method, path, data, options.headers ?? {});
  }

  /** A create call carrying an `Idempotency-Key` header (CONTRACTS 3.4); reuse the key to replay it. */
  postIdempotent<T = any>(path: string, data: unknown, key: string = idempotencyKey()) {
    return this.send<T>('POST', path, { data, headers: { 'Idempotency-Key': key } });
  }

  /** One page of a paged list endpoint (`page` and `size` are added to `params`). */
  page<T = any>(path: string, params: QueryParams = {}, page = 0, size = DEFAULT_PAGE_SIZE) {
    return this.get<PageResponse<T>>(withQuery(path, { ...params, page, size }));
  }

  /** Like the call helpers but fails the test unless the status is 2xx. */
  async ok<T = any>(p: Promise<ApiResult<T>>, what: string): Promise<T> {
    const r = await p;
    const b = r.body as any;
    const detail = `${what}: ${r.status} ${b && typeof b === 'object' ? b.message ?? '' : ''}`;
    expect(r.status >= 200 && r.status < 300, detail).toBe(true);
    return r.body;
  }
}

export interface Clients { thomas: Api; james: Api; sarah: Api }

export async function loginAll(): Promise<Clients> {
  const [thomas, james, sarah] = await Promise.all([Api.login(THOMAS), Api.login(JAMES), Api.login(SARAH)]);
  SARAH.systemId = sarah.systemId;
  return { thomas, james, sarah };
}

export async function disposeAll(c: Partial<Clients> | undefined): Promise<void> {
  if (!c) return;
  await Promise.all(Object.values(c).map(a => a?.dispose()));
}

// ── Caregiver consent (Thomas -> Sarah) ─────────────────────────────────────

export async function sarahGrant(thomas: Api): Promise<any> {
  const grants = await thomas.ok(thomas.get<any[]>('/api/patients/me/caregiver-access'), 'list caregiver access');
  const g = grants.find(x => (x.caregiverEmail ?? '').toLowerCase() === SARAH.email || x.caregiverSystemId === SARAH.systemId);
  expect(g, 'Thomas has a caregiver grant for Sarah (dev seed V6/V20)').toBeTruthy();
  return g;
}

/** Puts the demo grant back to "messaging + calls approved" (the state of dev seed V20). */
export async function restoreSarahConsent(thomas: Api): Promise<void> {
  const g = await sarahGrant(thomas);
  if (g.messagingAllowed && g.joinCallsAllowed) return;
  await thomas.ok(
    thomas.patch(`/api/patients/me/caregiver-access/${g.accessId}`, { canMessageCareTeam: true, canJoinCalls: true }),
    'restore Sarah consent',
  );
}

// ── Messaging ───────────────────────────────────────────────────────────────

export const BIG_ID = 2_147_483_647;

export async function privateThreadThomasJames(thomas: Api): Promise<any> {
  return thomas.ok(thomas.post('/api/communications/threads', {
    patientSystemId: THOMAS.systemId, type: 'PRIVATE', memberSystemIds: [JAMES.systemId],
  }), 'open private thread');
}

export async function markThreadRead(api: Api, threadId: number): Promise<void> {
  await api.ok(api.post(`/api/communications/threads/${threadId}/read`, { lastReadMessageId: BIG_ID }), 'mark read');
}

export async function messagesWithBody(api: Api, threadId: number, body: string): Promise<any[]> {
  const r = await api.ok(api.get(`/api/communications/threads/${threadId}/messages?limit=100`), 'list messages');
  return (r.messages as any[]).filter(m => m.body === body);
}

export function clientId(): string {
  return `e2e-${crypto.randomUUID()}`;
}

// ── Appointments and calls ──────────────────────────────────────────────────

export const E2E_REASON = 'E2E video call test';

/** Clinic wall-clock time (Africa/Harare) as an ISO LocalDateTime without zone. */
export function harareLocal(d: Date): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Harare', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(d);
  const v = (t: string) => parts.find(p => p.type === t)!.value;
  return `${v('year')}-${v('month')}-${v('day')}T${v('hour')}:${v('minute')}:00`;
}

/** Cancels VIDEO appointments left over from earlier e2e runs (identified by their reason text). */
export async function cancelLeftoverE2EAppointments(james: Api): Promise<void> {
  const list = await james.ok(james.get<any[]>(`/api/appointments/doctor/${JAMES.systemId}`), 'doctor appointments');
  for (const a of list) {
    if (a.reasonForVisit === E2E_REASON && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED')) {
      await james.del(`/api/appointments/${a.appointmentId}`);
    }
  }
}

/**
 * Doctor James books a VIDEO appointment with Thomas that starts within the next few minutes,
 * so the join window (opens 15 min before start) is already open. Waits until the room is READY.
 */
export async function createLiveableVideoAppointment(james: Api): Promise<number> {
  await cancelLeftoverE2EAppointments(james);
  const now = Date.now();
  // First 5-minute boundary at least one minute ahead, then later slots if that one is taken.
  let start = Math.ceil((now + 60_000) / 300_000) * 300_000;
  let lastError = '';
  for (let i = 0; i < 3; i++, start += 300_000) {
    const r = await james.post('/api/appointments', {
      patientSystemId: THOMAS.systemId,
      doctorSystemId:  JAMES.systemId,
      scheduledAt:     harareLocal(new Date(start)),
      durationMinutes: 30,
      type:            'VIDEO',
      reasonForVisit:  E2E_REASON,
    });
    if (r.status === 201 || r.status === 200) {
      const id = r.body.appointmentId as number;
      expect(r.body.callJoinRoute).toBe(`/dashboard/call/${id}`);
      expect(r.body.videoConsultationLink ?? null).toBeNull();
      await waitForCallStatus(james, id, s => s.callStatus === 'READY', 'room READY');
      return id;
    }
    lastError = `${r.status} ${r.body?.message ?? ''}`;
  }
  throw new Error(`Could not book a video appointment: ${lastError}`);
}

export async function callStatus(api: Api, appointmentId: number): Promise<ApiResult> {
  return api.get(`/api/appointments/${appointmentId}/call`);
}

export async function waitForCallStatus(
  api: Api, appointmentId: number, pred: (s: any) => boolean, what: string, timeoutMs = 45_000,
): Promise<any> {
  let last: any = null;
  await expect.poll(async () => {
    const r = await callStatus(api, appointmentId);
    last = r.body;
    return r.status === 200 && pred(r.body);
  }, { message: `call status: ${what} (last: ${JSON.stringify({ callStatus: last?.callStatus, me: last?.myParticipant?.status })})`, timeout: timeoutMs, intervals: [1000] })
    .toBe(true);
  return last;
}

/** Doctor admits the open request of `systemLabel` (matched by label). */
export async function admitByLabel(james: Api, appointmentId: number, label: string): Promise<void> {
  const s = await waitForCallStatus(
    james, appointmentId,
    x => x.participants?.some((p: any) => p.label === label && p.status === 'REQUESTED'),
    `${label} REQUESTED`,
  );
  const p = s.participants.find((x: any) => x.label === label && x.status === 'REQUESTED');
  await james.ok(james.post(`/api/appointments/${appointmentId}/call/admissions/${p.participantId}/admit`), `admit ${label}`);
}

// ── Paged lists (CONTRACTS 3.2) ─────────────────────────────────────────────

/** The pagination envelope every new list endpoint returns. */
export interface PageResponse<T> {
  content:       T[];
  page:          number;
  size:          number;
  totalElements: number;
  totalPages:    number;
}

export type QueryValue = string | number | boolean | null | undefined | readonly (string | number)[];
export type QueryParams = Record<string, QueryValue>;

export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 100;

/** `path` with the defined params appended as a query string (arrays become comma-separated values). */
export function withQuery(path: string, params: QueryParams = {}): string {
  const parts: string[] = [];
  for (const [name, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    const text = Array.isArray(value) ? value.join(',') : String(value);
    parts.push(`${encodeURIComponent(name)}=${encodeURIComponent(text)}`);
  }
  if (!parts.length) return path;
  return `${path}${path.includes('?') ? '&' : '?'}${parts.join('&')}`;
}

/**
 * Every row of a paged list endpoint, page by page (size `pageSize`, at most `maxPages` pages). Fails the
 * test when a page is not 2xx or the body is not the pagination envelope.
 */
export async function fetchAllPages<T = any>(
  api: Api, path: string, params: QueryParams = {},
  { pageSize = MAX_PAGE_SIZE, maxPages = 50 }: { pageSize?: number; maxPages?: number } = {},
): Promise<T[]> {
  const rows: T[] = [];
  for (let page = 0; page < maxPages; page++) {
    const body = await api.ok(api.page<T>(path, params, page, pageSize), `GET ${withQuery(path, params)} page ${page}`);
    expect(Array.isArray(body?.content), `${path}: paged envelope (content array)`).toBe(true);
    rows.push(...body.content);
    if (page + 1 >= body.totalPages || body.content.length === 0) return rows;
  }
  throw new Error(`${path}: more than ${maxPages} pages of ${pageSize}; narrow the filters`);
}

/** Just the total of a paged list (one row requested). */
export async function pagedTotal(api: Api, path: string, params: QueryParams = {}): Promise<number> {
  const body = await api.ok(api.page(path, params, 0, 1), `GET ${withQuery(path, params)} total`);
  return body.totalElements;
}

/**
 * The canonical paged list of each clinical resource (CONTRACTS 3.2). Each accepts `patientSystemId` and
 * `doctorSystemId` plus the filters of its slice contract. The legacy array endpoints used by the older helpers
 * above (`/api/appointments/doctor/{id}` and friends) still exist, capped at 200 rows.
 */
export const CANONICAL_LISTS = {
  appointments:   '/api/appointments',
  consultations:  '/api/consultations',
  prescriptions:  '/api/prescriptions',
  labResults:     '/api/lab-results',
  imagingResults: '/api/imaging-results',
  medicalRecords: '/api/medical-records',
} as const;
export type CanonicalList = keyof typeof CANONICAL_LISTS;

/** Doctor lists that page under their own path (CONTRACTS 3.2); the caller is the doctor. */
export const DOCTOR_PAGED_LISTS = {
  patients:     '/api/doctors/patients/search',
  appointments: '/api/doctors/appointments/search',
  schedule:     '/api/doctors/schedule/window',
} as const;

/** All rows of a canonical list visible to `api` with the given filters. */
export function listAll<T = any>(api: Api, list: CanonicalList, filters: QueryParams = {}): Promise<T[]> {
  return fetchAllPages<T>(api, CANONICAL_LISTS[list], filters);
}

export const listAppointments   = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'appointments', filters);
export const listConsultations  = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'consultations', filters);
export const listPrescriptions  = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'prescriptions', filters);
export const listLabResults     = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'labResults', filters);
export const listImagingResults = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'imagingResults', filters);
export const listMedicalRecords = <T = any>(api: Api, filters: QueryParams = {}) => listAll<T>(api, 'medicalRecords', filters);

/**
 * Cancels the doctor's active appointments whose reason starts with `reasonPrefix` (default: any e2e marker),
 * read from the canonical paged list. Returns how many were cancelled.
 */
export async function cancelE2EAppointments(api: Api, filters: QueryParams, reasonPrefix = /^e2e/i): Promise<number> {
  const rows = await listAppointments(api, { status: 'SCHEDULED,RESCHEDULED', ...filters });
  let cancelled = 0;
  for (const a of rows) {
    if (!reasonPrefix.test(String(a.reasonForVisit ?? ''))) continue;
    const r = await api.del(`/api/appointments/${a.appointmentId}`);
    if (r.status < 300) cancelled++;
  }
  return cancelled;
}

// ── Idempotency keys (CONTRACTS 3.4) ────────────────────────────────────────

/** A fresh Idempotency-Key (8..64 of [A-Za-z0-9_-]) carrying the e2e marker, so the cleanup can find it. */
export function idempotencyKey(): string {
  return `e2e-${crypto.randomUUID()}`;
}

// ── Legal acceptance (be-account-legal) ─────────────────────────────────────

export interface LegalVersions { terms: string; privacy: string }

/**
 * The current Terms and Privacy versions from the public endpoint, or null when the server has no legal
 * documents yet (404: an older backend).
 */
export async function currentLegalVersions(): Promise<LegalVersions | null> {
  const ctx = await request.newContext({ baseURL: API_URL });
  try {
    const res = await ctx.get('/api/legal/current');
    if (res.status() === 404) return null;
    expect(res.status(), 'GET /api/legal/current').toBe(200);
    const body = await res.json();
    return { terms: body.terms.version, privacy: body.privacy.version };
  } finally {
    await ctx.dispose();
  }
}

/**
 * Accepts every current legal document the signed-in user has not accepted yet, so a UI sign-in goes straight
 * to the dashboard instead of /auth/accept-terms. Returns how many documents were accepted (0 when nothing was
 * pending or the server has no legal endpoints).
 */
export async function acceptPendingLegal(api: Api): Promise<number> {
  const status = await api.get('/api/users/me/legal');
  if (status.status === 404) return 0;
  expect(status.status, `GET /api/users/me/legal as ${api.user.email}`).toBe(200);
  let accepted = 0;
  for (const doc of (status.body?.pending ?? []) as { docType: string; version: string }[]) {
    await api.ok(api.post('/api/users/me/legal/accept', { docType: doc.docType, version: doc.version }), `accept ${doc.docType} ${doc.version}`);
    accepted++;
  }
  if (accepted) api.flags = { ...api.flags, legalActionRequired: false };
  return accepted;
}
