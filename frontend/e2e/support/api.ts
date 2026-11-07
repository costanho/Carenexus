import { type APIRequestContext, expect, request } from '@playwright/test';
import { API_URL, type DemoUser, JAMES, SARAH, THOMAS } from './env';

// Thin API client for test setup and server-side assertions. Tokens stay in memory and are
// never logged or attached to reports.

export interface ApiResult<T = any> { status: number; body: T }

export class Api {
  private token = '';
  systemId = '';

  private constructor(readonly user: DemoUser, private readonly ctx: APIRequestContext) {}

  static async login(user: DemoUser): Promise<Api> {
    const ctx = await request.newContext({ baseURL: API_URL });
    const api = new Api(user, ctx);
    const res = await ctx.post('/api/auth/login', { data: { email: user.email, password: user.password } });
    expect(res.status(), `login ${user.email}`).toBe(200);
    const body = await res.json();
    api.token = body.tokens.accessToken;
    api.systemId = body.user.system_id;
    return api;
  }

  async dispose(): Promise<void> {
    await this.ctx.dispose();
  }

  private async call<T>(method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE', path: string, data?: unknown): Promise<ApiResult<T>> {
    const res = await this.ctx.fetch(path, {
      method,
      headers: { Authorization: `Bearer ${this.token}` },
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
