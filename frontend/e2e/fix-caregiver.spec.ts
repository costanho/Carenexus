import { expect, type Page, test } from '@playwright/test';
import { Api, type Clients, clientId, disposeAll, loginAll, restoreSarahConsent } from './support/api';
import { type DemoUser, JAMES, SARAH, THOMAS } from './support/env';
import {
  fixTag, leaveThreads, registerFixCaregiver, revokeDependentGrants, revokeFixCaregiverGrants,
} from './support/fix-caregiver';
import {
  type MonitoredPage, PROXY_SECTIONS, apiIdle, futureDay, navButton, pageOverflow, signedInPage, visit,
} from './support/smoke';
import { escapeRe } from './support/ui';

// Caregiver fixes: Add Loved One date fields in the clinic time zone, the Loved One Overview at
// 375 px, named notification switches, section headings, the caregiver Notifications page, and
// "Message Care Team" for a loved one (opens their conversation, or is disabled with the reason).

const section = (nav: string) => PROXY_SECTIONS.find(s => s.nav === nav)!;

/** Waits for the caregiver dashboard and shows every loved one (the list starts collapsed to three). */
async function openDashboard(page: Page): Promise<void> {
  await navButton(page, 'Dashboard').click();
  await expect(page.getByRole('heading', { name: 'Loved One Overview', exact: true })).toBeVisible();
  await expect(page.getByText('Loading loved ones')).toHaveCount(0, { timeout: 20_000 });
  // The Loved One Overview card: the innermost box with its heading and the loved-one rows.
  const card = page.locator('div')
    .filter({ has: page.getByRole('heading', { name: 'Loved One Overview', exact: true }) })
    .filter({ has: page.getByRole('button', { name: /^Open profile for / }) }).last();
  // The rows render after the dependents load; check for "+N more" only once they are there.
  await expect(card.getByRole('button', { name: /^Open profile for / }).first()).toBeVisible();
  const more = card.getByRole('button', { name: /\+\d+ more$/ });
  if (await more.count()) {
    await more.first().click();
    await expect(card.getByRole('button', { name: /Show less$/ })).toBeVisible();
  }
}

function messageCareTeam(page: Page, name: string, reason?: string) {
  const label = reason ? `Message Care Team about ${name}. ${reason}` : `Message Care Team about ${name}`;
  return page.getByRole('button', { name: label, exact: true });
}

