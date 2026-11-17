// End-to-end smoke test of a deployed CareNexus environment (staging by default): the API over HTTPS, then the web
// app in headless Chromium. Unlike the Playwright suite in e2e/ (local docker stack, demo seed accounts, database
// resets) it needs nothing but the two URLs, and it only creates data: one new test patient per run,
// smoke+<timestamp>@example.com, whose password is generated and never printed.
//
//   npm run smoke:staging                                   # https://carenexus-{api,web}-staging.fly.dev
//   SMOKE_API=https://api.example.com SMOKE_WEB=https://app.example.com npm run smoke:staging
//   npm run smoke:staging -- --api-only                     # skip the browser part
//
// Exit code 0 only when every check passes. Screenshots of failing pages go to smoke-results/ (git-ignored).
// Registration is rate limited (10 per hour per client address), so do not run it in a tight loop.

import { randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const API = (process.env.SMOKE_API ?? 'https://carenexus-api-staging.fly.dev').replace(/\/$/, '');
const WEB = (process.env.SMOKE_WEB ?? 'https://carenexus-web-staging.fly.dev').replace(/\/$/, '');
const API_ONLY = process.argv.includes('--api-only');
const OUT = join(process.cwd(), 'smoke-results');

const results = [];
const check = (name, ok, detail = '') => {
  results.push(ok);
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
};

// ---- Part 1: API ---------------------------------------------------------------------------------------------------
console.log(`API ${API}`);
const req = async (method, path, { body, token } = {}) => {
  const res = await fetch(API + path, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), Origin: WEB },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try { json = await res.json(); } catch {}
  return { status: res.status, json };
};
const preflight = (origin) => fetch(`${API}/api/auth/login`, {
  method: 'OPTIONS',
  headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type' },
  signal: AbortSignal.timeout(30_000),
});

const email = `smoke+${Date.now()}@example.com`;
const password = `Sm0ke-${randomBytes(12).toString('base64url')}`;
let systemId = null;

try {
  let r = await req('GET', '/actuator/health');
  check('health is UP', r.status === 200 && r.json?.status === 'UP', `${r.status} ${r.json?.status ?? ''}`.trim());

  let p = await preflight(WEB);
  check('CORS allows the web origin', p.headers.get('access-control-allow-origin') === WEB, `${p.status}`);
  p = await preflight('https://not-carenexus.example');
  check('CORS refuses another origin', !p.headers.get('access-control-allow-origin'), `${p.status}`);

  r = await req('GET', '/v3/api-docs');
  check('API docs are not public', r.status !== 200, `${r.status}`);
  r = await req('GET', '/api/users/me');
  check('anonymous request is refused', r.status === 401, `${r.status}`);

  r = await req('POST', '/api/auth/register', {
    body: { firstName: 'Smoke', lastName: 'Test', email, password, role: 'PATIENT', acceptedTermsVersion: '1', acceptedPrivacyVersion: '1' },
  });
  check('register a patient', r.status === 201, `${r.status} ${r.json?.code ?? ''}`.trim());

  r = await req('POST', '/api/auth/login', { body: { email, password: `${password}-wrong` } });
  check('wrong password is refused', r.status === 401, `${r.status}`);
  r = await req('POST', '/api/auth/login', { body: { email, password } });
  const tokens = r.json?.tokens;
  check('sign in returns a session', r.status === 200 && !!tokens?.accessToken, `${r.status}`);
  const token = tokens?.accessToken;

  r = await req('GET', '/api/users/me', { token });
  systemId = r.json?.system_id ?? null;
  check('current user', r.status === 200 && r.json?.email === email && r.json?.role === 'patient', `${r.status} ${systemId ?? ''}`.trim());

  const own = systemId ? `patientSystemId=${encodeURIComponent(systemId)}&` : '';
  for (const path of [
    '/api/notifications', '/api/notifications/unread-count', '/api/settings/public', '/api/patients/me/care-team',
    '/api/patients/me/consents', '/api/medication-schedules/me', `/api/appointments?${own}page=0&size=20`,
    `/api/medical-records?${own}page=0&size=20`, `/api/lab-results?${own}page=0&size=20`, `/api/imaging-results?${own}page=0&size=20`,
  ]) {
    r = await req('GET', path, { token });
    check(`GET ${path.split('?')[0]}`, r.status === 200, `${r.status}${r.status >= 400 ? ` ${r.json?.code ?? ''}` : ''}`);
  }
  r = await req('GET', '/api/admin/users', { token });
  check('patient is refused admin endpoints', r.status === 403, `${r.status}`);

  r = await req('POST', '/api/auth/refresh', { body: { refreshToken: tokens?.refreshToken } });
  check('refresh issues a new access token', r.status === 200 && !!r.json?.tokens?.accessToken, `${r.status}`);
  r = await req('POST', '/api/auth/logout', { token: r.json?.tokens?.accessToken ?? token, body: { refreshToken: r.json?.tokens?.refreshToken } });
  check('sign out', r.status === 200 || r.status === 204, `${r.status}`);
} catch (e) {
  check('API part finished', false, String(e).split('\n')[0]);
}

