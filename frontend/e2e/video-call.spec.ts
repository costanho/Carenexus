import { expect, type Page, test } from '@playwright/test';
import {
  type Clients, admitByLabel, callStatus, cancelLeftoverE2EAppointments, createLiveableVideoAppointment, disposeAll,
  loginAll, restoreSarahConsent, sarahGrant, waitForCallStatus,
} from './support/api';
import { JAMES, LABELS, SARAH, THOMAS } from './support/env';
import { roomIdentities, tokenClaims } from './support/livekit';
import { liveVideoCount, newUserPage, openSection, tile, type UserPage } from './support/ui';

// c. Full call: doctor starts, patient and caregiver ask to join, doctor admits, everyone sees
//    live remote video; Leave keeps the call LIVE; End ends it for all; appointment not completed.
// d. Revoking the caregiver's "join calls" permission mid-call removes her from the call.
// Every test joins a live call with Chromium's fake camera and microphone, so the describe is @chromium-only.
//
// Tables written: appointments ("E2E video call test" VIDEO appointments, cancelled in afterAll), call_sessions,
// call_participants, call_jobs and provider_events of their calls; dependent_access (Sarah's "join calls" consent,
// restored in afterAll) and consent_records / caregiver_activity_log rows for that change; refresh_tokens and
// audit_log for every sign-in.

test.describe.configure({ mode: 'serial' });

