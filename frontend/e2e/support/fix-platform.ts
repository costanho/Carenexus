import { type Browser, type BrowserContext, type BrowserContextOptions, expect, type Page } from '@playwright/test';
import { type Api, callStatus, harareLocal, waitForCallStatus } from './api';
import { type DemoUser, JAMES, THOMAS } from './env';
import { login, mediaPermissions } from './ui';

// Helpers for fix-platform.spec.ts (dashboard role gate, register form, device check timeout, call safety).

export const FIX_PREFIX = 'e2e-fix-platform';
export const FIX_REASON = `${FIX_PREFIX} video call safety`;

export interface FixPage { context: BrowserContext; page: Page; dialogs: string[] }

/**
 * A signed-in page WITHOUT the auto-accepting dialog handler of ui.ts: every window.confirm is recorded and
 * dismissed unless the test accepts it itself (acceptNextDialog). A confirm nobody expected therefore never
 * goes through silently.
 */
export async function fixPage(browser: Browser, user: DemoUser | null, options: BrowserContextOptions = {}): Promise<FixPage> {
  const context = await browser.newContext({ permissions: mediaPermissions(browser), ...options });
  const page = await context.newPage();
  const dialogs: string[] = [];
  page.on('dialog', d => {
    dialogs.push(d.message());
    if (!acceptNext.has(page)) d.dismiss().catch(() => {});
  });
  if (user) await login(page, user);
  return { context, page, dialogs };
}

const acceptNext = new WeakSet<Page>();

/** Accepts the next window.confirm on this page (and only that one). */
export function acceptNextDialog(page: Page): void {
  acceptNext.add(page);
  page.once('dialog', d => {
    acceptNext.delete(page);
    d.accept().catch(() => {});
  });
}

/** Every URL the main frame navigates to (client-side route changes included). */
export function trackUrls(page: Page): string[] {
  const urls: string[] = [];
  page.on('framenavigated', f => { if (f === page.mainFrame()) urls.push(new URL(f.url()).pathname); });
  return urls;
}

/** API paths requested by the page (no query strings, no headers). */
export function trackApi(page: Page): string[] {
  const paths: string[] = [];
  page.on('request', r => {
    const u = new URL(r.url());
    if (u.pathname.startsWith('/api/')) paths.push(`${r.method()} ${u.pathname}`);
  });
  return paths;
}

/**
 * Dr James books a VIDEO appointment with Thomas starting in the next few minutes (join window already open)
 * and waits until the room is READY. Tries later 5-minute slots when one is taken.
 */
export async function bookFixVideoAppointment(james: Api): Promise<number> {
  let start = Math.ceil((Date.now() + 60_000) / 300_000) * 300_000;
  let lastError = '';
  for (let i = 0; i < 8; i++, start += 300_000) {
    const r = await james.post('/api/appointments', {
      patientSystemId: THOMAS.systemId,
      doctorSystemId:  JAMES.systemId,
      scheduledAt:     harareLocal(new Date(start)),
      durationMinutes: 30,
      type:            'VIDEO',
      reasonForVisit:  FIX_REASON,
    });
    if (r.status === 201 || r.status === 200) {
      const id = r.body.appointmentId as number;
      await waitForCallStatus(james, id, s => s.callStatus === 'READY', 'room READY');
      return id;
    }
    lastError = `${r.status} ${r.body?.message ?? ''}`;
  }
  throw new Error(`Could not book a video appointment: ${lastError}`);
}

/** Ends the call if it is live and cancels the appointment (afterAll cleanup). */
export async function cleanUpFixAppointment(james: Api, id: number | undefined): Promise<void> {
  if (!id) return;
  const s = await callStatus(james, id);
  if (s.status === 200 && s.body?.callStatus === 'LIVE') await james.post(`/api/appointments/${id}/call/end`);
  await james.del(`/api/appointments/${id}`);
}

/** Cancels fix-platform appointments left by an earlier, interrupted run. */
export async function cancelLeftoverFixAppointments(james: Api): Promise<void> {
  const list = await james.ok(james.get<any[]>(`/api/appointments/doctor/${JAMES.systemId}`), 'doctor appointments');
  for (const a of list) {
    if (a.reasonForVisit === FIX_REASON && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED')) {
      await cleanUpFixAppointment(james, a.appointmentId);
    }
  }
}

/** Call status from the API, asserted to stay `expected` for `ms` (polled every second). */
export async function expectCallStatusStays(api: Api, id: number, expected: string, ms: number): Promise<void> {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    const r = await callStatus(api, id);
    expect(r.status).toBe(200);
    expect(r.body.callStatus, 'call status must not change without an explicit Start/End').toBe(expected);
    await new Promise(res => setTimeout(res, 1000));
  }
}
