import { expect, test } from '@playwright/test';
import { loginAdmin, registerAccount, type RegisteredUser } from './support/accounts';
import { Api, privateThreadThomasJames } from './support/api';
import { ADMIN, runTag, THOMAS } from './support/env';
import { escapeRe, openSectionAt, sectionUrl } from './support/ui';
import { closeAll, flowPage, type FlowPage, marked, uniqueSurname, waitForNotifications } from './flows/account-helpers';

// Support conversations: a patient opens "Contact support" from Help & Support and writes; an administrator finds
// the conversation in the support inbox and answers; the patient sees the answer in the same conversation and is
// notified. An administrator who opens the link of a clinical conversation (patient and doctor) sees no content and
// the API refuses with 403.
//
// Tables written: users, patients, user_settings, legal_acceptances, refresh_tokens, message_threads (type
// SUPPORT), thread_members (the patient, then the administrator on first read), messages, notifications,
// outbound_messages, audit_log; for the refusal check, Thomas's existing private thread with Dr. Banda is only read
// (it is created if the demo data lacks it, with no messages). The support conversation belongs to a throwaway
// account e2e-…@carenexus.test and its messages start with the run tag, so scripts/dev-reset-e2e-data.sh removes
// it; the spec also closes it.

test.describe.configure({ mode: 'serial' });

test.describe('flow: contact support and the administrator support inbox', () => {
  const tag = runTag();
  let patient: RegisteredUser;
  let admin: Api;
  let threadId = 0;
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    patient = await registerAccount({ role: 'patient', lastName: uniqueSurname('Support') });
    admin = await loginAdmin();
  });

  test.afterAll(async () => {
    await closeAll(pages);
    if (admin && threadId) await admin.post(`/api/admin/support/threads/${threadId}/close`).catch(() => null);
    await admin?.dispose();
  });

  test('a patient writes to support, the administrator answers, the patient sees the answer', async ({ browser }) => {
    const question = marked(tag, 'I cannot find my invoice from last week.');
    const answer = marked(tag, 'Thanks for writing. It is under Billing & Payments now.');
    const patientApi = await Api.login(patient);
    try {
      const p = await flowPage(browser, patient);
      pages.push(p);

      await test.step('Contact support on Help & Support opens the patient\'s support conversation', async () => {
        await openSectionAt(p.page, 'patient', 'help-support', {}, 'Help & Support');
        await expect(p.page.getByRole('heading', { name: 'Contact support' })).toBeVisible();
        const opened = p.page.waitForResponse(r => r.url().endsWith('/api/communications/support-thread') && r.request().method() === 'POST');
        await p.page.getByRole('button', { name: 'Contact support', exact: true }).click();
        const response = await opened;
        expect([200, 201]).toContain(response.status());
        const thread = await response.json();
        expect(thread.type).toBe('SUPPORT');
        threadId = thread.threadId;
        await p.page.waitForURL(u => u.searchParams.get('section') === 'messages', { timeout: 20_000 });
        await expect(p.page.getByLabel('Message', { exact: true })).toBeVisible();
      });

      await test.step('the patient writes the first message', async () => {
        await p.page.getByLabel('Message', { exact: true }).fill(question);
        const sent = p.page.waitForResponse(r => r.url().endsWith(`/api/communications/threads/${threadId}/messages`) && r.request().method() === 'POST');
        await p.page.getByRole('button', { name: /^Send (urgent )?message$/ }).click();
        expect((await sent).status()).toBeLessThan(300);
        await expect(p.page.getByText(question, { exact: true })).toBeVisible();
      });

      const a = await flowPage(browser, ADMIN);
      pages.push(a);
      await test.step('the administrator finds it in the support inbox and answers', async () => {
        await openSectionAt(a.page, 'admin', 'messages', {}, 'Messages');
        const row = a.page.getByRole('list', { name: 'Open support conversations' })
          .getByRole('button', { name: new RegExp(escapeRe(tag)) });
        await expect(row).toBeVisible({ timeout: 30_000 });
        await expect(row).toHaveAccessibleName(new RegExp(escapeRe(patient.systemId)));
        await row.click();
        await expect(a.page.getByText(question, { exact: true })).toBeVisible();
        await a.page.getByLabel('Message', { exact: true }).fill(answer);
        const sent = a.page.waitForResponse(r => r.url().endsWith(`/api/communications/threads/${threadId}/messages`) && r.request().method() === 'POST');
        await a.page.getByRole('button', { name: /^Send (urgent )?message$/ }).click();
        expect((await sent).status()).toBeLessThan(300);
        await expect(a.page.getByText(answer, { exact: true })).toBeVisible();
      });

      await test.step('the patient sees the answer in the conversation and is notified', async () => {
        await expect(p.page.getByText(answer, { exact: true })).toBeVisible({ timeout: 45_000 });
        await waitForNotifications(patientApi, n => n.type === 'MESSAGE' && n.threadId === threadId, 'support answered');
        const inbox = await admin.ok(admin.page('/api/admin/support/threads', { open: true }), 'support inbox');
        const listed = (inbox.content as { threadId: number; openerSystemId: string }[]).find(t => t.threadId === threadId);
        expect(listed?.openerSystemId).toBe(patient.systemId);
      });
    } finally {
      await patientApi.dispose();
    }
  });

  test('the administrator cannot open a clinical conversation by its link', async ({ browser }) => {
    const thomas = await Api.login(THOMAS);
    let clinicalId = 0;
    try {
      clinicalId = (await privateThreadThomasJames(thomas)).threadId as number;
    } finally {
      await thomas.dispose();
    }

    for (const path of [`/api/communications/threads/${clinicalId}`, `/api/communications/threads/${clinicalId}/messages`]) {
      const r = await admin.get(path);
      expect(r.status, path).toBe(403);
    }
    expect((await admin.post('/api/communications/support-thread', {})).status, 'administrators do not open support conversations').toBe(403);

    const a = await flowPage(browser, ADMIN);
    pages.push(a);
    const refused: number[] = [];
    a.page.on('response', r => {
      if (new RegExp(`^/api/communications/threads/${clinicalId}(/|$)`).test(new URL(r.url()).pathname)) refused.push(r.status());
    });
    await a.page.goto(sectionUrl('admin', 'messages', { thread: clinicalId }));
    await expect(a.page.getByText(/^(You no longer have access to this conversation\.|Conversation unavailable)$/).first()).toBeVisible({ timeout: 30_000 });
    expect(refused.length, 'the screen asked for the conversation').toBeGreaterThan(0);
    expect(refused.every(s => s === 403 || s === 404), `every read of the clinical conversation was refused (${refused.join(', ')})`).toBe(true);
  });
});
