// End-to-end smoke test of a DEPLOYED CareNexus environment (staging by default): every role and the flows between
// them, over the API and in headless Chromium. Unlike the Playwright suite in e2e/ (local docker stack, demo seed
// accounts, database resets) it needs only the two URLs and, for the admin, doctor and cross-role flows, the staging
// test administrator's credentials. It only adds data: one patient, one doctor and one caregiver per run
// (smoke+<timestamp>-<role>@example.com, passwords generated and never printed); the appointment it books is
// cancelled and the caregiver grant revoked at the end.
//
//   npm run smoke:staging                       # https://carenexus-{api,web}-staging.fly.dev
//   npm run smoke:staging -- --api-only         # skip the browser parts
//   SMOKE_API=https://api.example.com SMOKE_WEB=https://app.example.com npm run smoke:staging
//
// Admin credentials: SMOKE_ADMIN_EMAIL, SMOKE_ADMIN_PASSWORD, SMOKE_ADMIN_TOTP_SECRET (Base32, the account's
// two-factor secret), from the environment or from a git-ignored env file: SMOKE_ENV_FILE, else ./.env.smoke.local.
// Without them the admin, doctor and cross-role flows are reported as skipped and the run fails.
//
// Exit code 0 only when every check passes. Screenshots of failing pages go to smoke-results/ (git-ignored).
// Registration is rate limited (10 per hour per client address); one run registers three accounts.

