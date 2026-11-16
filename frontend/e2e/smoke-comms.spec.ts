import { expect, type Page, test } from '@playwright/test';
import {
  type Api, type Clients, cancelLeftoverE2EAppointments, clientId, createLiveableVideoAppointment, disposeAll, loginAll,
  privateThreadThomasJames, restoreSarahConsent,
} from './support/api';
import { type DemoUser, JAMES, LABELS, SARAH, THOMAS } from './support/env';
import { escapeRe, openMessages } from './support/ui';
import { type MonitoredPage, apiIdle, expectNoArtifacts, signedInPage, smokeTag } from './support/smoke';

// Smoke: notification bell (open, activate, mark all read) for each role; a quick messaging pass
// for each role (new conversation, send, receive a reply, archive and move back to the inbox); and
// the dashboard "Join video call" button for each role reaching the call lobby.
//
// Tables written: e2e-smoke-… care team conversations (message_threads, thread_members, messages) and the
// notifications their messages raise (read and read-all update notifications.read_at); an "E2E video call test"
// VIDEO appointment with its call_sessions and call_jobs (cancelled in afterAll); refresh_tokens and audit_log for
// every sign-in. scripts/dev-reset-e2e-data.sh removes the tagged rows.

/** The top-navbar bell (it has aria-expanded; the sidebar "Notifications" item does not). */
const bell = (page: Page) => page.getByRole('button', { name: /^Notifications(, \d+ unread)?$/, expanded: false });

async function unreadNotifications(api: Api): Promise<any[]> {
  const r = await api.ok(api.get('/api/notifications?unreadOnly=true&page=0&size=50'), 'unread notifications');
  return r.content as any[];
}

