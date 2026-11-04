import { type Browser, expect, test } from '@playwright/test';
import {
  type Clients, disposeAll, loginAll, markThreadRead, messagesWithBody, privateThreadThomasJames, restoreSarahConsent,
  sarahGrant,
} from './support/api';
import { type DemoUser, JAMES, LABELS, SARAH, THOMAS } from './support/env';
import {
  archiveForAll, bell, careTeamThread, composer, expectFocusInside, fixTag, isPoll, isSend, postMessage,
  sendFromComposer, setTabHidden, threadRow,
} from './support/fix-messaging';
import { escapeRe, login, newUserPage, openMessages, type UserPage } from './support/ui';

// Messaging fixes: unsent text survives switching threads (drafts and the failed-send outbox),
// offline / unreachable-server states, hidden-tab polling pause, list preview after sending,
// archive state in the open thread's header, notification click-through and panel focus,
// new-conversation flows per role (including a caregiver-doctor private conversation the patient
// cannot open), the caregiver patient filter and the caregiver who loses messaging mid-session.

const URGENT_HELPER =
  'Urgent highlights your message for the care team. It does not make a reply faster. For emergencies, call your local emergency number.';
const OFFLINE_BANNER = "You're offline. Messages will refresh when you reconnect.";
const NO_ACCESS = 'You no longer have access to this conversation.';