import { Buffer } from 'node:buffer';
import { createHmac, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const ENV_FILE = process.env.SMOKE_ENV_FILE ?? '.env.smoke.local';
if (existsSync(ENV_FILE)) process.loadEnvFile(ENV_FILE);

const API = (process.env.SMOKE_API ?? 'https://carenexus-api-staging.fly.dev').replace(/\/$/, '');
const WEB = (process.env.SMOKE_WEB ?? 'https://carenexus-web-staging.fly.dev').replace(/\/$/, '');
const API_ONLY = process.argv.includes('--api-only');
const OUT = join(process.cwd(), 'smoke-results');
const ADMIN = {
  email: process.env.SMOKE_ADMIN_EMAIL, password: process.env.SMOKE_ADMIN_PASSWORD, totp: process.env.SMOKE_ADMIN_TOTP_SECRET,
};
const HAVE_ADMIN = !!(ADMIN.email && ADMIN.password && ADMIN.totp);

// ---- Reporting -----------------------------------------------------------------------------------------------------
const results = [];
let section = '';
const heading = (title) => { section = title; console.log(`\n== ${title}`); };
const check = (name, ok, detail = '') => {
  results.push({ section, name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
  return ok;
};
const skip = (name, why) => { results.push({ section, name, ok: false, skipped: true }); console.log(`SKIP  ${name}  (${why})`); };

// ---- HTTP ----------------------------------------------------------------------------------------------------------
async function call(method, path, { body, token } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), Origin: WEB },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try { json = await res.json(); } catch {}
  return { status: res.status, json };
}
const detail = (r) => `${r.status}${r.status >= 400 && r.json?.code ? ` ${r.json.code}` : ''}${r.status >= 400 && r.json?.message ? `: ${String(r.json.message).slice(0, 120)}` : ''}`;
const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
const uid = () => randomBytes(8).toString('hex');

// ---- TOTP (RFC 6238: HmacSHA1, 6 digits, 30 s), as the API's TotpService --------------------------------------------
function base32(s) {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of s.replace(/=+$/, '').toUpperCase()) bits += A.indexOf(c).toString(2).padStart(5, '0');
  return Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
}
let lastTotpStep = -1;
/** A code for a 30-second step not used before in this run (the API refuses a code it has already accepted). */
async function freshTotp(secret) {
  let step = Math.floor(Date.now() / 30_000);
  if (step <= lastTotpStep) { await sleep((lastTotpStep + 1) * 30_000 - Date.now() + 500); step = Math.floor(Date.now() / 30_000); }
  lastTotpStep = step;
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', base32(secret)).update(counter).digest();
  const o = h[19] & 15;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

// ---- Accounts ------------------------------------------------------------------------------------------------------
const stamp = Date.now();
const account = (role) => ({ role, email: `smoke+${stamp}-${role}@example.com`, password: `Sm0ke-${randomBytes(12).toString('base64url')}` });
const patient = { ...account('patient'), firstName: 'Smoke', lastName: `Patient${String(stamp).slice(-5)}` };
const doctor = { ...account('doctor'), firstName: 'Smoke', lastName: `Doctor${String(stamp).slice(-5)}` };
const carer = { ...account('carer'), firstName: 'Smoke', lastName: `Carer${String(stamp).slice(-5)}` };
let legal = { terms: '1', privacy: '1' };

async function register(user, role, extra = {}) {
  const r = await call('POST', '/api/auth/register', {
    body: {
      firstName: user.firstName, lastName: user.lastName, email: user.email, password: user.password, role,
      acceptedTermsVersion: legal.terms, acceptedPrivacyVersion: legal.privacy, ...extra,
    },
  });
  user.systemId = r.json?.user?.system_id ?? null;
  return r;
}
async function signIn(user) {
  const r = await call('POST', '/api/auth/login', { body: { email: user.email, password: user.password } });
  user.token = r.json?.tokens?.accessToken ?? null;
  user.refreshToken = r.json?.tokens?.refreshToken ?? null;
  if (r.json?.user?.system_id) user.systemId = r.json.user.system_id;
  return r;
}
async function signInAdmin() {
  let r = await call('POST', '/api/auth/login', { body: { email: ADMIN.email, password: ADMIN.password } });
  if (r.json?.challenge?.type !== 'MFA') return { r, token: null, challenge: r.json?.challenge?.type ?? 'none' };
  r = await call('POST', '/api/auth/mfa/verify', { body: { challengeToken: r.json.challenge.token, code: await freshTotp(ADMIN.totp) } });
  return { r, token: r.json?.tokens?.accessToken ?? null, challenge: 'MFA', role: r.json?.user?.role, systemId: r.json?.user?.system_id };
}

// Shared state between the API flows, the browser flows and the clean-up.
const state = { appointmentId: null, accessId: null, threadId: null, slotStart: null, admin: null };

// =====================================================================================================================
// API
// =====================================================================================================================
async function apiPublic() {
  heading(`API basics  ${API}`);
  let r = await call('GET', '/actuator/health');
  check('health is UP', r.status === 200 && r.json?.status === 'UP', `${r.status} ${r.json?.status ?? ''}`.trim());
  const preflight = (origin) => fetch(`${API}/api/auth/login`, {
    method: 'OPTIONS', signal: AbortSignal.timeout(30_000),
    headers: { Origin: origin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'content-type' },
  });
  let p = await preflight(WEB);
  check('CORS allows the web origin', p.headers.get('access-control-allow-origin') === WEB, `${p.status}`);
  p = await preflight('https://not-carenexus.example');
  check('CORS refuses another origin', !p.headers.get('access-control-allow-origin'), `${p.status}`);
  r = await call('GET', '/v3/api-docs');
  check('API docs are not public', r.status !== 200, `${r.status}`);
  r = await call('GET', '/api/users/me');
  check('anonymous request is refused', r.status === 401, `${r.status}`);
  r = await call('GET', '/api/legal/current');
  if (r.status === 200 && r.json?.terms?.version) legal = { terms: r.json.terms.version, privacy: r.json.privacy.version };
  check('current legal documents', r.status === 200 || r.status === 404, `${r.status} terms=${legal.terms} privacy=${legal.privacy}`);
}

async function apiPatient() {
  heading('Patient');
  let r = await register(patient, 'patient');
  check('register', r.status === 201, detail(r));
  r = await call('POST', '/api/auth/login', { body: { email: patient.email, password: `${patient.password}-wrong` } });
  check('wrong password is refused', r.status === 401, `${r.status}`);
  r = await signIn(patient);
  check('sign in', r.status === 200 && !!patient.token, detail(r));
  r = await call('GET', '/api/users/me', { token: patient.token });
  check('current user is a patient', r.status === 200 && r.json?.role === 'patient' && r.json?.email === patient.email, `${r.status} ${patient.systemId}`);
  const own = `patientSystemId=${encodeURIComponent(patient.systemId ?? '')}&`;
  for (const path of [
    '/api/notifications', '/api/notifications/unread-count', '/api/settings/public', '/api/patients/me/care-team',
    '/api/patients/me/consents', '/api/medication-schedules/me', `/api/appointments?${own}page=0&size=20`,
    `/api/medical-records?${own}page=0&size=20`, `/api/lab-results?${own}page=0&size=20`, `/api/imaging-results?${own}page=0&size=20`,
  ]) {
    r = await call('GET', path, { token: patient.token });
    check(`GET ${path.split('?')[0]}`, r.status === 200, detail(r));
  }
  r = await call('GET', '/api/admin/users', { token: patient.token });
  check('refused admin endpoints', r.status === 403, `${r.status}`);
  r = await call('POST', '/api/auth/refresh', { body: { refreshToken: patient.refreshToken } });
  check('refresh issues a new access token', r.status === 200 && !!r.json?.tokens?.accessToken, `${r.status}`);
  if (r.json?.tokens?.accessToken) { patient.token = r.json.tokens.accessToken; patient.refreshToken = r.json.tokens.refreshToken; }
}

async function apiAdminAndDoctor() {
  heading('Admin approves a new doctor');
  let r = await register(doctor, 'doctor', {
    licenseNo: `SMOKE-${stamp}`, specialization: 'General Practice',
    licensingBody: 'Medical and Dental Practitioners Council', licenseCountry: 'ZW',
  });
  check('doctor registers (pending verification)', r.status === 201, detail(r));
  r = await signIn(doctor);
  check('doctor cannot sign in before approval', r.status === 403 && !doctor.token, detail(r));

  if (!HAVE_ADMIN) { skip('admin flows', 'no SMOKE_ADMIN_* credentials'); return false; }
  const admin = await signInAdmin();
  state.admin = admin;
  if (!check('admin signs in with two-factor', admin.r.status === 200 && !!admin.token && admin.role === 'admin', `${detail(admin.r)} challenge=${admin.challenge}`)) return false;

  r = await call('GET', '/api/admin/doctors/pending?page=0&size=100', { token: admin.token });
  const pending = (r.json?.content ?? r.json ?? []);
  check('pending clinicians list shows the doctor', r.status === 200 && JSON.stringify(pending).includes(doctor.systemId ?? '§'), detail(r));
  r = await call('GET', `/api/admin/users/${doctor.systemId}`, { token: admin.token });
  check('admin opens the doctor account', r.status === 200, detail(r));
  r = await call('POST', `/api/admin/doctors/${doctor.systemId}/verify`, { token: admin.token });
  check('admin approves the doctor', r.status === 200, detail(r));
  r = await call('GET', '/api/admin/users?page=0&size=20', { token: admin.token });
  check('admin lists users', r.status === 200, detail(r));
  r = await call('GET', '/api/admin/settings', { token: admin.token });
  check('admin reads system settings', r.status === 200, detail(r));
  r = await call('GET', `/api/admin/users/${patient.systemId}/audit-logs?page=0&size=20`, { token: admin.token });
  check('admin reads a user\'s audit log', r.status === 200, detail(r));

  heading('Doctor');
  r = await signIn(doctor);
  check('approved doctor signs in', r.status === 200 && !!doctor.token, detail(r));
  r = await call('GET', '/api/users/me', { token: doctor.token });
  check('current user is a doctor', r.status === 200 && r.json?.role === 'doctor', `${r.status} ${doctor.systemId}`);
  r = await call('GET', '/api/appointments/booking-rules', { token: doctor.token });
  const rules = r.json ?? {};
  check('booking rules', r.status === 200, `${r.status} ${rules.openHour}-${rules.closeHour}h, weekends ${rules.allowWeekends}`);
  const open = Math.max(9, Number(rules.openHour ?? 8));
  const close = Math.min(open + 3, Number(rules.closeHour ?? 17));
  const hh = (h) => `${String(h).padStart(2, '0')}:00`;
  const blocks = [1, 2, 3, 4, 5].map((dayOfWeek) => ({ dayOfWeek, startTime: hh(open), endTime: hh(close), isActive: true }));
  r = await call('PUT', '/api/doctors/availability', { token: doctor.token, body: { slotMinutes: 30, blocks } });
  check('doctor saves weekly hours', r.status === 200 && r.json?.blocks?.length === 5, detail(r));
  for (const path of ['/api/doctors/availability', '/api/appointments?page=0&size=20', '/api/notifications']) {
    r = await call('GET', path, { token: doctor.token });
    check(`GET ${path.split('?')[0]}`, r.status === 200, detail(r));
  }
  r = await call('GET', '/api/admin/users', { token: doctor.token });
  check('doctor is refused admin endpoints', r.status === 403, `${r.status}`);
  return true;
}

async function apiCross() {
  heading('Patient books the doctor; both see it; they message');
  // First free 30-minute slot on a weekday 3 to 30 days ahead (clinic dates come from the API's slot list).
  for (let days = 3; days <= 30 && !state.slotStart; days++) {
    const date = new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
    const r = await call('GET', `/api/appointments/doctor/${doctor.systemId}/slots?date=${date}&durationMinutes=30`, { token: patient.token });
    const slot = (r.json?.slots ?? []).find((s) => s.available);
    if (slot) state.slotStart = slot.start;
  }
  check('patient finds a free slot with the doctor', !!state.slotStart, state.slotStart ?? 'none in 30 days');
  if (!state.slotStart) return;
  let r = await call('POST', '/api/appointments', {
    token: patient.token,
    body: { doctorSystemId: doctor.systemId, scheduledAt: state.slotStart, durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: `Smoke test ${stamp}` },
  });
  state.appointmentId = r.json?.appointmentId ?? null;
  check('patient books the appointment', (r.status === 201 || r.status === 200) && !!state.appointmentId, detail(r));
  r = await call('POST', '/api/appointments', {
    token: patient.token,
    body: { doctorSystemId: doctor.systemId, scheduledAt: state.slotStart, durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: `Smoke test ${stamp} twice` },
  });
  check('the same slot cannot be booked twice', r.status === 409, `${r.status}`);
  r = await call('GET', `/api/appointments?doctorSystemId=${doctor.systemId}&page=0&size=50`, { token: doctor.token });
  check('doctor sees the appointment', r.status === 200 && (r.json?.content ?? []).some((a) => a.appointmentId === state.appointmentId), detail(r));
  r = await call('GET', `/api/appointments/${state.appointmentId}`, { token: patient.token });
  check('patient sees the appointment', r.status === 200 && ['SCHEDULED', 'CONFIRMED', 'PENDING'].includes(r.json?.status), `${r.status} ${r.json?.status ?? ''}`);
  let notified = false;
  for (let i = 0; i < 10 && !notified; i++) {
    r = await call('GET', '/api/notifications?page=0&size=50', { token: doctor.token });
    notified = (r.json?.content ?? []).some((n) => Number(n.resourceId) === Number(state.appointmentId));
    if (!notified) await sleep(2000);
  }
  check('doctor is notified of the booking', notified, notified ? '' : 'no notification after 20 s');

  r = await call('POST', '/api/communications/threads', {
    token: patient.token,
    body: { patientSystemId: patient.systemId, type: 'PRIVATE', memberSystemIds: [doctor.systemId], clientThreadId: uid() },
  });
  state.threadId = r.json?.threadId ?? null;
  check('patient opens a conversation with the doctor', (r.status === 201 || r.status === 200) && !!state.threadId, detail(r));
  if (!state.threadId) return;
  const hello = `Smoke test hello ${stamp}`;
  r = await call('POST', `/api/communications/threads/${state.threadId}/messages`, { token: patient.token, body: { clientMessageId: uid(), body: hello } });
  check('patient sends a message', r.status === 201 || r.status === 200, detail(r));
  r = await call('GET', `/api/communications/threads/${state.threadId}/messages?limit=100`, { token: doctor.token });
  check('doctor reads the message', r.status === 200 && (r.json?.messages ?? []).some((m) => m.body === hello), detail(r));
  const reply = `Smoke test reply ${stamp}`;
  r = await call('POST', `/api/communications/threads/${state.threadId}/messages`, { token: doctor.token, body: { clientMessageId: uid(), body: reply } });
  check('doctor replies', r.status === 201 || r.status === 200, detail(r));
  r = await call('GET', `/api/communications/threads/${state.threadId}/messages?limit=100`, { token: patient.token });
  check('patient reads the reply', r.status === 200 && (r.json?.messages ?? []).some((m) => m.body === reply), detail(r));
}

async function apiCaregiver() {
  heading('Caregiver with access from the patient');
  let r = await register(carer, 'proxy');
  check('caregiver registers', r.status === 201, detail(r));
  r = await signIn(carer);
  check('caregiver signs in', r.status === 200 && !!carer.token, detail(r));
  r = await call('GET', '/api/users/me', { token: carer.token });
  check('current user is a caregiver', r.status === 200 && ['caregiver', 'proxy'].includes(r.json?.role), `${r.status} ${r.json?.role}`);
  r = await call('GET', `/api/appointments?patientSystemId=${patient.systemId}&page=0&size=20`, { token: carer.token });
  check('no access to the patient before a grant', r.status === 403, `${r.status}`);

  const flags = {
    canViewRecords: true, canViewAppointments: true, canViewMedications: true, canViewLabResults: true, canViewImaging: true,
    canViewConsultations: true, canViewBilling: false, canScheduleAppointments: false, canManageCarePlan: false, canManageBilling: false,
  };
  r = await call('POST', '/api/patients/me/caregiver-access', {
    token: patient.token, body: { caregiverEmail: carer.email, relationship: 'friend', accessLevel: 'VIEW_ONLY', ...flags },
  });
  state.accessId = r.json?.accessId ?? null;
  check('patient grants view-only access', (r.status === 201 || r.status === 200) && !!state.accessId, detail(r));
  r = await call('GET', '/api/caregiver/dependents', { token: carer.token });
  const list = Array.isArray(r.json) ? r.json : (r.json?.content ?? []);
  check('caregiver sees the patient', r.status === 200 && JSON.stringify(list).includes(patient.systemId ?? '§'), detail(r));
  r = await call('GET', `/api/appointments?patientSystemId=${patient.systemId}&page=0&size=20`, { token: carer.token });
  check('caregiver sees the patient\'s appointment', r.status === 200 && (!state.appointmentId || (r.json?.content ?? []).some((a) => a.appointmentId === state.appointmentId)), detail(r));
  r = await call('GET', `/api/medical-records?patientSystemId=${patient.systemId}&page=0&size=20`, { token: carer.token });
  check('caregiver reads the patient\'s records', r.status === 200, detail(r));
  if (state.slotStart) {
    r = await call('POST', '/api/appointments', {
      token: carer.token,
      body: { patientSystemId: patient.systemId, doctorSystemId: doctor.systemId, scheduledAt: state.slotStart, durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: 'Smoke test caregiver' },
    });
    check('view-only caregiver cannot book', r.status === 403, `${r.status}`);
  }
}

// =====================================================================================================================
// Browser
// =====================================================================================================================
const PAGES = {
  patient: [
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
  ],
  doctor: [
    ['schedule', 'Manage your appointments and consultations'], ['availability', 'Set the hours patients can book you in'],
    ['consultations', 'Your full consultation history and active sessions'], ['appointments', 'Complete appointment history for your patients'],
    ['patients', 'Patients on your care team, or with an appointment or consultation with you'], ['medical-records', 'All records for patients associated with you'],
    ['prescriptions', 'Issue prescriptions and manage the ones you have written'], ['labs', 'Laboratory test results for all your patients'],
    ['imaging', 'Imaging results for all your patients'], ['care-plans', 'Goals, interventions and review dates'],
    ['referrals', 'Refer patients to specialists'], ['ai-alerts', null], ['billing', 'Invoices for your consultations'],
    ['messages', 'Conversations with your patients and their caregivers.'], ['notifications', null],
    ['profile', 'Your professional profile and contact details'], ['settings', null], ['help-support', null],
  ],
  proxy: [
    ['dashboard', 'Loved One Overview'], ['appointments', 'Appointments for your linked patients'], ['medications', 'Prescriptions for your linked patients'],
    ['care-plans', 'Care plans for the people you care for'], ['notifications', null],
    ['documents-results', 'Lab results, imaging, medical records and consultations'], ['health-summary', 'Health overview for your linked patients'],
    ['health-alerts', 'Reminders from automated screening'], ['billing', 'Invoices, payments and insurance claims'],
    ['profile-settings', 'Patients you have been authorised to manage as proxy'], ['notification-settings', 'Choose how you receive notifications'],
    ['help-support', null],
  ],
  admin: [
    ['dashboard', 'What is waiting for an administrator'], ['users', 'Every CareNexus account.'], ['clinician-queue', 'Check each clinician\'s licence'],
    ['account-requests', 'Data exports, account closures and deletion requests.'], ['corrections', 'Requests from patients and caregivers to correct'],
    ['messages', 'Conversations people opened from Help & Support.'], ['notifications', null], ['help-support', null],
    ['audit-log', 'Who did what, and when.'], ['legal', 'The Terms of Service and the Privacy Policy'],
    ['facilities', 'Locations that can be chosen for in-person appointments'], ['system-settings', 'Values the clinic can change without a new deployment.'],
    ['account', 'Your password, sign-in email, two-factor authentication'],
  ],
};
const ROLE_RADIO = { patient: 'Patient', doctor: 'Doctor', proxy: 'Proxy / Carer', admin: 'Patient' };
// zod v4 probes `new Function("")` in a try/catch to choose its JIT mode; the CSP blocks it as intended and zod falls back.
const EXPECTED_CONSOLE = [/CSP violation: script-src eval$/, /status of 401/];

async function browserRole(browser, role, user, { extra } = {}) {
  heading(`Browser: ${role}  ${WEB}`);
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 } });
  await context.addInitScript(() => document.addEventListener('securitypolicyviolation',
    (e) => console.error(`CSP violation: ${e.violatedDirective} ${e.blockedURI}`)));
  const page = await context.newPage();
  let where = 'start';
  const problems = [];
  page.on('console', (m) => { if (m.type() === 'error' && !EXPECTED_CONSOLE.some((re) => re.test(m.text()))) problems.push(`[${role}/${where}] console: ${m.text().slice(0, 200)}`); });
  page.on('pageerror', (e) => problems.push(`[${role}/${where}] page error: ${String(e).slice(0, 200)}`));
  page.on('response', (r) => {
    const wrongPassword = where === 'wrong-password' && r.status() === 401;
    if (r.url().startsWith(API) && r.status() >= 400 && !wrongPassword) problems.push(`[${role}/${where}] ${r.request().method()} ${r.url().slice(API.length).split('?')[0]} -> ${r.status()}`);
  });
  page.on('requestfailed', (r) => { if (r.failure()?.errorText !== 'net::ERR_ABORTED') problems.push(`[${role}/${where}] request failed: ${r.url().slice(0, 120)} ${r.failure()?.errorText}`); });
  page.on('dialog', (d) => d.accept().catch(() => {}));
  const shot = (name) => page.screenshot({ path: join(OUT, `${role}-${name}.png`) }).catch(() => {});
  const home = `/dashboard/${role}`;

  try {
    where = 'sign-in';
    await page.goto(`${WEB}/auth/login/web/login-web`, { waitUntil: 'networkidle' });
    await page.getByRole('radio', { name: ROLE_RADIO[role], exact: true }).click();
    await page.getByLabel('Email address').fill(user.email);
    if (role === 'patient') {
      where = 'wrong-password';
      await page.getByLabel('Password', { exact: true }).fill(`${user.password}-wrong`);
      await page.getByRole('button', { name: 'Sign in', exact: true }).click();
      const refused = await page.getByText(/Invalid email or password/).waitFor({ timeout: 30_000 }).then(() => true, () => false);
      check('wrong password shows an error', refused && page.url().includes('/auth/login'));
      where = 'sign-in';
    }
    await page.getByLabel('Password', { exact: true }).fill(user.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    if (role === 'admin') {
      await page.getByLabel('Authentication code').waitFor({ timeout: 30_000 });
      await page.getByLabel('Authentication code').fill(await freshTotp(ADMIN.totp));
      await page.getByRole('button', { name: 'Verify and sign in' }).click();
    }
    await page.waitForURL((u) => u.pathname === home || u.pathname === '/auth/accept-terms', { timeout: 60_000 });
    for (let i = 0; i < 4 && new URL(page.url()).pathname === '/auth/accept-terms'; i++) {
      await page.getByRole('button', { name: /^Accept / }).first().click();
      await page.waitForTimeout(1500);
    }
    await page.waitForURL((u) => u.pathname === home, { timeout: 60_000 });
    check(`sign in${role === 'admin' ? ' with two-factor' : ''} lands on ${home}`, true);

    for (const [key, marker] of PAGES[role]) {
      where = key;
      const before = problems.length;
      await page.goto(`${WEB}${home}?section=${key}`, { waitUntil: 'networkidle', timeout: 60_000 });
      const shown = await (marker ? page.getByText(marker, { exact: false }).first() : page.getByRole('main').first())
        .waitFor({ timeout: 30_000 }).then(() => true, () => false);
      const fresh = problems.length - before;
      check(`page ${key}`, shown && fresh === 0, `${shown ? 'content shown' : 'content missing'}${fresh ? `, ${fresh} problem(s)` : ''}`);
      if (!shown || fresh) await shot(key);
    }
    if (extra) { where = 'cross'; await extra(page, home); }
  } catch (e) {
    check(`browser ${role} finished (stopped at ${where})`, false, String(e).split('\n')[0].slice(0, 200));
    await shot(`stopped-${where}`);
  } finally {
    await context.close();
  }
  if (problems.length) {
    console.log('  Problems seen:');
    for (const p of [...new Set(problems)]) console.log(`    ${p}`);
  }
}