test.describe('smoke communications', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) await cancelLeftoverE2EAppointments(api.james).catch(() => {});
    await disposeAll(api);
  });

  test('notification bell opens, activating a notification marks it read, mark all as read', async ({ browser }) => {
    const tag = smokeTag();
    // Fresh unread MESSAGE notifications for everyone in at least TWO care team threads (Thomas, Dr. Banda,
    // Sarah). Opening a notification opens its thread, which marks that whole thread read on the server; the
    // second thread keeps unread notifications, so "Mark all as read" is always exercised (never skipped).
    // Posting a message also marks the SENDER's own unread MESSAGE notifications of that thread read (up to the
    // new message), so a user who replies in a thread loses its notifications. Each thread therefore has a
    // single sender: in two threads only Thomas posts (Dr. Banda and Sarah notified), in two only Dr. Banda
    // posts (Thomas and Sarah notified). Every user ends up with unread message notifications in two or more
    // threads, so after the newest one's thread is opened at least one other thread stays unread.
    const senders: [Api, string][] = [[api.thomas, 'patient'], [api.thomas, 'patient'], [api.james, 'doctor'], [api.james, 'doctor']];
    for (const [i, [sender, who]] of senders.entries()) {
      const n = i + 1;
      const thread = await api.thomas.ok(api.thomas.post('/api/communications/threads', {
        patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject: `${tag} bell ${n}`,
        memberSystemIds: [JAMES.systemId, SARAH.systemId], clientThreadId: `${tag}-${n}`,
      }), `create thread ${n}`);
      await sender.ok(sender.post(`/api/communications/threads/${thread.threadId}/messages`, { clientMessageId: clientId(), body: `${tag} ${n} from ${who}` }), `${who} message ${n}`);
    }

    for (const [user, client] of [[THOMAS, api.thomas], [JAMES, api.james], [SARAH, api.sarah]] as [DemoUser, Api][]) {
      await test.step(user.role, async () => {
        const unread = await unreadNotifications(client);
        expect(unread.length, `${user.role} has unread notifications in two threads`).toBeGreaterThan(1);
        expect(new Set(unread.filter(n => n.threadId).map(n => n.threadId)).size, `${user.role} unread notifications span two threads`).toBeGreaterThan(1);
        // The newest message notification (a sign-in alert from another browser may also be unread).
        const newest = unread.find(n => n.type === 'MESSAGE') ?? unread[0];

        const up = await signedInPage(browser, user);
        pages.push(up);
        const { page, monitor } = up;
        await expect(bell(page)).toHaveAccessibleName(/^Notifications, \d+ unread$/, { timeout: 20_000 });
        await bell(page).click();
        await expect(page.getByRole('button', { name: /^Notifications/, expanded: true })).toBeVisible();
        await expect(page.getByRole('heading', { name: 'Notifications', exact: true }).first()).toBeVisible();
        // The row of the newest message notification ("<title>, <time>, <message>, unread"). Another unread row
        // (for example a new sign-in alert) may sit above it, so it is found by its own title and message.
        expect(newest.type, 'newest notification is the message just sent').toBe('MESSAGE');
        const row = page.getByRole('list', { name: 'Notifications' })
          .getByRole('button', { name: new RegExp(`^${escapeRe(newest.title)}, .*${escapeRe(newest.message)}, unread$`) }).first();
        await expect(row).toBeVisible();
        await expectNoArtifacts(page, `${user.role} notifications panel`);

        // Activating the newest unread notification marks it read and (for a message) opens the thread.
        const marked = page.waitForResponse(r => /\/api\/notifications\/\d+\/read$/.test(r.url()) && r.request().method() === 'POST');
        await row.click();
        expect((await marked).status()).toBeLessThan(300);
        await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
        await expect.poll(async () => (await unreadNotifications(client)).some(n => n.notificationId === newest.notificationId),
          { message: 'newest notification marked read' }).toBe(false);

        // Mark all as read from the panel.
        await bell(page).click();
        await expect(page.getByRole('list', { name: 'Notifications' }).first()).toBeVisible();
        // The other thread's notifications are still unread, so the panel must offer "Mark all as read".
        expect((await client.get('/api/notifications/unread-count')).body.unreadCount, 'unread notifications remain').toBeGreaterThan(0);
        await expect(page.getByRole('list', { name: 'Notifications' }).getByRole('button', { name: /, unread$/ }).first()).toBeVisible();
        const markAll = page.getByRole('button', { name: 'Mark all as read', exact: true }).first();
        await expect(markAll, 'Mark all as read offered while unread notifications exist').toBeVisible();
        const all = page.waitForResponse(r => r.url().endsWith('/api/notifications/read-all') && r.request().method() === 'POST');
        await markAll.click();
        expect((await all).status()).toBeLessThan(300);
        await expect.poll(async () => (await client.get('/api/notifications/unread-count')).body.unreadCount, { message: 'unread count 0' }).toBe(0);
        await expect(page.getByRole('list', { name: 'Notifications' }).getByRole('button', { name: /, unread$/ })).toHaveCount(0);
        await page.keyboard.press('Escape');
        await expect(bell(page)).toHaveAccessibleName('Notifications', { timeout: 40_000 }); // badge polls every 30 s
        monitor.expectClean(`${user.role} notifications`);
      });
    }
  });

  test('messaging quick pass for each role: new conversation, send, receive, archive, back to inbox', async ({ browser }) => {
    // `add`: the person ticked under "Add people" (a care team conversation needs at least one).
    const cases: { user: DemoUser; me: Api; replier: Api; add: string }[] = [
      { user: THOMAS, me: api.thomas, replier: api.james,  add: LABELS.doctor },
      { user: JAMES,  me: api.james,  replier: api.thomas, add: THOMAS.name },
      { user: SARAH,  me: api.sarah,  replier: api.james,  add: LABELS.doctor },
    ];
    for (const c of cases) {
      await test.step(c.user.role, async () => {
        const tag = smokeTag();
        const subject = `${tag} ${c.user.role}`;
        const first = `${tag} first message from ${c.user.role}`;
        const up = await signedInPage(browser, c.user);
        pages.push(up);
        const { page, monitor } = up;
        await openMessages(page);

        // New conversation.
        await page.getByRole('button', { name: 'New conversation', exact: true }).first().click();
        await expect(page.getByRole('heading', { name: 'New conversation' })).toBeVisible();
        // Doctor and caregiver first choose who the conversation is about (the first radio group).
        if (c.user.role === 'doctor') await page.getByLabel('Search patients').fill('Thomas');
        if (c.user.role !== 'patient') await page.getByRole('radio', { name: THOMAS.name, exact: true }).first().click();
        // Type radios are named "<type>, <description>"; people are named "<name>, <role>".
        await page.getByRole('radio', { name: /^Care team, / }).click();
        await page.getByRole('checkbox', { name: new RegExp(`^${escapeRe(c.add)}, `) }).click();
        await page.getByLabel('Subject, optional').fill(subject);
        await page.getByLabel('First message, optional').fill(first);
        const created = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
        await page.getByRole('button', { name: 'Start conversation' }).click();
        const res = await created;
        expect(res.status(), 'thread created').toBeLessThan(300);
        const threadId = (await res.json()).threadId as number;
        await expect(page.getByRole('heading', { name: subject, exact: true })).toBeVisible();
        await expect(page.getByText(first, { exact: true })).toBeVisible();

        // The other side received it (server), and their reply shows up here (5 s polling).
        await expect.poll(async () => {
          const r = await c.replier.get(`/api/communications/threads/${threadId}/messages?limit=20`);
          return r.status === 200 && (r.body.messages as any[]).some(m => m.body === first);
        }, { message: 'recipient received the first message' }).toBe(true);
        const reply = `${tag} reply`;
        await c.replier.ok(c.replier.post(`/api/communications/threads/${threadId}/messages`, { clientMessageId: clientId(), body: reply }), 'reply');
        await expect(page.getByText(reply, { exact: true })).toBeVisible({ timeout: 12_000 });

        // Send from the composer.
        const second = `${tag} second message`;
        await page.getByLabel('Message', { exact: true }).fill(second);
        await page.getByRole('button', { name: 'Send message', exact: true }).click();
        await expect(page.getByText(second, { exact: true })).toBeVisible();
        await expect(page.getByText('Sending…')).toHaveCount(0, { timeout: 10_000 });

        // Archive, find it under Archived, move it back to the inbox.
        await page.getByRole('button', { name: 'Archive conversation' }).click();
        await page.getByRole('tab', { name: 'Archived conversations' }).click();
        const rowName = new RegExp(`^${escapeRe(subject)}, `);
        await expect(page.getByRole('list', { name: 'Archived conversations' }).getByRole('button', { name: rowName })).toBeVisible();
        await page.getByRole('button', { name: `Move ${subject} to inbox` }).click();
        await expect(page.getByRole('list', { name: 'Archived conversations' }).getByRole('button', { name: rowName })).toHaveCount(0);
        await page.getByRole('tab', { name: 'Inbox conversations' }).click();
        await expect(page.getByRole('list', { name: 'Conversations' }).getByRole('button', { name: rowName })).toBeVisible();
        const t = await c.me.ok(c.me.get(`/api/communications/threads/${threadId}`), 'thread');
        expect(t.archived, 'thread back in the inbox').toBe(false);

        await apiIdle(page);
        await expectNoArtifacts(page, `${c.user.role} messages`);
        monitor.expectClean(`${c.user.role} messaging`);
      });
    }
  });

  test('dashboard "Join video call" button reaches the call lobby for each role @chromium-only', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const lobby = new RegExp(`/dashboard/call/${id}$`);
    // Make sure the private thread exists so nothing else is needed for the caregiver.
    await privateThreadThomasJames(api.thomas);

    for (const user of [THOMAS, JAMES, SARAH]) {
      await test.step(user.role, async () => {
        const up = await signedInPage(browser, user);
        pages.push(up);
        const { page, monitor } = up;
        let join;
        if (user.role === 'patient') {
          join = page.getByRole('button', { name: `Join video call: video call with ${LABELS.doctor}` }).first();
        } else {
          // The dashboard's today list: the row for the e2e appointment with an active Join button.
          // CallJoinButton names the button '<label>: video call with <name>' (the visible label first).
          const btn = page.getByRole('button', { name: /^Join video call: video call with / });
          join = page.locator('div').filter({ has: page.getByText(/E2E video call test/) }).filter({ has: btn }).last()
            .getByRole('button', { name: /^Join video call: / });
        }
        await expect(join).toBeVisible({ timeout: 20_000 });
        await join.click();
        await expect(page).toHaveURL(lobby);
        await expect(page.getByRole('heading', { name: 'Video consultation' })).toBeVisible();
        await expect(page.getByRole('heading', { name: 'Camera and microphone' })).toBeVisible();
        await apiIdle(page);
        await expectNoArtifacts(page, `${user.role} call lobby`);
        monitor.expectClean(`${user.role} lobby`);
      });
    }
  });
});
