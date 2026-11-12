import { expect, test } from '@playwright/test';
import {
  type Clients, disposeAll, loginAll, markThreadRead, messagesWithBody, privateThreadThomasJames, restoreSarahConsent,
} from './support/api';
import { JAMES, LABELS, THOMAS, runTag } from './support/env';
import { escapeRe, newUserPage, openMessages, type UserPage } from './support/ui';

// a. Private thread Thomas (patient) <-> Dr. James Banda: delivery through polling, unread badge,
//    read marking, urgent highlight and copy, and a retry that must not create a duplicate.

const URGENT_HELPER =
  'Urgent highlights your message for the care team. It does not make a reply faster. For emergencies, call your local emergency number.';

test.describe('messaging: patient <-> doctor private thread', () => {
  let api: Clients;
  let threadId: number;
  let thomas: UserPage;
  let james: UserPage;

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
    const thread = await privateThreadThomasJames(api.thomas);
    threadId = thread.threadId;
    // Start from "everything read" on both sides so the badge assertions only see this run.
    await markThreadRead(api.thomas, threadId);
    await markThreadRead(api.james, threadId);
  });

  test.afterAll(async () => {
    await thomas?.context.close();
    await james?.context.close();
    await disposeAll(api);
  });

  test('send, receive by polling, unread badge, mark read, urgent, retry-safe send', async ({ browser }) => {
    const tag = runTag();
    thomas = await newUserPage(browser, THOMAS);
    james  = await newUserPage(browser, JAMES);

    await openMessages(thomas.page);
    await openMessages(james.page);

    // Thomas opens the private thread with Dr. James Banda.
    // Thread rows are named in visible order: title, time, type (· patient), preview, unread count.
    const thomasRow = thomas.page.getByRole('button', { name: new RegExp(`^${escapeRe(LABELS.doctor)}, .*, Private, `) });
    await thomasRow.click();
    await expect(thomas.page.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();

    // 1) Thomas sends; James (list open, thread closed) gets an unread badge on the thread row.
    const first = `Hello doctor, question ${tag}`;
    const composer = thomas.page.getByLabel('Message', { exact: true });
    await composer.fill(first);
    await thomas.page.getByRole('button', { name: 'Send message', exact: true }).click();
    await expect(thomas.page.getByText(first, { exact: true })).toBeVisible();
    await expect(thomas.page.getByText('Sending…')).toHaveCount(0, { timeout: 10_000 });

    const jamesRow = james.page.getByRole('button', { name: new RegExp(`^${escapeRe(THOMAS.name)}, .*, Private · ${escapeRe(THOMAS.name)}, `) });
    await expect(jamesRow).toHaveAccessibleName(new RegExp(`${escapeRe(first)}, 1 unread$`), { timeout: 25_000 }); // list polls every 15 s
    await expect(jamesRow.getByText('1', { exact: true })).toBeVisible(); // visible badge
    // Sidebar Messages badge (unread-count polls every 30 s).
    await expect(james.page.getByRole('button', { name: /^Messages, \d+ unread$/ })).toBeVisible({ timeout: 40_000 });

    // 2) James opens the thread: the message is there and it is marked read (server and UI).
    await jamesRow.click();
    await expect(james.page.getByText(first, { exact: true })).toBeVisible();
    await expect.poll(async () => {
      const t = await api.james.get(`/api/communications/threads/${threadId}`);
      return t.body.unreadCount;
    }, { message: 'James unread count after opening' }).toBe(0);
    await expect(jamesRow).not.toHaveAccessibleName(/unread$/, { timeout: 20_000 });
    await expect(jamesRow.getByText('1', { exact: true })).toHaveCount(0);

    // 3) Urgent: Thomas sees the exact helper copy, sends; James (thread open) gets it within the 5 s poll.
    const urgentSwitch = thomas.page.getByRole('switch', { name: 'Mark as urgent' });
    await urgentSwitch.click();
    await expect(thomas.page.getByText(URGENT_HELPER, { exact: true })).toBeVisible();
    const urgentBody = `Urgent check ${tag}`;
    await composer.fill(urgentBody);
    const sentAt = Date.now();
    await thomas.page.getByRole('button', { name: 'Send urgent message', exact: true }).click();
    const urgentBubble = james.page.getByLabel(new RegExp(`^${escapeRe(THOMAS.name)}, .*, urgent: ${escapeRe(urgentBody)}$`));
    await expect(urgentBubble).toBeVisible({ timeout: 9_000 }); // 5 s poll + render margin
    expect(Date.now() - sentAt).toBeLessThan(9_000);
    await expect(urgentBubble.getByText('Urgent', { exact: true })).toBeVisible();
    // Highlighted style: the urgent bubble uses a red border (not just the tag).
    const border = await urgentBubble.locator('div').first().evaluate(el => getComputedStyle(el).borderTopColor);
    expect(border).toBe('rgb(185, 28, 28)'); // RED (#B91C1C)
    // The helper text never claims faster replies or monitoring.
    await expect(thomas.page.getByText(/encrypt|monitor/i)).toHaveCount(0);

    // 4) Retry-safe send: the first POST reaches the server but its response is lost.
    const retryBody = `Retry safety ${tag}`;
    let intercepted = 0;
    await thomas.page.route(`**/api/communications/threads/${threadId}/messages`, async route => {
      if (route.request().method() !== 'POST' || intercepted > 0) return route.fallback();
      intercepted++;
      await route.fetch();          // the server stores the message...
      await route.abort('failed');  // ...but the browser sees a network failure
    });
    await composer.fill(retryBody);
    await thomas.page.getByRole('button', { name: 'Send message', exact: true }).click();
    await expect(thomas.page.getByText('Not sent', { exact: true })).toBeVisible();
    expect(intercepted).toBe(1);
    expect(await messagesWithBody(api.thomas, threadId, retryBody)).toHaveLength(1); // stored once already

    await thomas.page.getByRole('button', { name: 'Retry sending message' }).click();
    await expect(thomas.page.getByText('Not sent', { exact: true })).toHaveCount(0);
    await expect(thomas.page.getByText(retryBody, { exact: true })).toHaveCount(1);
    await thomas.page.unroute(`**/api/communications/threads/${threadId}/messages`);

    expect(await messagesWithBody(api.thomas, threadId, retryBody)).toHaveLength(1);
    await expect(james.page.getByText(retryBody, { exact: true })).toHaveCount(1, { timeout: 9_000 });
    // Still exactly one after another poll cycle.
    await james.page.waitForTimeout(6_000);
    await expect(james.page.getByText(retryBody, { exact: true })).toHaveCount(1);
    await expect(thomas.page.getByText(retryBody, { exact: true })).toHaveCount(1);
  });
});