/** Cross-role checks in the UI: the booked appointment and the conversation show up for each side. */
const seesText = async (page, url, text, label) => {
  await page.goto(url, { waitUntil: 'networkidle', timeout: 60_000 });
  const ok = await page.getByText(text, { exact: false }).first().waitFor({ timeout: 30_000 }).then(() => true, () => false);
  check(label, ok);
  if (!ok) await page.screenshot({ path: join(OUT, `cross-${label.replace(/\W+/g, '-')}.png`) }).catch(() => {});
};

// =====================================================================================================================
// Clean-up: the run's appointment is cancelled and the caregiver grant revoked, so the doctor's diary and the
// patient's caregiver list do not keep test rows active. The accounts themselves stay (they are marked smoke+...).
// =====================================================================================================================
async function cleanUp() {
  heading('Clean-up');
  if (state.accessId) {
    const r = await call('DELETE', `/api/patients/me/caregiver-access/${state.accessId}`, { token: patient.token });
    check('patient revokes the caregiver grant', r.status < 300, detail(r));
    const after = await call('GET', `/api/appointments?patientSystemId=${patient.systemId}&page=0&size=20`, { token: carer.token });
    check('caregiver loses access after the revoke', after.status === 403, `${after.status}`);
  }
  if (state.appointmentId) {
    const r = await call('DELETE', `/api/appointments/${state.appointmentId}`, { token: patient.token });
    check('patient cancels the appointment', r.status < 300, detail(r));
  }
}

