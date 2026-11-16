import { expect, type Page, test } from '@playwright/test';
import {
  type Clients, admitByLabel, cancelLeftoverE2EAppointments, clientId, createLiveableVideoAppointment, disposeAll, loginAll,
  markThreadRead, messagesWithBody, privateThreadThomasJames, restoreSarahConsent,
} from './support/api';
import { JAMES, LABELS, SARAH, THOMAS, runTag } from './support/env';
import { escapeRe, horizontalOverflow, newUserPage, openMessages, openSection, seriousA11yViolations, type UserPage } from './support/ui';

// f. Accessibility: axe (WCAG 2.1 A/AA, serious + critical) on the messages screen, the call lobby,
//    waiting area and in-call view, and the patient's caregiver-access screen; keyboard-only send;
//    no horizontal scrolling at 375 px.

async function expectNoSeriousViolations(page: Page, screen: string) {
  const violations = await seriousA11yViolations(page);
  expect.soft(violations, `${screen}: serious/critical axe violations`).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page, screen: string) {
  expect.soft(await horizontalOverflow(page), `${screen}: horizontal overflow at 375 px`).toEqual([]);
}

/** Presses Tab until `target` has focus (bounded), so the test proves it is reachable by keyboard. */
async function tabTo(page: Page, isTarget: () => Promise<boolean>, what: string, max = 80) {
  for (let i = 0; i < max; i++) {
    if (await isTarget()) return;
    await page.keyboard.press('Tab');
  }
  throw new Error(`${what} is not reachable with the Tab key`);
}

const focusedLabel = (page: Page) => page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '');

