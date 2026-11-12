import { execFile } from 'node:child_process';
import path from 'node:path';
import { promisify } from 'node:util';
import { expect, test } from '@playwright/test';
import {
  type Clients, cancelLeftoverE2EAppointments, clientId, createLiveableVideoAppointment, disposeAll, listAppointments,
  loginAll, privateThreadThomasJames, sarahGrant,
} from './support/api';
import { API_URL, JAMES, SARAH, THOMAS } from './support/env';
import { type UserPage, newUserPage, openMessages } from './support/ui';
import { resetE2EData } from './global-setup';

// Test-data hygiene: scripts/dev-reset-e2e-data.sh (run by globalSetup / globalTeardown and
// `npm run e2e:clean`) deletes what the suites create, keeps the demo seed, refuses anything that
// is not the local dev database, and a cleanup that cannot reach Docker never fails the run.
//
// Tables written: an "E2E video call test" VIDEO appointment with its call_sessions, call_jobs and provider_events, an
// e2e-fix-… care team conversation and a tagged message in the Thomas / Dr. Banda private thread (message_threads,
// thread_members, messages, notifications); refresh_tokens and audit_log for every sign-in. The test then runs
// scripts/dev-reset-e2e-data.sh, which deletes all of it (and every other e2e row), and checks a second run deletes
// nothing.

const run = promisify(execFile);
const SCRIPT = path.resolve(__dirname, '../../scripts/dev-reset-e2e-data.sh');

async function scriptExit(env: Record<string, string>): Promise<{ code: number; stderr: string }> {
  try {
    await run('bash', [SCRIPT, '--dry-run'], { env: { ...process.env, ...env }, timeout: 30_000 });
    return { code: 0, stderr: '' };
  } catch (e: any) {
    return { code: typeof e.code === 'number' ? e.code : -1, stderr: String(e.stderr ?? '') };
  }
}

test.describe('e2e test-data cleanup', () => {
  test('the reset script refuses targets that are not the local dev database', async () => {
    const remoteHost = await scriptExit({ E2E_PG_HOST: 'db.example.com' });
    expect(remoteHost.code, remoteHost.stderr).toBe(2);
    expect(remoteHost.stderr).toContain('is not localhost');

    const remoteEngine = await scriptExit({ DOCKER_HOST: 'tcp://10.0.0.1:2375' });
    expect(remoteEngine.code, remoteEngine.stderr).toBe(2);
    expect(remoteEngine.stderr).toContain('is not local');
  });

  test('a cleanup that cannot reach Docker warns instead of failing the run', async () => {
    const r = await resetE2EData('test', { ...process.env, E2E_CLEANUP: undefined, DOCKER_HOST: 'unix:///nonexistent/e2e-fix-docker.sock' });
    expect(r.ok).toBe(false);
    expect(r.message).toContain('npm run e2e:clean');

    const off = await resetE2EData('test', { ...process.env, E2E_CLEANUP: '0' });
    expect(off).toMatchObject({ ok: false, removed: {} });
  });

  test.describe('against the local stack', () => {
    let api: Clients | undefined;
    const pages: UserPage[] = [];

    test.skip(process.env.E2E_CLEANUP === '0', 'E2E_CLEANUP=0 keeps test data, so the cleanup is not exercised');
    test.skip(!['localhost', '127.0.0.1', '[::1]'].includes(new URL(API_URL).hostname), 'cleanup only runs against the local stack');

    test.beforeAll(async () => {
      api = await loginAll();
    });

    test.afterAll(async () => {
      await Promise.all(pages.map(p => p.context.close().catch(() => {})));
      // The test deletes its own data; if it failed before that, the API fallback cancels the call
      // and globalTeardown removes the rest.
      if (api) await cancelLeftoverE2EAppointments(api.james).catch(() => {});
      await disposeAll(api);
    });

    test('deletes e2e appointments, calls and conversations from the UI and keeps the demo data', async ({ browser }) => {
      const { thomas, james } = api!;
      const tag = `e2e-fix-${Date.now().toString(36)}`;
      // Every appointment of Dr. Banda from the canonical paged list (the legacy array is capped at 200 rows).
      const seedAppointments = async () => (await listAppointments(james, { doctorSystemId: JAMES.systemId }))
        .filter(a => !/^(e2e|Demo video consultation|Smoke test$)/i.test(String(a.reasonForVisit ?? '')));
      const seedBefore = (await seedAppointments()).map(a => a.appointmentId).sort();

      // Test data like the suites create it: a live video appointment (call session, jobs, room
      // events), a tagged care-team conversation and a tagged message in the reused private thread.
      const appointmentId = await createLiveableVideoAppointment(james);
      const thread = await thomas.ok(thomas.post('/api/communications/threads', {
        patientSystemId: THOMAS.systemId, type: 'CARE_TEAM', subject: `${tag} care team`,
        memberSystemIds: [JAMES.systemId], clientThreadId: `${tag}-thread`,
      }), 'create care-team thread');
      await thomas.ok(thomas.post(`/api/communications/threads/${thread.threadId}/messages`, { clientMessageId: clientId(), body: `${tag} hello` }), 'care-team message');
      const priv = await privateThreadThomasJames(thomas);
      await thomas.ok(thomas.post(`/api/communications/threads/${priv.threadId}/messages`, { clientMessageId: clientId(), body: `Question ${tag}` }), 'private message');

      const doctor = await newUserPage(browser, JAMES);
      const patient = await newUserPage(browser, THOMAS);
      pages.push(doctor, patient);
      await expect(doctor.page.getByText('E2E video call test').first(), "the doctor's schedule shows the test call").toBeVisible();
      await openMessages(patient.page);
      await expect(patient.page.getByText(`${tag} care team`).first()).toBeVisible();

      const r = await resetE2EData('test');
      expect(r.ok, `cleanup ran: ${r.message}`).toBe(true);
      expect(r.removed.appointments).toBeGreaterThanOrEqual(1);
      expect(r.removed['call sessions']).toBeGreaterThanOrEqual(1);
      expect(r.removed.threads).toBeGreaterThanOrEqual(1);
      expect(r.removed.messages).toBeGreaterThanOrEqual(2);

      // Deleted, not cancelled: nothing is left in the doctor's schedule or the patient's inbox.
      await doctor.page.reload();
      await expect(doctor.page.getByText("Here's your practice at a glance.")).toBeVisible();
      await expect(doctor.page.getByText('E2E video call test')).toHaveCount(0);
      await patient.page.reload();
      await openMessages(patient.page);
      await expect(patient.page.getByText(tag)).toHaveCount(0);
      expect((await james.get(`/api/appointments/${appointmentId}/call`)).status).toBe(404);

      // The demo data is untouched: same seed appointments, Sarah's consent, and the demo logins.
      expect((await seedAppointments()).map(a => a.appointmentId).sort()).toEqual(seedBefore);
      const grant = await sarahGrant(thomas);
      expect(grant).toMatchObject({ accessLevel: 'FULL_ACCESS', messagingAllowed: true, joinCallsAllowed: true });
      const again = await loginAll();
      expect(again.sarah.systemId).toBe(SARAH.systemId);
      await disposeAll(again);

      const second = await resetE2EData('test');
      expect(second.ok).toBe(true);
      expect(Object.values(second.removed).every(n => n === 0), `second run deletes nothing: ${second.message}`).toBe(true);
    });
  });
});