async function expectInCall(page: Page, who: string) {
  await expect(page.getByRole('button', { name: 'Leave', exact: true }), `${who} in call`).toBeVisible({ timeout: 45_000 });
  await expect(page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
}

async function askToJoin(page: Page, opts: { caregiver?: boolean } = {}) {
  const ask = page.getByRole('button', { name: 'Ask to join', exact: true });
  await expect(ask).toBeVisible({ timeout: 20_000 }); // waiting area polls every 5 s
  if (opts.caregiver) {
    await page.getByRole('radio', { name: `Attending with ${THOMAS.name}` }).click();
  }
  await ask.click();
  await expect(page.getByText('Waiting for the doctor to let you in...')).toBeVisible();
}

test.describe('video call @chromium-only', () => {
  let api: Clients;
  const pages: UserPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      await restoreSarahConsent(api.thomas).catch(() => {});
      await cancelLeftoverE2EAppointments(api.james).catch(() => {});
    }
    await disposeAll(api);
  });

  test('doctor starts, admits patient and caregiver, live video, leave, end', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;

    const doctor    = await newUserPage(browser, JAMES);
    const patient   = await newUserPage(browser, THOMAS);
    const caregiver = await newUserPage(browser, SARAH);
    pages.push(doctor, patient, caregiver);

    // Doctor: lobby with device check, then Start call.
    await doctor.page.goto(route);
    await expect(doctor.page.getByRole('heading', { name: 'Video consultation' })).toBeVisible();
    await expect(doctor.page.getByText('Working')).toHaveCount(2, { timeout: 20_000 }); // mic + camera preview
    await doctor.page.getByRole('button', { name: 'Start call', exact: true }).click();
    await expectInCall(doctor.page, 'doctor');
    await expect(doctor.page.getByRole('heading', { name: `Call with ${THOMAS.name}` })).toBeVisible();
    await waitForCallStatus(api.james, id, s => s.callStatus === 'LIVE', 'LIVE');

    // Patient and caregiver ask to join.
    await patient.page.goto(route);
    await caregiver.page.goto(route);
    await askToJoin(patient.page);
    await askToJoin(caregiver.page, { caregiver: true });

    // Doctor admits both from the in-call admission panel.
    const panel = doctor.page.getByLabel('People in this call');
    await panel.getByRole('button', { name: `Admit ${LABELS.patientInCall}` }).click({ timeout: 20_000 });
    await panel.getByRole('button', { name: `Admit ${LABELS.caregiverInCall}` }).click({ timeout: 20_000 });

    await expectInCall(patient.page, 'patient');
    await expectInCall(caregiver.page, 'caregiver');

    // Remote tiles with live video on every page (local + 2 remote cameras = 3 playing videos).
    for (const [who, up] of [['doctor', doctor], ['patient', patient], ['caregiver', caregiver]] as const) {
      await expect.poll(() => liveVideoCount(up.page), { message: `${who}: playing <video> elements`, timeout: 45_000 })
        .toBeGreaterThanOrEqual(3);
    }
    await expect(tile(doctor.page, LABELS.caregiverInCall)).toBeVisible();
    await expect(tile(doctor.page, LABELS.patientInCall)).toBeVisible();
    await expect(tile(patient.page, LABELS.doctor)).toBeVisible();
    await expect(tile(patient.page, LABELS.caregiverInCall)).toBeVisible();
    await expect(tile(caregiver.page, LABELS.doctor)).toBeVisible();
    await expect(tile(caregiver.page, `${LABELS.caregiverInCall} (You)`)).toBeVisible();
    // The caregiver tile's own <video> renders frames on the doctor's page.
    const cgVideo = tile(doctor.page, LABELS.caregiverInCall).locator('video');
    await expect.poll(() => cgVideo.evaluate((v: HTMLVideoElement) => v.readyState >= 2 && v.videoWidth > 0), { timeout: 30_000 }).toBe(true);
    // ...and keeps decoding new frames (not a single frozen frame).
    const framesAt = () => cgVideo.evaluate((v: HTMLVideoElement) => v.getVideoPlaybackQuality().totalVideoFrames);
    const f0 = await framesAt();
    await expect.poll(framesAt, { message: 'caregiver video frames advance on the doctor page', timeout: 10_000 }).toBeGreaterThan(f0 + 10);
    // Doctor panel shows both as connected (provider webhook reached the API).
    await expect(panel.getByText('Connected')).toHaveCount(2, { timeout: 30_000 });
    // No chat in the call UI.
    await expect(doctor.page.getByText(/chat/i)).toHaveCount(0);

    // Patient leaves: the call stays LIVE, the doctor no longer sees the patient tile.
    await patient.page.getByRole('button', { name: 'Leave', exact: true }).click();
    await patient.page.waitForURL('**/dashboard/patient');
    const afterLeave = await waitForCallStatus(api.james, id, s => !s.participants.some((p: any) => p.label === LABELS.patientInCall && p.status === 'ADMITTED'), 'patient left');
    expect(afterLeave.callStatus).toBe('LIVE');
    await expect(tile(doctor.page, LABELS.patientInCall)).toHaveCount(0, { timeout: 30_000 });
    await expect(caregiver.page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible();
    const patientView = await api.thomas.ok(callStatus(api.thomas, id), 'patient status');
    expect(patientView.myParticipant.status).toBe('LEFT');
    expect(patientView.callStatus).toBe('LIVE');

    // Doctor ends the call for everyone (window.confirm accepted by the helper).
    await doctor.page.getByRole('button', { name: 'End call', exact: true }).click();
    await expect(doctor.page.getByRole('heading', { name: 'The call has ended' })).toBeVisible({ timeout: 20_000 });
    await expect(caregiver.page.getByRole('heading', { name: 'The call has ended' })).toBeVisible({ timeout: 20_000 });
    const ended = await api.james.ok(callStatus(api.james, id), 'ended status');
    expect(ended.callStatus).toBe('ENDED');

    // Ending the call never completes the appointment.
    const appt = await api.james.ok(api.james.get(`/api/appointments/${id}`), 'appointment');
    expect(appt.status).toBe('SCHEDULED');
    expect(appt.callStatus).toBe('ENDED');

    // The ended call refuses new tokens.
    expect((await api.sarah.post(`/api/appointments/${id}/call/token`)).status).toBe(409);
  });

  test('patient revokes caregiver call permission mid-call: caregiver is removed', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;
    await api.james.ok(api.james.post(`/api/appointments/${id}/call/start`), 'start');

    const caregiver = await newUserPage(browser, SARAH);
    const patient   = await newUserPage(browser, THOMAS);
    pages.push(caregiver, patient);

    await caregiver.page.goto(route);
    await askToJoin(caregiver.page, { caregiver: true });
    await admitByLabel(api.james, id, LABELS.caregiverInCall);
    await expectInCall(caregiver.page, 'caregiver');
    await waitForCallStatus(api.james, id,
      s => s.participants.some((p: any) => p.label === LABELS.caregiverInCall && p.status === 'ADMITTED' && p.connected),
      'caregiver connected');

    // Thomas turns off "Join my video calls" for Sarah on the caregiver access screen.
    await openSection(patient.page, 'My Proxies (Caregivers)');
    await expect(patient.page.getByRole('heading', { name: 'My caregivers' })).toBeVisible();
    const card = patient.page.locator('div').filter({ has: patient.page.getByRole('button', { name: `Remove access, ${SARAH.name}` }) })
      .filter({ has: patient.page.getByRole('switch', { name: 'Join my video calls' }) }).last();
    const callsSwitch = card.getByRole('switch', { name: 'Join my video calls' });
    await expect(callsSwitch).toHaveAttribute('aria-checked', 'true');
    const revokedAt = Date.now();
    await callsSwitch.click();
    await expect(callsSwitch).toHaveAttribute('aria-checked', 'false');
    expect((await sarahGrant(api.thomas)).joinCallsAllowed).toBe(false);

    // Sarah's page leaves the room and shows that her access was removed.
    await expect(caregiver.page.getByRole('button', { name: 'Leave', exact: true })).toHaveCount(0, { timeout: 30_000 });
    await expect(caregiver.page.getByText('Your access to this call was removed.')).toBeVisible({ timeout: 15_000 });
    expect(Date.now() - revokedAt).toBeLessThan(45_000);
    await expect(caregiver.page.getByRole('button', { name: /^(Join call|Ask to join)$/ })).toHaveCount(0);

    // Server side: her token request is refused and she is out of the room.
    const token = await api.sarah.post(`/api/appointments/${id}/call/token`);
    expect([403, 409]).toContain(token.status);
    const s = await waitForCallStatus(api.james, id,
      x => !x.participants.some((p: any) => p.label === LABELS.caregiverInCall && p.status === 'ADMITTED'),
      'caregiver no longer admitted');
    expect(s.callStatus).toBe('LIVE');
    await waitForCallStatus(api.james, id, x => x.removalPending === false, 'provider confirmed the removal', 60_000);

    // Restore the demo consent and finish the call.
    await restoreSarahConsent(api.thomas);
    await api.james.ok(api.james.post(`/api/appointments/${id}/call/end`), 'end');
  });

  // The test above passes even if only the caregiver's own page reacts (it sees the 403 and leaves
  // by itself). This one proves the server enforces the revocation: Sarah's page is made unable to
  // notice (status polling frozen at the last good answer, token refresh cut off), so she only leaves
  // the room if the API makes LiveKit remove her. Checked on the LiveKit server and on the doctor's page.
  test('revocation is enforced by the server even if the caregiver page does not notice', async ({ browser }) => {
    const id = await createLiveableVideoAppointment(api.james);
    const route = `/dashboard/call/${id}`;
    await api.james.ok(api.james.post(`/api/appointments/${id}/call/start`), 'start');

    const doctor    = await newUserPage(browser, JAMES);
    const caregiver = await newUserPage(browser, SARAH);
    pages.push(doctor, caregiver);

    await doctor.page.goto(route);
    await doctor.page.getByRole('button', { name: 'Rejoin call', exact: true }).click({ timeout: 20_000 });
    await expectInCall(doctor.page, 'doctor');

    await caregiver.page.goto(route);
    await askToJoin(caregiver.page, { caregiver: true });
    await admitByLabel(api.james, id, LABELS.caregiverInCall);
    await expectInCall(caregiver.page, 'caregiver');
    await waitForCallStatus(api.james, id,
      s => s.participants.some((p: any) => p.label === LABELS.caregiverInCall && p.status === 'ADMITTED' && p.connected),
      'caregiver connected');

    // Sarah's video reaches the doctor, and LiveKit itself lists her in the room.
    const cgVideo = tile(doctor.page, LABELS.caregiverInCall).locator('video');
    await expect.poll(() => cgVideo.evaluate((v: HTMLVideoElement) => v.readyState >= 2 && v.videoWidth > 0).catch(() => false),
      { message: 'caregiver video on the doctor page', timeout: 30_000 }).toBe(true);
    const tokenRes = await api.sarah.post(`/api/appointments/${id}/call/token`);
    expect(tokenRes.status).toBe(200);
    const { room, identity } = tokenClaims(tokenRes.body.token);
    expect(room && identity).toBeTruthy();
    await expect.poll(() => roomIdentities(room), { timeout: 15_000 }).toContain(identity);

    // Make Sarah's page blind to the revocation.
    let frozen: { status: number; body: string; headers: Record<string, string> } | null = null;
    let frozenServed = 0;
    await caregiver.page.route(`**/api/appointments/${id}/call`, async r => {
      if (r.request().method() !== 'GET') return r.fallback();
      const res = await r.fetch();
      if (res.status() === 200) {
        frozen = { status: 200, body: await res.text(), headers: res.headers() };
        return r.fulfill({ response: res, body: frozen.body });
      }
      if (frozen) { frozenServed++; return r.fulfill(frozen); }
      return r.fulfill({ response: res });
    });
    await caregiver.page.route(`**/api/appointments/${id}/call/token`, r => r.abort('failed'));
    // Capture one good status (the page polls every 5 s in the call) before revoking.
    await expect.poll(() => frozen !== null, { message: 'a status poll was captured', timeout: 15_000 }).toBe(true);

    // Thomas revokes "Join my video calls" (the UI path is covered by the test above).
    const g = await sarahGrant(api.thomas);
    await api.thomas.ok(api.thomas.patch(`/api/patients/me/caregiver-access/${g.accessId}`, { canJoinCalls: false }), 'revoke calls');

    // LiveKit no longer has her in the room, and her media is gone from the doctor's page.
    await expect.poll(() => roomIdentities(room), { message: 'LiveKit room participants', timeout: 30_000 }).not.toContain(identity);
    await expect(tile(doctor.page, LABELS.caregiverInCall)).toHaveCount(0, { timeout: 15_000 });
    // Her page was disconnected by the server (not by its own status check, which still says ADMITTED).
    await expect(caregiver.page.getByText('You were disconnected from the call.')).toBeVisible({ timeout: 15_000 });
    await expect(caregiver.page.getByRole('button', { name: 'Leave', exact: true })).toHaveCount(0);
    // Its re-check after the disconnect got the stale answer, so the server alone removed her.
    await expect.poll(() => frozenServed, { message: 'the page was served the stale status', timeout: 10_000 }).toBeGreaterThan(0);
    await expect.poll(() => roomIdentities(room), { timeout: 5_000 }).not.toContain(identity);
    await waitForCallStatus(api.james, id, x => x.removalPending === false, 'provider confirmed the removal', 60_000);

    await caregiver.page.unrouteAll({ behavior: 'ignoreErrors' });
    await restoreSarahConsent(api.thomas);
    await api.james.ok(api.james.post(`/api/appointments/${id}/call/end`), 'end');
  });
});
