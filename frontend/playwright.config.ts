import { defineConfig, devices } from '@playwright/test';

// Browser end-to-end tests for messaging, video calls and accessibility (web app).
// They run against a real local stack: `docker compose up -d --build postgres-db livekit carenexus-api`
// (API on :9000, LiveKit dev on :7880) and the exported web app on :8081. See README "End-to-end tests".
//
// E2E_WEB_URL / E2E_API_URL override the defaults. When nothing listens on the web port, the
// config serves `dist/` itself (build it first with `npm run e2e:build`).

const WEB_URL = process.env.E2E_WEB_URL ?? 'http://localhost:8081';
const webPort = Number(new URL(WEB_URL).port || 80);

export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.spec\.ts$/,
  // The specs share one database (demo users, one doctor, one caregiver grant), so they run in order.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  forbidOnly: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  outputDir: 'e2e-results',
  use: {
    baseURL: WEB_URL,
    viewport: { width: 1280, height: 800 },
    timezoneId: 'Africa/Harare',
    locale: 'en-GB',
    permissions: ['camera', 'microphone'],
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1280, height: 800 },
        launchOptions: {
          args: [
            // Fake camera/microphone, and accept the permission prompt automatically.
            '--use-fake-ui-for-media-stream',
            '--use-fake-device-for-media-stream',
            '--autoplay-policy=no-user-gesture-required',
          ],
        },
      },
    },
  ],
  webServer: {
    command: `node e2e/static-server.mjs dist ${webPort}`,
    url: WEB_URL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