test.describe('accessibility', () => {
  let api: Clients;
  const pages: UserPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) await cancelLeftoverE2EAppointments(api.james).catch(() => {});
    await disposeAll(api);
  });

  test('sign-in: axe, selected role announced, keyboard-only sign in', async ({ browser }) => {
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      await page.goto('/auth/login/web/login-web');
      await expect(page.getByRole('radio', { name: 'Patient', exact: true })).toHaveAttribute('aria-checked', 'true');
      await expectNoSeriousViolations(page, 'sign in');

      await page.locator('body').click({ position: { x: 1, y: 1 } });
      await tabTo(page, async () => (await focusedLabel(page)) === 'Doctor', 'the Doctor role');
      // react-native-web activates role="radio" pressables with Enter only (Space works for
      // button roles only); Enter is what keyboard users can rely on here.
      await page.keyboard.press('Enter');
      await expect(page.getByRole('radio', { name: 'Doctor', exact: true })).toHaveAttribute('aria-checked', 'true');
      await expect(page.getByRole('radio', { name: 'Patient', exact: true })).toHaveAttribute('aria-checked', 'false');
      await tabTo(page, async () => (await focusedLabel(page)) === 'Email address', 'the email box');
      await page.keyboard.type(JAMES.email);
      await tabTo(page, async () => (await focusedLabel(page)) === 'Password', 'the password box');
      await page.keyboard.type(JAMES.password);
      await page.keyboard.press('Enter'); // Enter in the password box signs in
      await page.waitForURL('**/dashboard/doctor', { timeout: 30_000 });
    } finally {
      await context.close();
    }
  });

  test('messages screen: axe, keyboard-only send, 375 px', async ({ browser }) => {
    const thread = await privateThreadThomasJames(api.thomas);
    await markThreadRead(api.thomas, thread.threadId);
    const patient = await newUserPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;

    await openMessages(page);
    await expectNoSeriousViolations(page, 'messages (list)');

    // Keyboard only: Tab to the thread, open it with Enter, Tab to the composer, type, Enter sends.
    await page.locator('body').click({ position: { x: 1, y: 1 } });
    const rowName = new RegExp(`^${escapeRe(LABELS.doctor)}, .*, Private, `);
    await tabTo(page, async () => rowName.test(await focusedLabel(page)), 'the private thread row');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();
    await tabTo(page, async () => (await focusedLabel(page)) === 'Message', 'the message box');
    const body = `Sent with the keyboard only ${runTag()}`;
    await page.keyboard.type(body);
    await page.keyboard.press('Enter');
    await expect(page.getByText(body, { exact: true })).toBeVisible();
    await expect.poll(async () => (await messagesWithBody(api.thomas, thread.threadId, body)).length).toBe(1);
    await expect(page.getByLabel('Message', { exact: true })).toHaveValue('');

    // Sending with the Send button from the keyboard keeps focus in the message box (the button
    // turns disabled once the box is empty, which used to drop focus to the page body).
    const body2 = `Sent with the Send button ${runTag()}`;
    await page.keyboard.type(body2);
    await page.keyboard.press('Tab');
    expect(await focusedLabel(page)).toBe('Send message');
    await page.keyboard.press('Enter');
    await expect(page.getByText(body2, { exact: true })).toBeVisible();
    await expect(page.getByLabel('Message', { exact: true })).toBeFocused();

    await expectNoSeriousViolations(page, 'messages (thread open)');

    // 375 px: list, then thread (single pane with a back button).
    await page.setViewportSize({ width: 375, height: 812 });
    await page.reload();
    await openMessagesNarrow(page);
    await expectNoHorizontalOverflow(page, 'messages list');
    await page.getByRole('button', { name: rowName }).click();
    await expect(page.getByRole('button', { name: 'Back to conversations' })).toBeVisible();
    await expectNoHorizontalOverflow(page, 'messages thread');
    await expectNoSeriousViolations(page, 'messages (375 px thread)');
  });

  test('doctor and caregiver messages screens: axe and 375 px', async ({ browser }) => {
    // Both inboxes need a conversation to show: the global e2e cleanup leaves none behind, so open
    // the Thomas-James private thread and a care-team thread with Sarah (tagged e2e-, cleaned up).
    await privateThreadThomasJames(api.thomas);
    await api.sarah.ok(api.sarah.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject: `e2e-a11y ${runTag()}`,
      memberSystemIds: [JAMES.systemId], clientThreadId: clientId(),
    }), 'create care team thread for Sarah');
    for (const user of [JAMES, SARAH]) {
      const up = await newUserPage(browser, user);
      pages.push(up);
      await openMessages(up.page);
      await expect(up.page.getByRole('button', { name: /, (Private|Care team)/ }).first()).toBeVisible();
      await expectNoSeriousViolations(up.page, `messages (${user.role})`);
      await up.page.setViewportSize({ width: 375, height: 812 });
      await up.page.reload();
      await openMessagesNarrow(up.page);
      await expectNoHorizontalOverflow(up.page, `messages list (${user.role})`);
      await expectNoSeriousViolations(up.page, `messages (${user.role}, 375 px)`);
    }
  });

  test('call lobby, waiting area and in-call view: axe and 375 px', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;

    const patient = await newUserPage(browser, THOMAS);
    const caregiver = await newUserPage(browser, SARAH, { viewport: { width: 375, height: 812 } });
    pages.push(patient, caregiver);

    // Lobby before the doctor starts.
    await patient.page.goto(route);
    await expect(patient.page.getByText('Waiting for the doctor to start the call.')).toBeVisible();
    await expect(patient.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoSeriousViolations(patient.page, 'call lobby');

    await api.james.ok(api.james.post(`/api/appointments/${id}/call/start`), 'start');

    // Caregiver at 375 px: lobby with the join choices, then the waiting area.
    await caregiver.page.goto(route);
    await expect(caregiver.page.getByRole('button', { name: 'Ask to join', exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(caregiver.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoHorizontalOverflow(caregiver.page, 'call lobby (caregiver)');
    await expectNoSeriousViolations(caregiver.page, 'call lobby (caregiver, 375 px)');
    await caregiver.page.getByRole('button', { name: 'Ask to join', exact: true }).click();
    await expect(caregiver.page.getByText('Waiting for the doctor to let you in...')).toBeVisible();
    await expectNoHorizontalOverflow(caregiver.page, 'waiting area');
    await expectNoSeriousViolations(caregiver.page, 'waiting area (375 px)');

    // Patient waiting area at desktop width.
    await patient.page.getByRole('button', { name: 'Ask to join', exact: true }).click({ timeout: 20_000 });
    await expect(patient.page.getByText('Waiting for the doctor to let you in...')).toBeVisible();
    await expectNoSeriousViolations(patient.page, 'waiting area');

    // Doctor: lobby (call already live) and in-call view with two people waiting in the admission panel.
    const doctor = await newUserPage(browser, JAMES);
    pages.push(doctor);
    await doctor.page.goto(route);
    await expect(doctor.page.getByRole('button', { name: 'Rejoin call', exact: true })).toBeVisible();
    await expect(doctor.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });
    await expectNoSeriousViolations(doctor.page, 'call lobby (doctor)');
    await doctor.page.getByRole('button', { name: 'Rejoin call', exact: true }).click();
    await expect(doctor.page.getByRole('button', { name: `Admit ${LABELS.caregiverInCall}` })).toBeVisible({ timeout: 45_000 });
    await expect(doctor.page.getByRole('button', { name: `Admit ${LABELS.patientInCall}` })).toBeVisible();
    await expect(doctor.page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
    await expectNoSeriousViolations(doctor.page, 'in call (doctor, admission panel)');

    // In call.
    await admitByLabel(api.james, id, LABELS.patientInCall);
    await admitByLabel(api.james, id, LABELS.caregiverInCall);
    for (const up of [patient, caregiver]) {
      await expect(up.page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible({ timeout: 45_000 });
      await expect(up.page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
    }
    await expectNoSeriousViolations(patient.page, 'in call');
    await expectNoHorizontalOverflow(caregiver.page, 'in call');
    await expectNoSeriousViolations(caregiver.page, 'in call (375 px)');

    await api.james.ok(api.james.post(`/api/appointments/${id}/call/end`), 'end');
  });

  test('patient caregiver-access screen: axe and 375 px', async ({ browser }) => {
    const patient = await newUserPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;
    await openSection(page, 'My Proxies (Caregivers)');
    await expect(page.getByRole('heading', { name: 'My caregivers' })).toBeVisible();
    await expect(page.getByRole('button', { name: `Remove access for ${SARAH.name}` })).toBeVisible();
    await expectNoSeriousViolations(page, 'caregiver access');

    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await expect(page.getByLabel('Caregiver email')).toBeVisible();
    await expectNoSeriousViolations(page, 'caregiver access (grant form)');

    await page.setViewportSize({ width: 375, height: 812 });
    await page.reload();
    await openSectionNarrow(page, 'My Proxies (Caregivers)');
    await expect(page.getByRole('heading', { name: 'My caregivers' })).toBeVisible();
    await expectNoHorizontalOverflow(page, 'caregiver access');
    await expectNoSeriousViolations(page, 'caregiver access (375 px)');
  });
});

/** At phone width the sidebar sits behind a menu button. */
async function openSectionNarrow(page: Page, label: string) {
  const menu = page.getByRole('button', { name: /^(Open menu|Menu|Open navigation)$/ });
  if (await menu.count()) await menu.first().click();
  await openSection(page, label);
}

async function openMessagesNarrow(page: Page) {
  await openSectionNarrow(page, 'Messages');
  await expect(page.getByRole('heading', { name: 'Messages', exact: true })).toBeVisible();
}