test.describe('messaging fixes', () => {
  let api: Clients;
  const pages: UserPage[] = [];
  const created: number[] = [];
  let subjectA: string;
  let subjectB: string;
  let threadA: number;
  let threadB: number;

  async function open(user: DemoUser, browser: Browser): Promise<UserPage> {
    const up = await newUserPage(browser, user);
    pages.push(up);
    return up;
  }

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
    const tag = fixTag();
    subjectA = `${tag} thread A`;
    subjectB = `${tag} thread B`;
    threadA = await careTeamThread(api.thomas, subjectA);
    threadB = await careTeamThread(api.thomas, subjectB); // newest activity: B is above A
    created.push(threadA, threadB);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      await restoreSarahConsent(api.thomas).catch(() => {});
      await archiveForAll([api.thomas, api.james, api.sarah], created);
    }
    await disposeAll(api);
  });

  test('drafts survive switching threads and a reload (last keystrokes are not lost)', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    const draftA = `${fixTag()} draft for A`;
    const draftB = `${fixTag()} draft for B`;

    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();
    // Switch straight after typing, well inside the 400 ms save debounce.
    await composer(page).fill(draftA);
    await threadRow(page, subjectB).click();
    await expect(page.getByRole('heading', { name: subjectB, exact: true })).toBeVisible();
    await expect(composer(page)).toHaveValue('');
    await composer(page).fill(draftB);
    await threadRow(page, subjectA).click();
    await expect(composer(page)).toHaveValue(draftA);
    await threadRow(page, subjectB).click();
    await expect(composer(page)).toHaveValue(draftB);

    await page.reload();
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(composer(page)).toHaveValue(draftA);
    await composer(page).fill('');
  });

  test('list preview and order update right after sending (no 15 s wait)', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    const list = page.getByRole('list', { name: 'Conversations' });
    await threadRow(page, subjectA).click();
    const text = `${fixTag()} preview check`;
    await sendFromComposer(page, text);
    const firstRow = list.getByRole('button').first();
    await expect(firstRow).toHaveAccessibleName(new RegExp(`^${escapeRe(subjectA)}, .*You: ${escapeRe(text)}`), { timeout: 5_000 });
  });

  test('a failed send survives switching threads, and Retry sends it once with the same clientMessageId', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    const text = `${fixTag()} unsent message`;
    const ids: string[] = [];
    const sends = (u: URL) => u.pathname === `/api/communications/threads/${threadA}/messages`;
    await page.route(sends, async route => {
      const req = route.request();
      if (!isSend(req.method(), req.url(), threadA)) return route.fallback();
      ids.push(JSON.parse(req.postData() ?? '{}').clientMessageId);
      if (ids.length === 1) return route.abort('failed'); // never reaches the server
      return route.fallback();
    });

    await threadRow(page, subjectA).click();
    await sendFromComposer(page, text);
    await expect(page.getByText('Not sent', { exact: true })).toBeVisible();
    expect(await messagesWithBody(api.thomas, threadA, text)).toHaveLength(0);

    // Away and back: the unsent message is still there with Retry.
    await threadRow(page, subjectB).click();
    await expect(page.getByRole('heading', { name: subjectB, exact: true })).toBeVisible();
    await expect(page.getByText(text, { exact: true })).toHaveCount(0);
    await threadRow(page, subjectA).click();
    await expect(page.getByText(text, { exact: true })).toBeVisible();
    await expect(page.getByText('Not sent', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Retry sending message' }).click();
    await expect(page.getByText('Not sent', { exact: true })).toHaveCount(0);
    await expect(page.getByText(text, { exact: true })).toHaveCount(1);
    expect(ids).toHaveLength(2);
    expect(ids[1], 'Retry reuses the clientMessageId').toBe(ids[0]);
    await expect.poll(async () => (await messagesWithBody(api.thomas, threadA, text)).length).toBe(1);
    await page.unroute(sends);
  });

  test('sign-out clears drafts and unsent messages; signing back in shows neither', async ({ browser }) => {
    const up = await open(THOMAS, browser);
    const { page } = up;
    await openMessages(page);
    const draft  = `${fixTag()} draft before sign-out`;
    const unsent = `${fixTag()} unsent before sign-out`;
    const sends = (u: URL) => u.pathname === `/api/communications/threads/${threadB}/messages`;
    await page.route(sends, route => (isSend(route.request().method(), route.request().url(), threadB)
      ? route.abort('failed') : route.fallback()));

    await threadRow(page, subjectB).click();
    await sendFromComposer(page, unsent);
    await expect(page.getByText('Not sent', { exact: true })).toBeVisible();
    await threadRow(page, subjectA).click();
    await composer(page).fill(draft);
    const localKeys = () => page.evaluate(() => Object.keys(sessionStorage)
      .filter(k => k.startsWith('carenexus.draft.') || k.startsWith('carenexus.outbox.')));
    await expect.poll(localKeys, 'draft and outbox are stored while signed in').toHaveLength(2);

    // Last keystrokes still inside the 400 ms save debounce must not be written back after sign-out.
    await composer(page).fill(`${draft} and more`);
    await page.getByRole('button', { name: 'Sign out' }).first().click();
    await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
    await expect.poll(localKeys, 'nothing left in this tab after sign-out').toEqual([]);
    await page.unroute(sends);

    await login(page, THOMAS);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();
    await expect(composer(page)).toHaveValue('');
    await threadRow(page, subjectB).click();
    await expect(page.getByRole('heading', { name: subjectB, exact: true })).toBeVisible();
    await expect(page.getByText(unsent, { exact: true })).toHaveCount(0);
    expect(await messagesWithBody(api.thomas, threadB, unsent)).toHaveLength(0);
    // An open thread B view would keep marking B read and hide later tests' notifications for it.
    await up.context.close();
  });

  test('offline: banner, a send fails with the offline notice, then recovers after reconnecting', async ({ browser }) => {
    const { context, page } = await open(THOMAS, browser);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();

    await context.setOffline(true);
    await expect(page.getByText(OFFLINE_BANNER)).toBeVisible();
    const text = `${fixTag()} offline message`;
    await sendFromComposer(page, text);
    await expect(page.getByText('Not sent', { exact: true })).toBeVisible();
    await expect(page.getByText("You're offline. Messages will be sent when you retry.")).toBeVisible();

    await context.setOffline(false);
    await expect(page.getByText(OFFLINE_BANNER)).toHaveCount(0);
    await page.getByRole('button', { name: 'Retry sending message' }).click();
    await expect(page.getByText('Not sent', { exact: true })).toHaveCount(0);
    await expect.poll(async () => (await messagesWithBody(api.thomas, threadA, text)).length).toBe(1);

    // Polling resumed: a new message from the doctor arrives.
    const reply = `${fixTag()} after reconnect`;
    await postMessage(api.james, threadA, reply);
    await expect(page.getByText(reply, { exact: true })).toBeVisible({ timeout: 12_000 });
  });

  test('server unreachable while online: "Not up to date" notice, cleared on recovery', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();

    const failPolls = (u: URL) => isPoll(u.toString(), threadA);
    await page.route(failPolls, route => route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"down"}' }));
    const notice = page.getByText(/^Not up to date/);
    await expect(notice).toBeVisible({ timeout: 12_000 });
    await expect(composer(page)).toBeEditable(); // non-blocking

    await page.unroute(failPolls);
    const fresh = `${fixTag()} after outage`;
    await postMessage(api.james, threadA, fresh);
    await expect(page.getByText(fresh, { exact: true })).toBeVisible({ timeout: 12_000 });
    await expect(notice).toHaveCount(0);
  });

  test('hidden tab pauses polling and refreshes straight away when visible again', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();
    await page.waitForRequest(r => isPoll(r.url(), threadA), { timeout: 10_000 }); // polling is running

    let polls = 0;
    const count = (r: { url(): string }) => { if (isPoll(r.url(), threadA)) polls++; };
    await setTabHidden(page, true);
    page.on('request', count);
    await page.waitForTimeout(11_000); // two poll intervals
    page.off('request', count);
    expect(polls, 'no message polls while hidden').toBe(0);

    const resumed = page.waitForRequest(r => isPoll(r.url(), threadA), { timeout: 2_000 });
    await setTabHidden(page, false);
    await resumed;
  });

  test('archive from the list updates the open thread header; unarchive from the header', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('button', { name: 'Archive conversation', exact: true })).toBeVisible();

    await page.getByRole('button', { name: `Archive ${subjectA}`, exact: true }).click();
    await expect(page.getByRole('button', { name: 'Move conversation to inbox', exact: true })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Conversations' }).getByRole('button', { name: new RegExp(`^${escapeRe(subjectA)}, `) })).toHaveCount(0);
    expect((await api.thomas.get(`/api/communications/threads/${threadA}`)).body.archived).toBe(true);

    await page.getByRole('button', { name: 'Move conversation to inbox', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Archive conversation', exact: true })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Conversations' }).getByRole('button', { name: new RegExp(`^${escapeRe(subjectA)}, `) })).toBeVisible();
    await page.getByRole('tab', { name: 'Archived conversations' }).click();
    await expect(page.getByRole('button', { name: new RegExp(`^${escapeRe(subjectA)}, `) })).toHaveCount(0);
    await page.getByRole('tab', { name: 'Inbox conversations' }).click();
    expect((await api.thomas.get(`/api/communications/threads/${threadA}`)).body.archived).toBe(false);
  });

  test('notification click-through opens that thread; panel focus, trap, Escape; tabs use arrow keys', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    const text = `${fixTag()} notify`;
    await postMessage(api.james, threadB, text);
    const unread = (await api.thomas.ok(api.thomas.get('/api/notifications?unreadOnly=true&page=0&size=5'), 'notifications')).content as any[];
    expect(unread[0]?.threadId, 'newest notification is for thread B').toBe(threadB);

    // Keyboard: open with Enter, focus is inside the panel, Tab stays inside, Escape returns to the bell.
    await expect(bell(page)).toHaveAccessibleName(/^Notifications, \d+ unread$/, { timeout: 40_000 });
    await bell(page).focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Notifications' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('list', { name: 'Notifications' })).toBeVisible();
    await expectFocusInside(dialog, 'focus moved into the panel');
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press(i % 3 === 2 ? 'Shift+Tab' : 'Tab');
      await expectFocusInside(dialog, `focus still inside after Tab ${i + 1}`);
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(bell(page)).toBeFocused();

    // Close button also returns focus to the bell.
    await bell(page).click();
    await dialog.getByRole('button', { name: 'Close notifications' }).click();
    await expect(dialog).toHaveCount(0);
    await expect(bell(page)).toBeFocused();

    // Click-through from the dashboard home: Messages opens on thread B with the new message.
    await bell(page).click();
    await dialog.getByRole('list', { name: 'Notifications' }).getByRole('button').first().click();
    await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: subjectB, exact: true })).toBeVisible();
    await expect(page.getByText(text, { exact: true })).toBeVisible();

    // And from Messages with another thread open.
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();
    const again = `${fixTag()} notify again`;
    await postMessage(api.james, threadB, again);
    await expect(bell(page)).toHaveAccessibleName(/^Notifications, \d+ unread$/, { timeout: 40_000 });
    await bell(page).click();
    await dialog.getByRole('list', { name: 'Notifications' }).getByRole('button').first().click();
    await expect(page.getByRole('heading', { name: subjectB, exact: true })).toBeVisible();
    await expect(page.getByText(again, { exact: true })).toBeVisible();

    // Inbox / Archived tabs: arrow keys move selection and focus, one tab stop.
    const inbox = page.getByRole('tab', { name: 'Inbox conversations' });
    const archivedTab = page.getByRole('tab', { name: 'Archived conversations' });
    await inbox.focus();
    await page.keyboard.press('ArrowRight');
    await expect(archivedTab).toBeFocused();
    await expect(archivedTab).toHaveAttribute('aria-selected', 'true');
    await expect(archivedTab).toHaveAttribute('tabindex', '0');
    await expect(inbox).toHaveAttribute('tabindex', '-1');
    await page.keyboard.press('ArrowLeft');
    await expect(inbox).toBeFocused();
    await expect(inbox).toHaveAttribute('aria-selected', 'true');
  });

  test('new conversation from the UI: patient -> doctor private, doctor replies from the UI', async ({ browser }) => {
    const thread = await privateThreadThomasJames(api.thomas); // reused by the form (one private pair)
    await markThreadRead(api.james, thread.threadId);
    const thomas = await open(THOMAS, browser);
    const james  = await open(JAMES, browser);
    const first = `${fixTag()} question for the doctor`;
    const reply = `${fixTag()} doctor reply`;

    const p = thomas.page;
    await openMessages(p);
    await p.getByRole('button', { name: 'Start a new conversation' }).first().click();
    await p.getByRole('radio', { name: /^Private conversation, between a doctor and the patient or one caregiver$/ }).click();
    await p.getByRole('radio', { name: LABELS.doctor, exact: true }).click();
    await p.getByLabel('First message, optional').fill(first);
    // Urgent helper is announced: it sits in a polite live region.
    await p.getByRole('switch', { name: 'Mark first message as urgent' }).click();
    await expect(p.locator('[aria-live="polite"]').filter({ hasText: URGENT_HELPER })).toBeVisible();
    await p.getByRole('switch', { name: 'Mark first message as urgent' }).click();
    const createdRes = p.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
    await p.getByRole('button', { name: 'Start conversation' }).click();
    expect((await createdRes).status()).toBeLessThan(300);
    await expect(p.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();
    await expect(p.getByText(first, { exact: true })).toBeVisible();

    const j = james.page;
    await openMessages(j);
    await j.getByRole('button', { name: new RegExp(`^${escapeRe(THOMAS.name)}, .*, Private · ${escapeRe(THOMAS.name)}, `) }).first().click();
    await expect(j.getByText(first, { exact: true })).toBeVisible();
    await sendFromComposer(j, reply);
    await expect(j.getByText(reply, { exact: true })).toBeVisible();
    await expect(j.getByText('Sending…')).toHaveCount(0, { timeout: 10_000 });
    await expect(p.getByText(reply, { exact: true })).toBeVisible({ timeout: 12_000 });

    // Doctor's new-conversation type option: accessible name matches its visible text.
    await j.getByRole('button', { name: 'Start a new conversation' }).first().click();
    await j.getByLabel('Search patients').fill('Thomas');
    await j.getByRole('radio', { name: THOMAS.name, exact: true }).first().click();
    await expect(j.getByRole('radio', { name: /^Private conversation, between a doctor and the patient or one caregiver$/ })).toBeVisible();
    await expect(j.getByText('Between a doctor and the patient or one caregiver', { exact: true })).toBeVisible();
  });

  test('new conversation: a retry after changing type or people never reuses the earlier clientThreadId', async ({ browser }) => {
    const { page } = await open(THOMAS, browser);
    await openMessages(page);
    const subject = `${fixTag()} retry ids`;
    const bodies: any[] = [];
    const creates = (u: URL) => u.pathname === '/api/communications/threads';
    await page.route(creates, async route => {
      if (route.request().method() !== 'POST') return route.fallback();
      bodies.push(JSON.parse(route.request().postData() ?? '{}'));
      if (bodies.length <= 2) return route.abort('failed');
      return route.fallback();
    });

    await page.getByRole('button', { name: 'Start a new conversation' }).first().click();
    await page.getByRole('radio', { name: /^Private conversation/ }).click();
    await page.getByRole('radio', { name: LABELS.doctor, exact: true }).click();
    await page.getByLabel('Subject, optional').fill(subject);
    await page.getByRole('button', { name: 'Start conversation' }).click();
    await expect.poll(() => bodies.length).toBe(1);
    await expect(page.getByRole('button', { name: 'Start conversation' })).toBeEnabled();
    await page.getByRole('button', { name: 'Start conversation' }).click(); // same choices: same id
    await expect.poll(() => bodies.length).toBe(2);
    expect(bodies[1].clientThreadId).toBe(bodies[0].clientThreadId);

    await page.getByRole('radio', { name: /^Care team conversation/ }).click();
    await page.getByRole('checkbox', { name: LABELS.doctor, exact: true }).click();
    const createdRes = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Start conversation' }).click();
    const res = await createdRes;
    expect(res.status()).toBeLessThan(300);
    created.push((await res.json()).threadId);
    expect(bodies).toHaveLength(3);
    expect(bodies[2].type).toBe('CARE_TEAM');
    expect(bodies[2].clientThreadId, 'a different conversation gets a new clientThreadId').not.toBe(bodies[0].clientThreadId);
    await expect(page.getByRole('heading', { name: subject, exact: true })).toBeVisible();
    await page.unroute(creates);
  });

  test('caregiver: private conversation with the doctor from the UI that the patient cannot open', async ({ browser }) => {
    const { page } = await open(SARAH, browser);
    await openMessages(page);
    const first = `${fixTag()} caregiver private question`;
    await page.getByRole('button', { name: 'Start a new conversation' }).first().click();
    await page.getByRole('radio', { name: THOMAS.name, exact: true }).first().click();
    await page.getByRole('radio', { name: /^Private conversation/ }).click();
    await page.getByRole('radio', { name: LABELS.doctor, exact: true }).click();
    await page.getByLabel('First message, optional').fill(first);
    const createdRes = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Start conversation' }).click();
    const res = await createdRes;
    expect(res.status()).toBeLessThan(300);
    const thread = await res.json();
    expect(thread.type).toBe('PRIVATE');
    await expect(page.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();
    await expect(page.getByText(first, { exact: true })).toBeVisible();

    // The doctor has it; the patient cannot open it or its messages, and it is not in their list.
    await expect.poll(async () => (await messagesWithBody(api.james, thread.threadId, first)).length).toBe(1);
    expect([403, 404]).toContain((await api.thomas.get(`/api/communications/threads/${thread.threadId}`)).status);
    expect([403, 404]).toContain((await api.thomas.get(`/api/communications/threads/${thread.threadId}/messages?limit=5`)).status);
    const list = await api.thomas.ok(api.thomas.get('/api/communications/threads?archived=false&page=0&size=100'), 'thread list');
    expect((list.content as any[]).some(t => t.threadId === thread.threadId)).toBe(false);
  });

  test('caregiver patient filter narrows the inbox to one loved one', async ({ browser }) => {
    const { page } = await open(SARAH, browser);
    await openMessages(page);
    const toggle = page.getByRole('button', { name: 'Filter by patient: All patients', exact: true });
    await expect(toggle).toBeVisible();
    await toggle.click();
    const filtered = page.waitForRequest(r => r.url().includes('/api/communications/threads?') && r.url().includes(`patientSystemId=${THOMAS.systemId}`));
    await page.getByRole('radiogroup', { name: 'Filter by patient' }).getByRole('radio', { name: THOMAS.name, exact: true }).click();
    await filtered;
    await expect(page.getByRole('button', { name: `Filter by patient: ${THOMAS.name}`, exact: true })).toBeVisible();
    const rows = page.getByRole('list', { name: 'Conversations' }).getByRole('button', { name: /, (Private|Care team) · / });
    await expect(rows.first()).toBeVisible();
    for (const name of await rows.evaluateAll(els => els.map(e => e.getAttribute('aria-label') ?? ''))) {
      expect(name).toContain(`· ${THOMAS.name}`);
    }

    await page.getByRole('button', { name: `Filter by patient: ${THOMAS.name}`, exact: true }).click();
    const all = page.waitForRequest(r => r.url().includes('/api/communications/threads?') && !r.url().includes('patientSystemId='));
    await page.getByRole('radiogroup', { name: 'Filter by patient' }).getByRole('radio', { name: 'All patients', exact: true }).click();
    await all;
    await expect(page.getByRole('button', { name: 'Filter by patient: All patients', exact: true })).toBeVisible();
  });

  test('caregiver whose messaging is turned off mid-session: no access in the thread, reason in the empty inbox', async ({ browser }) => {
    const { page } = await open(SARAH, browser);
    await openMessages(page);
    await threadRow(page, subjectA).click();
    await expect(page.getByRole('heading', { name: subjectA, exact: true })).toBeVisible();
    await expect(composer(page)).toBeVisible();

    const g = await sarahGrant(api.thomas);
    await api.thomas.ok(api.thomas.patch(`/api/patients/me/caregiver-access/${g.accessId}`, { canMessageCareTeam: false }), 'turn Sarah messaging off');
    try {
      await expect(page.getByText(NO_ACCESS).first()).toBeVisible({ timeout: 12_000 });
      await expect(composer(page)).toHaveCount(0);
      await page.getByRole('button', { name: 'Back to conversations' }).last().click();
      // All of Sarah's conversations are about Thomas, so the inbox is empty and explains why.
      await expect(page.getByText('No conversations yet', { exact: true })).toBeVisible({ timeout: 20_000 });
      const access = page.getByRole('list', { name: 'Messaging access' });
      await expect(access.getByText(`Messaging is not allowed for your access to ${THOMAS.name}.`, { exact: false })).toBeVisible();
      await expect(page.getByRole('alert').filter({ hasText: 'Messaging is not allowed' })).toHaveCount(0); // not an error block
    } finally {
      await restoreSarahConsent(api.thomas);
    }
  });
});