test.describe('fix caregiver', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];
  const dependentsToRevoke: DemoUser[] = [];
  const sarahThreads: number[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    for (const d of dependentsToRevoke) await revokeDependentGrants(d).catch(() => {});
    if (api) {
      await leaveThreads(api.sarah, sarahThreads);
      await revokeFixCaregiverGrants(api.thomas).catch(() => {});
      await restoreSarahConsent(api.thomas).catch(() => {});
    }
    await disposeAll(api);
  });

  test('Add Loved One accepts a valid date of birth and access expiry in Africa/Harare (UTC+2)', async ({ browser }) => {
    const tag = fixTag();
    const carer = await registerFixCaregiver(tag);
    const cg = await signedInPage(browser, carer, { timezoneId: 'Africa/Harare' });
    pages.push(cg);
    const { page, monitor } = cg;
    expect(await page.evaluate(() => new Date(2026, 0, 1).getTimezoneOffset()), 'browser runs in UTC+2').toBe(-120);

    const depEmail = `${tag}-dep@carenexus.test`;
    const expiry = futureDay(365).iso;
    const visible = (placeholder: string) => page.getByPlaceholder(placeholder, { exact: true }).filter({ visible: true });

    await page.getByRole('button', { name: /Add Another Loved One/ }).first().click();
    await expect(page.getByRole('heading', { name: 'Add Loved One', exact: true })).toBeVisible();

    await visible('e.g. Thomas').fill('Fix');
    await visible('e.g. Dube').fill('Dependent');
    await visible('e.g. thomas.dube@email.com').fill(depEmail);
    await visible('e.g. +263771234567').fill(`+26371${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`);
    const depPassword = await page.locator('input[type="password"]').filter({ visible: true }).first().inputValue();
    await page.getByText('Continue', { exact: true }).click();

    await expect(page.getByText('Medical and demographic information for the patient record')).toBeVisible();
    await page.getByText('Female', { exact: true }).click();
    await page.getByText('B+', { exact: true }).click();
    // An impossible date is still refused...
    await visible('YYYY-MM-DD').fill('1950-02-30');
    await page.getByText('Continue', { exact: true }).click();
    await expect(page.getByText('Use the format YYYY-MM-DD')).toBeVisible();
    // ...and a real one is accepted in a time zone ahead of UTC.
    await visible('YYYY-MM-DD').fill('1950-03-15');
    await page.getByText('Continue', { exact: true }).click();
    await expect(page.getByText('Use the format YYYY-MM-DD'), 'valid date of birth 1950-03-15 accepted').toHaveCount(0);

    await expect(page.getByText('Define your proxy relationship and what you are permitted to access')).toBeVisible();
    await visible('e.g. Father, Mother, Sibling, Guardian…').fill('Mother');
    await visible('YYYY-MM-DD (leave blank for no expiry)').fill(expiry);
    const created = page.waitForResponse(r => r.url().endsWith('/api/caregiver/dependents') && r.request().method() === 'POST');
    await page.getByText('Add Loved One', { exact: true }).last().click();
    await expect(page.getByText('Use the format YYYY-MM-DD'), `valid access expiry ${expiry} accepted`).toHaveCount(0);
    expect((await created).status(), 'dependent created').toBeLessThan(300);
    dependentsToRevoke.push({ email: depEmail, password: depPassword, role: 'patient', systemId: '', name: 'Fix Dependent' });
    await expect(page.getByText('Loved One Added!')).toBeVisible();
    monitor.expectClean('add loved one');

    const capi = await Api.login(carer);
    try {
      const deps = await capi.ok(capi.get<any[]>('/api/caregiver/dependents'), 'dependents');
      const dep = deps.find(d => d.patientEmail === depEmail);
      expect(dep, 'dependent linked on the server').toBeTruthy();
      expect(dep.patientDateOfBirth, 'date of birth stored as entered').toBe('1950-03-15');
      expect(String(dep.expiresAt ?? '').slice(0, 10), 'access expiry stored as entered').toBe(expiry);
    } finally {
      await capi.dispose();
    }
  });

  test('Loved One Overview fits a 375 px screen', async ({ browser }) => {
    const sarah = await signedInPage(browser, SARAH, { viewport: { width: 375, height: 812 } });
    pages.push(sarah);
    const { page, monitor } = sarah;
    await openDashboard(page);
    await apiIdle(page);
    // Every loved one's health status and blood type is on screen (they used to end past 375 px).
    const deps = await api.sarah.ok(api.sarah.get<any[]>('/api/caregiver/dependents'), 'dependents');
    for (const d of deps) await expect(page.getByRole('button', { name: `Open profile for ${d.patientName}`, exact: true })).toBeVisible();
    expect(await pageOverflow(page), 'nothing on the caregiver dashboard runs past 375 px').toEqual([]);
    monitor.expectClean('dashboard 375 px');
  });

  test('notification settings: every switch is named and reports its state; email and SMS are not available', async ({ browser }) => {
    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    await visit(page, section('Notification Settings'));
    await apiIdle(page);

    const names = [
      'Critical Health Alerts', 'Lab Results', 'Medication Reminders', 'Vitals Outside Range',
      '24-Hour Appointment Reminder', '1-Hour Appointment Reminder', 'Appointment Changes', 'Video Call Ready',
      'New Messages', 'Prescription Ready', 'Care Plan Updates', 'Referrals',
      'New Login Alert', 'Proxy Access Changes', 'Weekly Health Summary', 'Push Notifications',
    ];
    for (const name of names) {
      const sw = page.getByRole('switch', { name, exact: true });
      await expect(sw, `switch "${name}"`).toHaveCount(1);
      await expect(sw, `switch "${name}" exposes aria-checked`).toHaveAttribute('aria-checked', /^(true|false)$/);
    }
    for (const name of ['Email, not available yet', 'SMS, not available yet']) {
      await expect(page.getByRole('switch', { name, exact: true })).toBeDisabled();
    }
    await expect(page.getByText('Not available yet', { exact: true })).toHaveCount(2);

    // Toggling flips aria-checked; email/SMS are never sent from this screen. Toggled twice, so Sarah's settings stay as they were.
    const msg = page.getByRole('switch', { name: 'New Messages', exact: true });
    const before = await msg.getAttribute('aria-checked');
    await msg.click();
    await expect(msg).toHaveAttribute('aria-checked', before === 'true' ? 'false' : 'true');
    await msg.click();
    await expect(msg).toHaveAttribute('aria-checked', before!);
    const saved = page.waitForRequest(r => r.url().endsWith('/api/profile/settings/notifications') && r.method() === 'PATCH');
    await page.getByRole('button', { name: 'Save notification settings' }).click();
    const body = (await saved).postDataJSON();
    expect(body, 'message notification preference is saved').toHaveProperty('messageNotifications');
    expect(Object.keys(body), 'email and SMS are not saved').not.toEqual(expect.arrayContaining(['emailNotifications']));
    expect(Object.keys(body)).not.toEqual(expect.arrayContaining(['smsNotifications']));
    await expect(page.getByText('Saved!')).toBeVisible();
    monitor.expectClean('notification settings');
  });

  test('caregiver section titles are headings', async ({ browser }) => {
    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    await openDashboard(page);
    await expect(page.getByRole('heading', { name: 'Loved One Overview', exact: true })).toBeVisible();
    await visit(page, section('Profile & Settings'));
    await expect(page.getByRole('heading', { name: 'Linked Patients', exact: true })).toBeVisible();
    await visit(page, section('Notification Settings'));
    await expect(page.getByRole('heading', { name: 'Notification Settings', exact: true })).toBeVisible();
    await visit(page, section('Health Summary'));
    await expect(page.getByRole('heading', { name: 'Latest Vitals', exact: true, level: 2 }).first()).toBeVisible();
    await navButton(page, 'Notifications').click();
    await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
    await openDashboard(page);
    await page.getByRole('button', { name: 'Open profile for Thomas Dube', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Patient Profile', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Quick Actions', exact: true })).toBeVisible();
    await apiIdle(page);
    monitor.expectClean('headings');
  });

  test('caregiver Notifications page lists notifications, opens the conversation and marks read', async ({ browser }) => {
    const tag = fixTag();
    const subject = `${tag} notifications`;
    const thread = await api.sarah.ok(api.sarah.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject, memberSystemIds: [JAMES.systemId], clientThreadId: tag,
    }), 'care team thread');
    sarahThreads.push(thread.threadId);
    for (const n of [1, 2]) {
      await api.james.ok(api.james.post(`/api/communications/threads/${thread.threadId}/messages`, {
        clientMessageId: clientId(), body: `${tag} doctor ${n}`, urgent: false,
      }), 'doctor message');
    }
    await expect.poll(async () => {
      const r = await api.sarah.ok(api.sarah.get<any>('/api/notifications?unreadOnly=true&page=0&size=50'), 'unread');
      return (r.content as any[]).filter(x => x.threadId === thread.threadId).length;
    }, { message: 'Sarah has notifications for the new messages' }).toBeGreaterThan(0);
    const list = await api.sarah.ok(api.sarah.get<any>('/api/notifications?unreadOnly=true&page=0&size=50'), 'unread');
    const note = (list.content as any[]).find(x => x.threadId === thread.threadId);

    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    const nav = navButton(page, 'Notifications');
    await expect(nav, 'Notifications in the caregiver sidebar').toBeVisible();
    await nav.click();
    await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
    const row = (unreadSuffix: boolean) => page.getByRole('button', {
      name: new RegExp(`^${escapeRe(note.title)}, .*${escapeRe(note.message)}${unreadSuffix ? ', unread' : ''}$`),
    }).first();
    await expect(row(true), 'the unread notification is listed').toBeVisible();

    // Activating it marks it read and opens its conversation in Messages.
    const marked = page.waitForResponse(r => r.url().endsWith(`/api/notifications/${note.notificationId}/read`) && r.request().method() === 'POST');
    await row(true).click();
    expect((await marked).status()).toBeLessThan(300);
    await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: subject })).toBeVisible();

    await navButton(page, 'Notifications').click();
    await expect(page.getByRole('heading', { name: 'Notifications', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: new RegExp(`^${escapeRe(note.title)}, .*${escapeRe(note.message)}$`) }).first(),
      'the notification now shows as read').toBeVisible();

    // "Mark all as read" clears the rest.
    const markAll = page.getByRole('button', { name: 'Mark all notifications as read' });
    if (await markAll.count()) {
      const all = page.waitForResponse(r => r.url().endsWith('/api/notifications/read-all') && r.request().method() === 'POST');
      await markAll.first().click();
      expect((await all).status()).toBeLessThan(300);
    }
    await expect.poll(async () => (await api.sarah.get('/api/notifications/unread-count')).body.unreadCount, { message: 'no unread notifications' }).toBe(0);
    await expect(page.getByRole('button', { name: /, unread$/ })).toHaveCount(0);
    monitor.expectClean('notifications page');
  });

  test('Message Care Team opens the loved one\'s existing care team conversation (dashboard and profile)', async ({ browser }) => {
    const tag = fixTag();
    const subject = `${tag} care team`;
    const thread = await api.sarah.ok(api.sarah.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject, memberSystemIds: [JAMES.systemId], clientThreadId: tag,
    }), 'care team thread');
    sarahThreads.push(thread.threadId);
    // A fresh message makes it the most recent care team conversation about Thomas.
    await api.sarah.ok(api.sarah.post(`/api/communications/threads/${thread.threadId}/messages`, {
      clientMessageId: clientId(), body: `${tag} hello`, urgent: false,
    }), 'caregiver message');

    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    const created: string[] = [];
    page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/api/communications/threads')) created.push(r.url()); });

    await openDashboard(page);
    const fromDashboard = messageCareTeam(page, THOMAS.name);
    await expect(fromDashboard).toBeEnabled();
    await fromDashboard.click();
    await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: subject }), 'the existing conversation is open').toBeVisible();

    await openDashboard(page);
    await page.getByRole('button', { name: `Open profile for ${THOMAS.name}`, exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Patient Profile', exact: true })).toBeVisible();
    await expect(page.getByLabel('Message the care team: Allowed')).toBeVisible();
    await messageCareTeam(page, THOMAS.name).click();
    await expect(page.getByRole('heading', { name: subject }), 'the profile opens the same conversation').toBeVisible();
    expect(created, 'no new conversation was started').toEqual([]);
    monitor.expectClean('message care team (existing)');
  });

  test('Message Care Team starts a care team conversation when the loved one has none', async ({ browser }) => {
    const carer = await registerFixCaregiver(fixTag());
    // Thomas grants access with messaging approved (a patient turning it on is the approval).
    await api.thomas.ok(api.thomas.post('/api/patients/me/caregiver-access', {
      caregiverEmail: carer.email, relationship: 'Neighbour', accessLevel: 'VIEW_ONLY', canMessageCareTeam: true,
    }), 'grant with messaging');
    const cg = await signedInPage(browser, carer);
    pages.push(cg);
    const { page, monitor } = cg;

    await openDashboard(page);
    const created = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
    await messageCareTeam(page, THOMAS.name).click();
    const res = await created;
    expect(res.status(), 'care team conversation created').toBeLessThan(300);
    const body = res.request().postDataJSON();
    expect(body.type).toBe('CARE_TEAM');
    expect(body.patientSystemId).toBe(THOMAS.systemId);
    expect((body.memberSystemIds as string[]).length, 'with Thomas\'s doctors').toBeGreaterThan(0);
    await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
    await expect(page.getByPlaceholder('Write a message…').filter({ visible: true }).first(), 'the new conversation is open').toBeVisible();
    const threads = await Api.login(carer);
    try {
      const list = await threads.ok(threads.get<any>(`/api/communications/threads?patientSystemId=${THOMAS.systemId}&archived=false&page=0&size=20`), 'threads');
      expect((list.content as any[]).map(t => t.type), 'one care team conversation about Thomas').toEqual(['CARE_TEAM']);
    } finally {
      await threads.dispose();
    }
    monitor.expectClean('message care team (new)');
    // afterAll revokes the grant, which also removes the caregiver from the conversation.
  });

  test('Message Care Team is disabled with the reason when messaging is waiting for approval or not allowed', async ({ browser }) => {
    const deps = await api.sarah.ok(api.sarah.get<any[]>('/api/caregiver/dependents'), 'dependents');
    const waiting = deps.find(d => d.pendingApproval && d.canMessageCareTeam && !d.messagingAllowed);
    const off = deps.find(d => !d.canMessageCareTeam && !d.messagingAllowed);
    expect(waiting, 'Sarah has a loved one whose messaging request is waiting (dev seed)').toBeTruthy();
    expect(off, 'Sarah has a loved one without messaging (dev seed)').toBeTruthy();
    const waitingFirst = String(waiting.patientName).split(' ')[0];
    const waitingReason = `Waiting for ${waitingFirst} to approve messaging`;
    const offReason = 'Messaging is not allowed for your access';

    const sarah = await signedInPage(browser, SARAH);
    pages.push(sarah);
    const { page, monitor } = sarah;
    let navigatedToMessages = false;
    page.on('request', r => { if (r.url().includes('/api/communications/threads')) navigatedToMessages = true; });

    await openDashboard(page);
    const waitingBtn = messageCareTeam(page, waiting.patientName, waitingReason);
    await expect(waitingBtn).toBeVisible();
    await expect(waitingBtn).toBeDisabled();
    await expect(messageCareTeam(page, off.patientName, offReason)).toBeDisabled();
    await expect(page.getByText(waitingReason, { exact: true }).first()).toBeVisible();
    await waitingBtn.click({ force: true });
    await expect(page.getByRole('heading', { name: 'Loved One Overview', exact: true }), 'a disabled button does nothing').toBeVisible();

    await page.getByRole('button', { name: `Open profile for ${waiting.patientName}`, exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Patient Profile', exact: true })).toBeVisible();
    await expect(messageCareTeam(page, waiting.patientName, waitingReason)).toBeDisabled();
    // The profile's status row uses pendingApproval too.
    await expect(page.getByLabel(`Message the care team: Waiting for ${waitingFirst} to approve`)).toBeVisible();
    expect(navigatedToMessages, 'no conversation was looked up or started').toBe(false);
    monitor.expectClean('message care team (disabled)');
  });
});