// ---- Part 2: web app in a browser ----------------------------------------------------------------------------------
const SECTIONS = [
  ['dashboard', 'Health Overview'], ['book-appointment', 'Schedule a visit with your healthcare provider'],
  ['my-appointments', 'Track and manage all your healthcare appointments'], ['consultation-history', 'Your complete clinical record'],
  ['prescriptions', 'Your medication history'], ['health-tracking', 'Record your readings and symptoms'],
  ['care-plans', 'The goals and next steps your care team has set out with you'], ['health-alerts', 'Reminders from automated screening'],
  ['medical-records', 'Your complete health record'], ['lab-results', 'Your laboratory test history'],
  ['imaging-results', 'Your radiology and imaging history'], ['documents', 'Files in your health record'],
  ['referrals', 'Referrals your doctors have made'], ['care-team', 'The doctors and other providers looking after you'],
  ['my-proxies', 'Caregivers can message your care team'], ['messages', 'Message your doctors and care team.'],
  ['notifications', null], ['billing', 'Your invoices, payments'], ['profile-settings', 'Your personal and health information'],
  ['settings', null], ['help-support', null],
];
// zod v4 probes `new Function("")` in a try/catch to choose its JIT mode; the CSP blocks it as intended and zod falls back.
const EXPECTED_CONSOLE = [/CSP violation: script-src eval$/, /status of 401/];

if (!API_ONLY) {
  console.log(`\nWeb ${WEB}`);
  mkdirSync(OUT, { recursive: true });
  const { chromium } = await import('@playwright/test');
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  await context.addInitScript(() => document.addEventListener('securitypolicyviolation',
    (e) => console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`)));
  const page = await context.newPage();
  let where = 'start';
  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error' && !EXPECTED_CONSOLE.some((re) => re.test(m.text()))) problems.push(`[${where}] console: ${m.text().slice(0, 200)}`); });
  page.on('pageerror', (e) => problems.push(`[${where}] page error: ${String(e).slice(0, 200)}`));
  page.on('response', (r) => {
    const wrongPassword = where === 'wrong-password' && r.status() === 401;
    if (r.url().startsWith(API) && r.status() >= 400 && !wrongPassword) problems.push(`[${where}] ${r.request().method()} ${r.url().slice(API.length).split('?')[0]} -> ${r.status()}`);
  });
  page.on('requestfailed', (r) => { if (r.failure()?.errorText !== 'net::ERR_ABORTED') problems.push(`[${where}] request failed: ${r.url().slice(0, 120)} ${r.failure()?.errorText}`); });
  page.on('dialog', (d) => d.accept().catch(() => {}));
  const shot = (name) => page.screenshot({ path: join(OUT, `${name}.png`) }).catch(() => {});

  try {
    where = 'landing';
    let res = await page.goto(WEB, { waitUntil: 'networkidle' });
    check('landing page loads', res?.status() === 200, `${res?.status()}`);

    where = 'wrong-password';
    await page.goto(`${WEB}/auth/login/web/login-web`, { waitUntil: 'networkidle' });
    await page.getByRole('radio', { name: 'Patient', exact: true }).click();
    await page.getByLabel('Email address').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(`${password}-wrong`);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    const refused = await page.getByText(/Invalid email or password/).waitFor({ timeout: 30_000 }).then(() => true, () => false);
    check('wrong password shows an error', refused && page.url().includes('/auth/login'));

    where = 'sign-in';
    await page.getByLabel('Password', { exact: true }).fill(password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.waitForURL((u) => u.pathname === '/dashboard/patient' || u.pathname === '/auth/accept-terms', { timeout: 60_000 });
    for (let i = 0; i < 4 && new URL(page.url()).pathname === '/auth/accept-terms'; i++) {
      await page.getByRole('button', { name: /^Accept / }).first().click();
      await page.waitForTimeout(1500);
    }
    await page.waitForURL((u) => u.pathname === '/dashboard/patient', { timeout: 60_000 });
    check('sign in lands on the patient dashboard', true);

    for (const [key, marker] of SECTIONS) {
      where = key;
      const before = problems.length;
      await page.goto(`${WEB}/dashboard/patient?section=${key}`, { waitUntil: 'networkidle', timeout: 60_000 });
      const shown = await (marker ? page.getByText(marker, { exact: false }).first() : page.getByRole('main').first())
        .waitFor({ timeout: 30_000 }).then(() => true, () => false);
      const fresh = problems.length - before;
      check(`page ${key}`, shown && fresh === 0, `${shown ? 'content shown' : 'content missing'}${fresh ? `, ${fresh} problem(s)` : ''}`);
      if (!shown || fresh) await shot(`page-${key}`);
    }

    where = 'call-link';
    res = await page.goto(`${WEB}/dashboard/call/00000000-0000-0000-0000-000000000000`, { waitUntil: 'networkidle' });
    check('video-call link serves the app', res?.status() === 200, `${res?.status()}`);
  } catch (e) {
    check(`browser part finished (stopped at ${where})`, false, String(e).split('\n')[0].slice(0, 200));
    await shot(`stopped-${where}`);
  } finally {
    await browser.close();
  }
  if (problems.length) {
    console.log('\nProblems seen in the browser:');
    for (const p of [...new Set(problems)]) console.log(`  ${p}`);
  }
}

const failed = results.filter((ok) => !ok).length;
console.log(`\n${results.length - failed}/${results.length} checks passed${failed ? `, ${failed} failed` : ''}. Test account: ${email}`);
process.exit(failed ? 1 : 0);
