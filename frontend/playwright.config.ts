import { defineConfig, devices } from '@playwright/test';

// Browser end-to-end tests for messaging, video calls and accessibility (web app).
// They run against a real local stack: `docker compose up -d --build postgres-db livekit carenexus-api`
// (API on :9000, LiveKit dev on :7880) and the exported web app on :8081. See README "End-to-end tests".
//
// E2E_WEB_URL / E2E_API_URL override the defaults. When nothing listens on the web port, the
// config serves `dist/` itself (build it first with `npm run e2e:build`).
//
// The specs share the local dev database with the demo. globalSetup and globalTeardown delete all
// e2e test data (scripts/dev-reset-e2e-data.sh, local database only) before and after each run;
// E2E_CLEANUP=0 keeps it. `npm run e2e:clean` runs the same cleanup by hand.
//
// Browsers: `chromium` (bundled) always; `chrome` (installed Google Chrome) when E2E_CHANNEL=chrome; `firefox` and
// `webkit` when E2E_CROSS_BROWSER=1. The two cross-browser projects run only the smoke and accessibility specs
// (plus deep-links) and are meant for manual and nightly runs:
//   E2E_CROSS_BROWSER=1 npx playwright test --project=firefox --project=webkit
// (install the engines once with `npx playwright install --with-deps firefox webkit`). Only Chromium gets a fake
// camera and microphone through launch flags and granted permissions (WebKit has no fake device, Firefox only fake
// streams without granted permissions, see support/ui.ts mediaPermissions()), so tests that need working media are
// left out of the two cross-browser projects: tests tagged @chromium-only, and by title the accessibility test that
// waits for the device checks and joins a live call (it carries no tag yet; e2e-existing-specs is asked to add
// @chromium-only to it, after which the title entry can go). Reaching the call lobby (smoke-comms) needs no media
// and stays in.

const WEB_URL = process.env.E2E_WEB_URL ?? 'http://localhost:8081';
const webPort = Number(new URL(WEB_URL).port || 80);

const CROSS_BROWSER = process.env.E2E_CROSS_BROWSER === '1';
/** Specs the cross-browser projects run. */
const CROSS_BROWSER_SPECS = /(^|[\\/])(smoke-[^\\/]*|accessibility|deep-links)\.spec\.ts$/;
/**
 * Tests that need a working fake camera and microphone (Chromium launch flags only), matched against the full
 * title (describe + test): the @chromium-only tag, and the untagged live-call accessibility test.
 */
const CHROMIUM_ONLY = /@chromium-only|call lobby, waiting area and in-call view/;
const MEDIA_PERMISSIONS = ['camera', 'microphone'];

const FAKE_MEDIA_ARGS = [
  // Fake camera/microphone, and accept the permission prompt automatically.
  '--use-fake-ui-for-media-stream',
  '--use-fake-device-for-media-stream',
  '--autoplay-policy=no-user-gesture-required',
];

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
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  outputDir: 'e2e-results',
  use: {
    baseURL: WEB_URL,
    viewport: { width: 1280, height: 800 },
    timezoneId: 'Africa/Harare',
    locale: 'en-GB',
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
        permissions: MEDIA_PERMISSIONS,
        launchOptions: { args: FAKE_MEDIA_ARGS },
      },
    },
    // Real Google Chrome (installed browser, Playwright channel 'chrome'). Only added when
    // E2E_CHANNEL=chrome, so default runs are unchanged: `npm run e2e:chrome`.
    ...(process.env.E2E_CHANNEL === 'chrome'
      ? [{
          name: 'chrome',
          use: {
            ...devices['Desktop Chrome'],
            channel: 'chrome',
            viewport: { width: 1280, height: 800 },
            permissions: MEDIA_PERMISSIONS,
            launchOptions: { args: FAKE_MEDIA_ARGS },
          },
        }]
      : []),
    // Cross-browser runs (manual and nightly): the smoke, deep-link and accessibility specs in Firefox and
    // WebKit. Only when E2E_CROSS_BROWSER=1, so `npm run e2e` and the pull-request job are unchanged.
    ...(CROSS_BROWSER
      ? [
          {
            name: 'firefox',
            testMatch: CROSS_BROWSER_SPECS,
            grepInvert: CHROMIUM_ONLY,
            use: {
              ...devices['Desktop Firefox'],
              viewport: { width: 1280, height: 800 },
              launchOptions: {
                firefoxUserPrefs: {
                  // Fake media streams and no permission prompt, the Firefox counterpart of FAKE_MEDIA_ARGS.
                  'media.navigator.streams.fake': true,
                  'media.navigator.permission.disabled': true,
                },
              },
            },
          },
          {
            name: 'webkit',
            testMatch: CROSS_BROWSER_SPECS,
            grepInvert: CHROMIUM_ONLY,
            use: {
              ...devices['Desktop Safari'],
              viewport: { width: 1280, height: 800 },
            },
          },
        ]
      : []),
  ],
  webServer: {
    command: `node e2e/static-server.mjs dist ${webPort}`,
    url: WEB_URL,
    reuseExistingServer: true,
    timeout: 30_000,
  },
});
