import { expect, type Locator, type Page } from '@playwright/test';
import { type Api, clientId } from './api';
import { JAMES, SARAH, THOMAS } from './env';
import { escapeRe } from './ui';

// Helpers for e2e/fix-messaging.spec.ts. Test data is prefixed 'e2e-fix-' so it is easy to spot.

export const FIX_PREFIX = 'e2e-fix-';

export function fixTag(): string {
  return `${FIX_PREFIX}${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
}

/** Thomas opens a care team thread with Dr. Banda and Sarah, with one message so it has activity. */
export async function careTeamThread(thomas: Api, subject: string): Promise<number> {
  const t = await thomas.ok(thomas.post('/api/communications/threads', {
    patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject,
    memberSystemIds: [JAMES.systemId, SARAH.systemId], clientThreadId: clientId(),
  }), `create thread ${subject}`);
  await postMessage(thomas, t.threadId, `${subject} opening message`);
  return t.threadId as number;
}

export async function postMessage(api: Api, threadId: number, body: string): Promise<void> {
  await api.ok(api.post(`/api/communications/threads/${threadId}/messages`, { clientMessageId: clientId(), body }), 'post message');
}

/** Archives each thread for every client that can see it (cleanup; errors ignored). */
export async function archiveForAll(clients: Api[], threadIds: number[]): Promise<void> {
  for (const id of threadIds) {
    for (const c of clients) await c.put(`/api/communications/threads/${id}/archive`, { archived: true }).catch(() => {});
  }
}

/** A conversation row button in the list (its accessible name starts with the title). */
export function threadRow(page: Page, title: string): Locator {
  return page.getByRole('button', { name: new RegExp(`^${escapeRe(title)}, `) }).first();
}

export function composer(page: Page): Locator {
  return page.getByLabel('Message', { exact: true });
}

export async function sendFromComposer(page: Page, text: string): Promise<void> {
  await composer(page).fill(text);
  await page.getByRole('button', { name: /^Send (urgent )?message$/ }).click();
}

/** The top-navbar bell (it has aria-expanded; the sidebar "Notifications" item does not). */
export function bell(page: Page, expanded = false): Locator {
  return page.getByRole('button', { name: /^Notifications(, \d+ unread)?$/, expanded });
}

/** True when the URL is a message poll (`after=`) for that thread. */
export function isPoll(url: string, threadId: number): boolean {
  return url.includes(`/api/communications/threads/${threadId}/messages?`) && /[?&]after=/.test(url);
}

/** True when the request is the POST that sends a message to that thread. */
export function isSend(method: string, url: string, threadId: number): boolean {
  return method === 'POST' && new URL(url).pathname === `/api/communications/threads/${threadId}/messages`;
}

/** Marks document hidden (or visible again) and fires visibilitychange, as a background tab would. */
export async function setTabHidden(page: Page, hidden: boolean): Promise<void> {
  await page.evaluate(h => {
    if (h) {
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
    } else {
      delete (document as any).visibilityState;
      delete (document as any).hidden;
    }
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);
}

export async function expectFocusInside(container: Locator, what: string): Promise<void> {
  expect(await container.evaluate(el => el.contains(document.activeElement)), what).toBe(true);
}
