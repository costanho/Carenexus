import { expect, test } from '@playwright/test';
import {
  type Clients, admitByLabel, cancelLeftoverE2EAppointments, createLiveableVideoAppointment, disposeAll, loginAll,
  restoreSarahConsent,
} from './support/api';
import { LABELS, THOMAS } from './support/env';
import { liveVideoCount, newUserPage, tile, type UserPage } from './support/ui';

// e. Device edge cases: joining with the camera off, and a browser that blocks the camera. Both need Chromium's
//    fake camera and microphone, so the describe is tagged @chromium-only (the Firefox and WebKit projects skip it).
//
// Tables written: appointments (an "E2E video call test" VIDEO appointment, cancelled in afterAll), call_sessions,
// call_participants, call_jobs and provider_events of its call; refresh_tokens and audit_log for every sign-in.

test.describe.configure({ mode: 'serial' });

test.describe('call devices @chromium-only', () => {
  let api: Clients;
  let appointmentId: number;
  const pages: UserPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
    await restoreSarahConsent(api.thomas);
    appointmentId = await createLiveableVideoAppointment(api.james);
    // The doctor starts the call through the API (no doctor browser needed here).
    await api.james.ok(api.james.post(`/api/appointments/${appointmentId}/call/start`), 'start');
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      await api.james.post(`/api/appointments/${appointmentId}/call/end`).catch(() => {});
      await cancelLeftoverE2EAppointments(api.james).catch(() => {});
    }
    await disposeAll(api);
  });

  test('audio-only: joining with the camera turned off works', async ({ browser }) => {
    const patient = await newUserPage(browser, THOMAS);
    pages.push(patient);
    const { page } = patient;
    await page.goto(`/dashboard/call/${appointmentId}`);
    await expect(page.getByText('Working')).toHaveCount(2, { timeout: 20_000 });

    await page.getByRole('switch', { name: 'Camera on' }).click();
    await expect(page.getByRole('switch', { name: 'Join without camera' })).toBeVisible();
    await expect(page.getByLabel('Camera preview is off')).toBeVisible();

    await page.getByRole('button', { name: 'Ask to join', exact: true }).click();
    await admitByLabel(api.james, appointmentId, LABELS.patientInCall);

    await expect(page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText('Connecting...')).toHaveCount(0, { timeout: 30_000 });
    // Microphone on, camera off, and no camera track published.
    await expect(page.getByRole('button', { name: 'Mute', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Start camera', exact: true })).toBeVisible();
    await expect(tile(page, `${LABELS.patientInCall} (You)`)).toHaveAttribute('aria-label', /camera off/);
    expect(await liveVideoCount(page)).toBe(0);
    await expect(page.getByRole('alert')).toHaveCount(0); // no device error banner

    await page.getByRole('button', { name: 'Leave', exact: true }).click();
    await page.waitForURL('**/dashboard/patient');
  });

  test('camera blocked by the browser: clear message, audio-only still possible', async ({ playwright }) => {
    // A browser without the auto-accept flag, where only the microphone is granted: the camera
    // request is denied (NotAllowedError). Full Chromium (new headless) honours per-context
    // permissions for media; the headless shell does not support media without the fake UI flag.
    const browser = await playwright.chromium.launch({ channel: 'chromium', args: ['--use-fake-device-for-media-stream'] });
    try {
      const patient = await newUserPage(browser, THOMAS, { permissions: ['microphone'] });
      const { page } = patient;
      await page.goto(`/dashboard/call/${appointmentId}`);

      await expect(page.getByText('Your browser is blocking the camera or microphone')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText(/allow camera and microphone for this site/)).toBeVisible();
      await expect(page.getByText(/You can also join with audio only or without a camera/)).toBeVisible();
      await expect(page.getByLabel('Camera preview is off').getByText('Blocked by the browser')).toBeVisible();
      await expect(page.getByText('Blocked by the browser')).toHaveCount(2); // preview + camera status line
      await expect(page.getByText('Working')).toHaveCount(1); // the microphone still works

      // Joining still works with audio only.
      await page.getByRole('button', { name: 'Ask to join', exact: true }).click();
      await admitByLabel(api.james, appointmentId, LABELS.patientInCall);
      await expect(page.getByRole('button', { name: 'Leave', exact: true })).toBeVisible({ timeout: 45_000 });
      await expect(page.getByRole('button', { name: 'Start camera', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Leave', exact: true }).click();
      await page.waitForURL('**/dashboard/patient');
      await patient.context.close();
    } finally {
      await browser.close();
    }
  });
});