// =====================================================================================================================
let rolesReady = false;
try {
  await apiPublic();
  await apiPatient();
  rolesReady = await apiAdminAndDoctor();
  if (rolesReady) {
    await apiCross();
    await apiCaregiver();
  } else {
    skip('doctor, booking, messaging and caregiver flows', 'the doctor could not be approved');
  }

  if (!API_ONLY) {
    mkdirSync(OUT, { recursive: true });
    const { chromium } = await import('@playwright/test');
    const browser = await chromium.launch();
    try {
      await browserRole(browser, 'patient', patient, {
        extra: async (page, home) => {
          if (state.appointmentId) await seesText(page, `${WEB}${home}?section=my-appointments`, doctor.lastName, 'patient sees the booking with the doctor in the UI');
          if (state.threadId) await seesText(page, `${WEB}${home}?section=messages`, doctor.lastName, 'patient sees the conversation with the doctor in the UI');
        },
      });
      if (rolesReady) {
        await browserRole(browser, 'doctor', doctor, {
          extra: async (page, home) => {
            if (state.appointmentId) await seesText(page, `${WEB}${home}?section=appointments`, patient.lastName, 'doctor sees the patient\'s booking in the UI');
            await seesText(page, `${WEB}${home}?section=patients`, patient.lastName, 'doctor sees the patient in the patient list');
          },
        });
        await browserRole(browser, 'proxy', carer, {
          extra: async (page, home) => {
            await seesText(page, `${WEB}${home}?section=dashboard`, patient.lastName, 'caregiver sees the patient in the UI');
          },
        });
      }
      if (HAVE_ADMIN) {
        await browserRole(browser, 'admin', { email: ADMIN.email, password: ADMIN.password }, {
          extra: async (page, home) => {
            await seesText(page, `${WEB}${home}?section=users`, 'smoke+', 'admin sees the test accounts in the user list');
          },
        });
      }
    } finally {
      await browser.close();
    }
  }
} catch (e) {
  check(`run finished (stopped in "${section}")`, false, String(e).split('\n')[0].slice(0, 200));
} finally {
  if (patient.token) await cleanUp().catch((e) => check('clean-up', false, String(e).split('\n')[0]));
}

// ---- Summary -------------------------------------------------------------------------------------------------------
const failed = results.filter((r) => !r.ok);
const bySection = {};
for (const r of results) {
  const s = (bySection[r.section] ??= { pass: 0, fail: 0 });
  if (r.ok) s.pass++;
  else s.fail++;
}
console.log('\nSummary');
for (const [name, s] of Object.entries(bySection)) console.log(`  ${s.fail ? 'FAIL' : 'ok  '}  ${name}: ${s.pass}/${s.pass + s.fail}`);
console.log(`\n${results.length - failed.length}/${results.length} checks passed${failed.length ? `, ${failed.length} failed or skipped` : ''}.`);
console.log(`Test accounts: ${patient.email}, ${doctor.email}, ${carer.email}`);
process.exit(failed.length ? 1 : 0);
