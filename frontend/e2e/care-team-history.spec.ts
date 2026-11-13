import { expect, test } from '@playwright/test';
import { type Clients, clientId, disposeAll, loginAll, restoreSarahConsent } from './support/api';
import { JAMES, LABELS, SARAH, THOMAS, runTag } from './support/env';
import { escapeRe, newUserPage, openMessages, type UserPage } from './support/ui';

// b. Care-team history boundary: a caregiver added to a care team conversation later sees only
//    the messages sent after they joined (UI and API).
//
// Tables written: a "Care team e2e-…" conversation (message_threads, thread_members, messages) and the
// notifications its messages raise; refresh_tokens and audit_log for every sign-in. scripts/dev-reset-e2e-data.sh
// removes the conversation by its e2e- subject.

test.describe('care team conversation: history boundary for a member added later', () => {
  let api: Clients;
  let thomas: UserPage | undefined;
  let sarah: UserPage | undefined;

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    await thomas?.context.close();
    await sarah?.context.close();
    await disposeAll(api);
  });

  test('Sarah, added after earlier messages, sees only later ones', async ({ browser }) => {
    const tag = runTag();
    const subject = `Care team ${tag}`;
    const thread = await api.thomas.ok(api.thomas.post('/api/communications/threads', {
      patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject,
      memberSystemIds: [JAMES.systemId], clientThreadId: tag,
    }), 'create care team thread');
    const threadId = thread.threadId as number;
    expect(thread.members.map((m: any) => m.systemId)).not.toContain(SARAH.systemId);

    const before1 = `Before Sarah joined (patient) ${tag}`;
    const before2 = `Before Sarah joined (doctor) ${tag}`;
    await api.thomas.ok(api.thomas.post(`/api/communications/threads/${threadId}/messages`, { clientMessageId: clientId(), body: before1 }), 'send before1');
    await api.james.ok(api.james.post(`/api/communications/threads/${threadId}/messages`, { clientMessageId: clientId(), body: before2 }), 'send before2');

    // Sarah is not a member yet: no access.
    expect((await api.sarah.get(`/api/communications/threads/${threadId}`)).status).toBe(403);

    // Thomas adds Sarah from the member manager in the UI.
    thomas = await newUserPage(browser, THOMAS);
    await openMessages(thomas.page);
    await thomas.page.getByRole('button', { name: new RegExp(`^${escapeRe(subject)}, .*, Care team, `) }).click();
    await expect(thomas.page.getByText(before2, { exact: true })).toBeVisible();
    await thomas.page.getByRole('button', { name: 'Show members' }).click();
    await thomas.page.getByRole('button', { name: 'Add people to this conversation' }).click();
    await thomas.page.getByLabel(LABELS.caregiverMsg, { exact: true }).click();
    await thomas.page.getByRole('button', { name: 'Add selected people' }).click();
    await expect(thomas.page.getByRole('heading', { name: 'Members (3)' })).toBeVisible();

    const after1 = `After Sarah joined (doctor) ${tag}`;
    await api.james.ok(api.james.post(`/api/communications/threads/${threadId}/messages`, { clientMessageId: clientId(), body: after1 }), 'send after1');
    const after2 = `After Sarah joined (patient, from the UI) ${tag}`;
    await thomas.page.getByLabel('Message', { exact: true }).fill(after2);
    await thomas.page.getByRole('button', { name: 'Send message', exact: true }).click();
    await expect(thomas.page.getByText(after2, { exact: true })).toBeVisible();

    // Sarah's view.
    sarah = await newUserPage(browser, SARAH);
    await openMessages(sarah.page);
    const row = sarah.page.getByRole('button', { name: new RegExp(`^${escapeRe(subject)}, .*, Care team · ${escapeRe(THOMAS.name)}, `) });
    await expect(row).toBeVisible();
    // Preview and unread count only reflect messages after she joined.
    await expect(row).toHaveAccessibleName(new RegExp(`${escapeRe(after2)}, 2 unread$`));
    await row.click();
    await expect(sarah.page.getByText(after1, { exact: true })).toBeVisible();
    await expect(sarah.page.getByText(after2, { exact: true })).toBeVisible();
    await expect(sarah.page.getByText(before1, { exact: true })).toHaveCount(0);
    await expect(sarah.page.getByText(before2, { exact: true })).toHaveCount(0);
    await expect(sarah.page.getByRole('button', { name: 'Load earlier messages' })).toHaveCount(0);

    // Server side: the boundary also holds for paging backwards.
    const page = await api.sarah.ok(api.sarah.get(`/api/communications/threads/${threadId}/messages?limit=100`), 'sarah messages');
    expect(page.messages.map((m: any) => m.body)).toEqual([after1, after2]);
    const firstVisible = page.messages[0].messageId as number;
    const older = await api.sarah.ok(api.sarah.get(`/api/communications/threads/${threadId}/messages?before=${firstVisible}`), 'sarah older');
    expect(older.messages).toHaveLength(0);
    // Thomas (member from the start) still sees everything.
    const full = await api.thomas.ok(api.thomas.get(`/api/communications/threads/${threadId}/messages?limit=100`), 'thomas messages');
    expect(full.messages.map((m: any) => m.body)).toEqual([before1, before2, after1, after2]);
  });
});
