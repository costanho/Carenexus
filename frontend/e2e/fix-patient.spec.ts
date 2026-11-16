import { expect, type Locator, type Page, test } from '@playwright/test';
import { registerAccount } from './support/accounts';
import { type Api, type Clients, disposeAll, harareLocal, listAppointments, loginAll } from './support/api';
import { sql } from './support/db';
import { JAMES, LABELS, THOMAS } from './support/env';
import { escapeRe } from './support/ui';
import {
  bookWithBanda, cancelFixAppointments, dateIn, fixTag, freeSlot, pickDate, removeFixGrants, revealAppointment,
  signedInCountingPage,
} from './support/fix-patient';
import {
  type MonitoredPage, PATIENT_SECTIONS, type Section, apiIdle, expectNoArtifacts, futureDay, signedInPage, visit,
} from './support/smoke';

// Patient web fixes: doctor names, Patient ID, reschedule confirmation, one shared appointment
// list across panels, finalised consultations only, booking-form selection state, section
// headings, caregiver grant end dates in UTC+2, "Message Dr X" and the call-time-pending state; profile edits
// (phone, allergies, a duplicate phone, emergency contacts).
// Booking and rescheduling go through the slot picker: times are the doctor's 30-minute slots (seeded doctors work
// 08:00 to 13:00 and 14:00 to 18:00), offered as buttons named "HH:mm".
//
// Tables written: appointments (e2e-fix-patient-… reasons, cancelled in afterAll) and the notifications and
// call_sessions they raise; users and legal_acceptances (throwaway e2e-fix-patient-…@carenexus.test caregivers),
// dependent_access, consent_records and caregiver_activity_log (their grants, removed in afterAll), the private
// Thomas / Dr. Banda conversation (message_threads, thread_members, found or created); users and patients (Thomas's
// phone and allergies, edited through the UI and restored in a finally block, and a throwaway patient holding the
// duplicate phone); emergency_contacts (an e2e-fix-patient-… contact, deleted; the demo primary contact is made
// primary again); refresh_tokens and audit_log for every sign-in. scripts/dev-reset-e2e-data.sh removes the
// tagged rows.

const section = (nav: string): Section => PATIENT_SECTIONS.find(s => s.nav === nav)!;
const pressed = (l: Locator) => expect(l).toHaveAttribute('aria-pressed', 'true');

/** A start on the doctor's 30-minute grid inside the seeded working windows. */
interface GridSlot { days: number; hour24: number; minute: 0 | 30 }

const GRID_HOURS = [9, 10, 11, 14, 15, 16];

function gridKey(s: GridSlot): string {
  return `${futureDay(s.days).iso}T${String(s.hour24).padStart(2, '0')}:${String(s.minute).padStart(2, '0')}`;
}

/** "HH:mm": the slot picker's name for a start time. */
function gridTime(s: GridSlot): string {
  return gridKey(s).slice(11);
}

/** Starts (clinic wall clock, minute precision) of the active appointments of Dr. Banda and Thomas from now on. */
async function activeStarts(api: Clients): Promise<number[]> {
  const from = harareLocal(new Date());
  const filters = { status: 'SCHEDULED,RESCHEDULED', from };
  const rows = [
    ...await listAppointments(api.james, { ...filters, doctorSystemId: JAMES.systemId }),
    ...await listAppointments(api.thomas, { ...filters, patientSystemId: THOMAS.systemId }),
  ];
  return rows.map(a => new Date(String(a.scheduledAt).slice(0, 16)).getTime());
}

/** A free grid slot 30-90 days ahead, an hour away from every active appointment of both and from `avoid`. */
async function freeGridSlot(api: Clients, avoid: GridSlot[] = []): Promise<GridSlot> {
  const busy = [...await activeStarts(api), ...avoid.map(s => new Date(gridKey(s)).getTime())];
  for (;;) {
    const s: GridSlot = {
      days:   30 + Math.floor(Math.random() * 60),
      hour24: GRID_HOURS[Math.floor(Math.random() * GRID_HOURS.length)],
      minute: Math.random() < 0.5 ? 0 : 30,
    };
    const t = new Date(gridKey(s)).getTime();
    if (busy.every(b => Math.abs(b - t) >= 60 * 60_000)) return s;
  }
}

/**
 * The first grid slot at least 30 minutes from now (clinic time) inside a working window: earlier than any seed
 * appointment, so it becomes the patient's next one.
 */
function soonGridSlot(): GridSlot {
  const start = new Date(Math.ceil((Date.now() + 30 * 60_000) / 1_800_000) * 1_800_000);
  const local = harareLocal(start);
  let days = local.slice(0, 10) === futureDay(0).iso ? 0 : 1;
  let hour24 = Number(local.slice(11, 13));
  let minute = Number(local.slice(14, 16)) as 0 | 30;
  const inWindow = (h: number, m: number) => { const t = h * 60 + m; return (t >= 8 * 60 && t + 30 <= 13 * 60) || (t >= 14 * 60 && t + 30 <= 18 * 60); };
  while (!inWindow(hour24, minute)) {
    minute = minute === 0 ? 30 : 0;
    if (minute === 0) hour24 += 1;
    if (hour24 >= 18) { days += 1; hour24 = 8; minute = 0; }
  }
  return { days, hour24, minute };
}

/** Chooses a start time in the slot picker labelled `group` ("Select Time" when booking, "New time" when moving). */
async function pickSlot(page: Page, group: string, time: string): Promise<void> {
  const button = page.getByRole('group', { name: group, exact: true }).getByRole('button', { name: time, exact: true });
  await expect(button, `${time} is offered`).toBeEnabled();
  await button.click();
  await pressed(button);
}

test.describe('patient fixes', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => {
    api = await loginAll();
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (api) {
      await cancelFixAppointments(api.thomas);
      await removeFixGrants(api.thomas);
    }
    await disposeAll(api);
  });

  test('doctor names carry a single "Dr." title in the booking list and appointment cards', async ({ browser }) => {
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;

    await visit(page, section('Book Appointment'));
    // Seed rows whose stored first name already starts with "Dr." (DOC-00005..00009).
    await page.getByLabel('Search doctors').fill('Kariuki');
    await expect(page.getByRole('button', { name: /^Dr\. Michael Kariuki/ })).toBeVisible();
    await page.getByLabel('Search doctors').fill('');
    const more = page.getByRole('button', { name: /^Show more doctors/ });
    if (await more.isVisible()) await more.click();
    await expect(page.getByRole('button', { name: /^Dr\. James Banda/ })).toBeVisible();
    await expect(page.getByText(/\bDr\.?\s+Dr\b/i)).toHaveCount(0);

    for (const nav of ['My Appointments', 'Consultation History', 'Prescriptions', 'Lab Results', 'Imaging Results', 'Medical Records', 'Dashboard']) {
      await visit(page, section(nav));
      await apiIdle(page);
      await expect(page.getByText(/\bDr\.?\s+Dr\b/i), `${nav}: no doubled title`).toHaveCount(0);
      await expectNoArtifacts(page, nav);
    }
    monitor.expectClean('doctor names');
  });

  test('profile shows the patient system id', async ({ browser }) => {
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await visit(page, section('Profile & Settings'));
    const account = page.locator('div')
      .filter({ has: page.getByText('Account Details', { exact: true }) })
      .filter({ has: page.getByText('Patient ID', { exact: true }) }).last();
    await expect(account.getByText(THOMAS.systemId, { exact: true })).toBeVisible();
    // The hero box shows it too.
    await expect(page.getByText(THOMAS.systemId, { exact: true })).toHaveCount(2);
    monitor.expectClean('profile');
  });

  test('rescheduling shows the confirmation after the list refreshes', async ({ browser }) => {
    const reason = `${fixTag()} reschedule`;
    const from = await freeGridSlot(api);
    let to = await freeGridSlot(api, [from]);
    while (to.days === from.days) to = await freeGridSlot(api, [from]);
    await bookWithBanda(api.thomas, reason, { days: from.days, hour24: from.hour24, minute: from.minute });

    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await visit(page, section('My Appointments'));
    await apiIdle(page);
    const card = await revealAppointment(page, reason);
    await card.getByRole('button', { name: /^Reschedule appointment with / }).click();

    // The panel is the innermost box with its heading and its submit button: the "New time" group only appears
    // once a date is chosen, so it cannot locate the panel before pickDate.
    const panel = page.locator('div')
      .filter({ has: page.getByRole('heading', { name: 'Choose a new date and time' }) })
      .filter({ has: page.getByRole('button', { name: 'Confirm new time' }) }).last();
    await pickDate(panel, to.days);
    await pickSlot(page, 'New time', gridTime(to));
    const put = page.waitForResponse(r => /\/api\/appointments\/\d+$/.test(r.url()) && r.request().method() === 'PUT');
    const refetch = page.waitForResponse(r => new URL(r.url()).pathname === '/api/appointments' && r.request().method() === 'GET');
    await panel.getByRole('button', { name: 'Confirm new time' }).click();
    expect((await put).status()).toBe(200);
    await refetch;

    const notice = page.getByText('Your appointment has been moved to the new time.', { exact: true });
    await expect(notice).toBeVisible();
    await expect(notice).toHaveCount(1);
    const moved = await revealAppointment(page, reason);
    await expect(moved.getByText('Rescheduled', { exact: true })).toBeVisible();
    // Card dates read "Wed 18 Nov 2026" and times "10:30 CAT" (clinic time).
    const [y, m, d] = futureDay(to.days).iso.split('-').map(Number);
    const dateLabel = new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
      .formatToParts(new Date(Date.UTC(y, m - 1, d)));
    const part = (t: string) => dateLabel.find(p => p.type === t)?.value ?? '';
    await expect(moved.getByText(`${part('weekday')} ${part('day')} ${part('month')} ${part('year')}`, { exact: true })).toBeVisible();
    await expect(moved.getByText(`${gridTime(to)} CAT`, { exact: true })).toBeVisible();
    monitor.expectClean('reschedule');
  });

  test('one appointment request per screen, and the right rail updates after booking without a reload', async ({ browser }) => {
    const reason = `${fixTag()} right rail`;
    const slot = soonGridSlot();
    const upcoming = (await listAppointments(api.thomas, {
      patientSystemId: THOMAS.systemId, status: 'SCHEDULED,RESCHEDULED', from: harareLocal(new Date()), order: 'asc',
    })).map(a => String(a.scheduledAt).slice(0, 16)).sort();
    const ours = gridKey(slot);
    expect(upcoming[0] === undefined || ours < upcoming[0], `precondition: ${ours} is before the next appointment ${upcoming[0]}`).toBe(true);

    const up = await signedInCountingPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor, gets } = up;
    // The canonical paged list (GET /api/appointments) and the bookable-doctor directory.
    const apptList = /^\/api\/appointments$/;
    const doctorList = /^\/api\/doctors\/profile\/directory$/;
    await expect(page.getByText('Health Overview', { exact: true })).toBeVisible();
    await apiIdle(page);
    // The dashboard's Upcoming Appointments and the right rail share one list.
    expect(gets(apptList), 'appointment list requests on the dashboard').toBe(1);
    const rail = page.locator('div')
      .filter({ has: page.getByRole('heading', { name: 'Upcoming Appointment', exact: true }) })
      .filter({ has: page.getByRole('heading', { name: 'Quick Access', exact: true }) }).last();
    await expect(rail.getByText(reason, { exact: true })).toHaveCount(0);

    await visit(page, section('Book Appointment'));
    await apiIdle(page);
    expect(gets(apptList), 'opening Book Appointment refreshes the list once').toBeLessThanOrEqual(2);
    await page.getByRole('button', { name: /^In Person/ }).click();
    await page.getByLabel('Search doctors').fill('Banda');
    await page.getByRole('button', { name: /^Dr\. James Banda/ }).click();
    const form = page.locator('div')
      .filter({ has: page.getByText('Date & Time', { exact: true }) })
      .filter({ has: page.getByText('Select Date', { exact: true }) }).last();
    await pickDate(form, slot.days);
    await pickSlot(page, 'Select Time', gridTime(slot));
    // In-person visits ask for a clinic location once the clinic has facilities; wait for that list first.
    await expect(page.getByText('Loading clinic locations…')).toHaveCount(0, { timeout: 20_000 });
    const location = page.getByLabel('Clinic location', { exact: true });
    if (await location.isVisible()) await location.selectOption({ index: 1 });
    await page.getByPlaceholder('Describe your symptoms or reason for the appointment...').fill(reason);
    await apiIdle(page);
    const doctorsBefore = gets(doctorList);
    const created = page.waitForResponse(r => r.url().endsWith('/api/appointments') && r.request().method() === 'POST');
    await page.getByRole('button', { name: /Book Appointment$/ }).last().click();
    expect((await created).status()).toBe(201);
    await expect(page.getByText('Appointment Booked!')).toBeVisible();

    // No reload: the right rail (still mounted beside the form) now shows the new appointment.
    await expect(rail.getByText(reason, { exact: true })).toBeVisible();
    // The rail adds the specialization: "with Dr. James Banda (General Practitioner)".
    await expect(rail.getByText(new RegExp(`^with ${escapeRe(LABELS.doctor)}( \\(.+\\))?$`))).toBeVisible();
    await apiIdle(page);
    expect(gets(doctorList) - doctorsBefore, 'the doctor list is not re-requested for the refresh').toBe(0);
    monitor.expectClean('book and right rail');
  });

  test('patients see only finalised consultations, the same on the dashboard and in Consultation History', async ({ browser }) => {
    const rows = (await sql(`SELECT status || '|' || coalesce(chief_complaint, '') FROM consultations WHERE patient_system_id = '${THOMAS.systemId}'`))
      .split('\n').filter(Boolean).map(r => { const i = r.indexOf('|'); return { status: r.slice(0, i), complaint: r.slice(i + 1) }; });
    const completed = rows.filter(r => r.status === 'COMPLETED');
    const hidden = rows.filter(r => r.status !== 'COMPLETED' && r.complaint);
    expect(hidden.length, 'precondition: the dev seed has draft consultations for Thomas').toBeGreaterThan(0);
    expect(completed.length, 'precondition: the dev seed has a completed consultation for Thomas').toBeGreaterThan(0);

    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await apiIdle(page);
    const recent = page.locator('div')
      .filter({ has: page.getByRole('heading', { name: 'Recent Consultations', exact: true }) })
      .filter({ has: page.getByText('View all consultations →', { exact: true }) }).last();
    await expect(recent.getByText(completed[0].complaint.slice(0, 40), { exact: false }).first()).toBeVisible();
    for (const d of hidden) await expect(recent.getByText(d.complaint.slice(0, 40), { exact: false })).toHaveCount(0);

    await visit(page, section('Consultation History'));
    await apiIdle(page);
    // The history pane only (the right rail beside it may show an appointment reason that
    // matches a draft's complaint). The list shows 20 per page.
    const toggles = page.getByRole('button', { name: /^Show full record/ });
    const history = page.locator('div')
      .filter({ has: page.getByRole('heading', { name: 'Consultation History', exact: true }) })
      .filter({ has: toggles.first() }).last();
    await expect(history.getByRole('button', { name: /^Show full record/ })).toHaveCount(Math.min(completed.length, 20));
    await expect(history.getByText(completed[0].complaint.slice(0, 40), { exact: false }).first()).toBeVisible();
    for (const d of hidden) await expect(history.getByText(d.complaint.slice(0, 40), { exact: false })).toHaveCount(0);
    await expect(history.getByRole('listitem', { name: `Total: ${completed.length}`, exact: true })).toBeVisible();
    await expect(page.getByText('Draft', { exact: true })).toHaveCount(0);
    await expect(page.getByText('In Progress', { exact: true })).toHaveCount(0);
    monitor.expectClean('consultations');
  });

  test('booking form announces the chosen type, doctor, duration, date and time', async ({ browser }) => {
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page } = up;
    await visit(page, section('Book Appointment'));

    const video = page.getByRole('button', { name: /^Video Call/ });
    await video.click();
    await pressed(video);
    await expect(page.getByRole('button', { name: /^In Person/ })).toHaveAttribute('aria-pressed', 'false');

    await page.getByLabel('Search doctors').fill('Banda');
    const doc = page.getByRole('button', { name: /^Dr\. James Banda/ });
    await doc.click();
    await pressed(doc);

    // Duration comes before the time: the offered times depend on it.
    const duration = page.getByRole('group', { name: 'Duration', exact: true });
    await pressed(duration.getByRole('button', { name: '30 min', exact: true }));
    await duration.getByRole('button', { name: '45 min', exact: true }).click();
    await pressed(duration.getByRole('button', { name: '45 min', exact: true }));
    await expect(duration.getByRole('button', { name: '30 min', exact: true })).toHaveAttribute('aria-pressed', 'false');

    const form = page.locator('div')
      .filter({ has: page.getByText('Date & Time', { exact: true }) })
      .filter({ has: page.getByText('Select Date', { exact: true }) }).last();
    await pickDate(form, 40);
    await pressed(form.getByRole('button', { name: futureDay(40).label, exact: true }));

    // The first free time of that day ("HH:mm"); unavailable ones are named "HH:mm, not available".
    const times = page.getByRole('group', { name: 'Select Time', exact: true });
    const first = times.getByRole('button', { name: /^\d{2}:\d{2}$/ }).first();
    await expect(first).toBeEnabled();
    const time = (await first.getAttribute('aria-label')) ?? (await first.innerText());
    await first.click();
    await pressed(times.getByRole('button', { name: time, exact: true }));
    await expect(page.getByText(`${time} CAT`, { exact: true }), 'the summary shows the chosen time').toBeVisible();
  });

  test('every patient section title is a heading', async ({ browser }) => {
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page } = up;
    // Messages and Notifications belong to the shared messaging screens.
    for (const s of PATIENT_SECTIONS.filter(x => x.nav !== 'Messages' && x.nav !== 'Notifications')) {
      await visit(page, s);
      await expect(page.getByRole('heading', { name: s.title, exact: true }).first(), `${s.nav}: "${s.title}" is a heading`).toBeVisible();
    }
  });

  test('caregiver grant form accepts an end date in Africa/Harare (UTC+2)', async ({ browser }) => {
    const carer = await registerAccount({ role: 'proxy', tag: fixTag(), firstName: 'Fix', lastName: 'Carer' });
    const up = await signedInPage(browser, THOMAS, { timezoneId: 'Africa/Harare' });
    pages.push(up);
    const { page, monitor } = up;
    const end = dateIn('Africa/Harare', 200);

    await visit(page, section('My Proxies (Caregivers)'));
    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await page.getByLabel('Caregiver email').fill(carer.email);
    await page.getByLabel('Relationship').fill('Neighbour');
    await page.getByRole('radio', { name: /^View only\./ }).click();
    await page.getByLabel('Access ends on (optional)').fill(end);
    const granted = page.waitForResponse(r => r.url().endsWith('/api/patients/me/caregiver-access') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Grant access', exact: true }).click();
    expect((await granted).status()).toBeLessThan(300);
    await expect(page.getByText(/^Access granted to /)).toBeVisible();

    const g = (await api.thomas.ok(api.thomas.get<any[]>('/api/patients/me/caregiver-access'), 'grants')).find(x => x.caregiverEmail === carer.email);
    expect(String(g?.expiresAt ?? '').slice(0, 10), 'end date stored').toBe(end);
    monitor.expectClean('grant end date');
  });

  test('"Message Dr X" on an appointment opens the private conversation with that doctor', async ({ browser }) => {
    const reason = `${fixTag()} message`;
    await bookWithBanda(api.thomas, reason, await freeSlot(api.james, api.thomas));

    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await visit(page, section('My Appointments'));
    await apiIdle(page);
    const card = await revealAppointment(page, reason);
    const opened = page.waitForResponse(r => r.url().endsWith('/api/communications/threads') && r.request().method() === 'POST');
    await card.getByRole('button', { name: `Message ${LABELS.doctor}`, exact: true }).click();
    const res = await opened;
    expect(res.status(), 'existing (200) or new (201) private thread').toBeLessThan(300);
    const thread = await res.json();
    expect(thread.type).toBe('PRIVATE');
    expect((thread.members as any[]).map(m => m.systemId).sort()).toEqual([JAMES.systemId, THOMAS.systemId].sort());

    await expect(page.getByText('Message your doctors and care team.', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: LABELS.doctor, exact: true })).toBeVisible();
    await expect(page.getByLabel('Message', { exact: true })).toBeVisible();
    monitor.expectClean('message doctor');
  });

  test('a video call whose time awaits confirmation is a note, not a call button', async ({ browser }) => {
    const pending = (await upcomingVideo(api.thomas))
      .filter(a => a.callStatus === 'NEEDS_TIME_CONFIRMATION');
    expect(pending.length, 'precondition: the dev seed has an upcoming video visit awaiting time confirmation').toBeGreaterThan(0);

    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await apiIdle(page);
    await visit(page, section('My Appointments'));
    await apiIdle(page);
    const card = await revealAppointment(page, pending[0].reasonForVisit);
    await expect(card.getByText(/^Video call not open yet\./)).toBeVisible();
    await expect(page.getByRole('button', { name: /Time to be confirmed/ })).toHaveCount(0);
    monitor.expectClean('call time pending');
  });
});

test.describe('patient profile edits', () => {
  let api: Clients;
  const pages: MonitoredPage[] = [];

  test.beforeAll(async () => { api = await loginAll(); });
  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    await disposeAll(api);
  });

  /** A random local number for the phone box ("77" and seven digits). */
  const localNumber = () => `77${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`;

  test('phone and allergies: each save sends only the changed field; a phone of another account is refused under the field', async ({ browser }) => {
    const original = await api.thomas.ok(api.thomas.get<any>('/api/profile'), 'profile before');
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    try {
      await visit(page, section('Profile & Settings'));
      await apiIdle(page);

      await test.step('phone', async () => {
        await page.getByRole('button', { name: 'Edit personal information' }).click();
        const phone = page.getByRole('group', { name: /^Phone number/ });
        const dial = (await phone.getByText(/^\+\d{1,4}$/).first().innerText()).trim();
        const local = localNumber();
        await phone.getByPlaceholder('Phone number').fill(local);
        const put = page.waitForResponse(r => new URL(r.url()).pathname === '/api/profile' && r.request().method() === 'PUT');
        await page.getByRole('button', { name: 'Save changes', exact: true }).click();
        const res = await put;
        expect(res.status(), 'phone saved').toBe(200);
        expect(res.request().postDataJSON(), 'PUT /api/profile carries only the phone').toEqual({ phone: `${dial} ${local}` });
        await expect(page.getByText(`${dial} ${local}`, { exact: true })).toBeVisible();
        monitor.expectClean('phone');
      });

      await test.step('allergies', async () => {
        await page.getByRole('button', { name: 'Edit medical profile' }).click();
        // The form puts focus on its first field when it opens. Type only after that: focus landing there later
        // would take it from the Allergies box, whose blur adds the typed entry and disables the Add button.
        await expect(page.getByLabel('Blood type', { exact: true })).toBeFocused();
        const allergy = `${fixTag()} allergy`.slice(0, 60);
        await page.getByRole('textbox', { name: /^Allergies/ }).fill(allergy);
        await page.getByRole('button', { name: 'Add to Allergies' }).click();
        const put = page.waitForResponse(r => new URL(r.url()).pathname === '/api/profile' && r.request().method() === 'PUT');
        await page.getByRole('button', { name: 'Save changes', exact: true }).click();
        const res = await put;
        expect(res.status(), 'allergies saved').toBe(200);
        const body = res.request().postDataJSON();
        expect(Object.keys(body), 'PUT /api/profile carries only the allergies').toEqual(['allergies']);
        expect(JSON.parse(body.allergies), 'allergies are sent as a JSON array in a string').toContain(allergy);
        await expect(page.getByRole('list', { name: 'Allergies', exact: true }).getByText(allergy, { exact: true })).toBeVisible();
        monitor.expectClean('allergies');
      });

      await test.step('a phone number another account uses', async () => {
        monitor.allow({ method: 'PUT', url: /^\/api\/profile$/, status: 409, why: 'the phone number belongs to another (throwaway) account' });
        await page.getByRole('button', { name: 'Edit personal information' }).click();
        const phone = page.getByRole('group', { name: /^Phone number/ });
        const dial = (await phone.getByText(/^\+\d{1,4}$/).first().innerText()).trim();
        const local = localNumber();
        await registerAccount({ role: 'patient', tag: fixTag(), phone: `${dial} ${local}` });
        await phone.getByPlaceholder('Phone number').fill(local);
        const put = page.waitForResponse(r => new URL(r.url()).pathname === '/api/profile' && r.request().method() === 'PUT');
        await page.getByRole('button', { name: 'Save changes', exact: true }).click();
        expect((await put).status(), 'duplicate phone refused').toBe(409);
        await expect(page.getByText('Phone number already registered', { exact: true })).toBeVisible();
        await page.getByRole('button', { name: 'Cancel', exact: true }).click();
        monitor.expectClean('duplicate phone');
      });
    } finally {
      // Thomas's demo profile as it was.
      await api.thomas.put('/api/profile', { phone: original.phone ?? undefined, allergies: original.allergies ?? '[]' });
    }
  });

  test('emergency contacts: add, edit, make primary and delete', async ({ browser }) => {
    const before = await api.thomas.ok(api.thomas.get<any[]>('/api/profile/emergency-contacts'), 'contacts before');
    const primaryBefore = before.find(c => c.isPrimary);
    const name = `${fixTag()} contact`.slice(0, 60);
    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    try {
      await visit(page, section('Profile & Settings'));
      await apiIdle(page);
      const contacts = page.getByRole('list', { name: 'Emergency contacts' });

      await test.step('add', async () => {
        await page.getByRole('button', { name: 'Add contact', exact: true }).first().click();
        const dialog = page.getByRole('dialog').filter({ hasText: 'Add emergency contact' });
        await expect(dialog).toBeVisible();
        await dialog.getByLabel('Full name').fill(name);
        await dialog.getByLabel('Relationship').fill('Neighbour');
        await dialog.getByRole('group', { name: /^Phone number/ }).getByPlaceholder('Phone number').fill(localNumber());
        const created = page.waitForResponse(r => new URL(r.url()).pathname === '/api/profile/emergency-contacts' && r.request().method() === 'POST');
        await dialog.getByRole('button', { name: 'Add contact', exact: true }).click();
        expect((await created).status(), 'contact added').toBeLessThan(300);
        await expect(contacts.getByText(name, { exact: true })).toBeVisible();
      });

      await test.step('edit and make primary', async () => {
        await page.getByRole('button', { name: `Edit ${name}`, exact: true }).click();
        const dialog = page.getByRole('dialog').filter({ hasText: 'Edit emergency contact' });
        await expect(dialog).toBeVisible();
        await dialog.getByLabel('Relationship').fill('Friend');
        await dialog.getByRole('switch', { name: 'Primary contact' }).click();
        const saved = page.waitForResponse(r => /\/api\/profile\/emergency-contacts\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT');
        await dialog.getByRole('button', { name: 'Save changes', exact: true }).click();
        const res = await saved;
        expect(res.status(), 'contact saved').toBe(200);
        expect(res.request().postDataJSON(), 'only the changed fields are sent').toEqual({ relationship: 'Friend', isPrimary: true });
        const row = contacts.getByRole('listitem').filter({ hasText: name });
        await expect(row.getByText('Primary', { exact: true })).toBeVisible();
        await expect(row.getByText('Friend')).toBeVisible();
      });

      await test.step('delete', async () => {
        const deleted = page.waitForResponse(r => /\/api\/profile\/emergency-contacts\/\d+$/.test(new URL(r.url()).pathname) && r.request().method() === 'DELETE');
        await page.getByRole('button', { name: `Delete ${name}`, exact: true }).click(); // the confirm dialog is accepted
        expect((await deleted).status(), 'contact deleted').toBeLessThan(300);
        await expect(contacts.getByText(name, { exact: true })).toHaveCount(0);
      });
      monitor.expectClean('emergency contacts');
    } finally {
      const now = await api.thomas.get<any[]>('/api/profile/emergency-contacts');
      for (const c of (now.body ?? []) as any[]) {
        if (c.name === name) await api.thomas.del(`/api/profile/emergency-contacts/${c.contactId}`);
      }
      // Nothing is promoted automatically: give the demo primary contact its place back.
      if (primaryBefore) await api.thomas.put(`/api/profile/emergency-contacts/${primaryBefore.contactId}`, { isPrimary: true });
    }
  });
});

/** Thomas's active VIDEO appointments that have not started yet (canonical paged list). */
async function upcomingVideo(thomas: Api): Promise<any[]> {
  const rows = await listAppointments(thomas, {
    patientSystemId: THOMAS.systemId, status: 'SCHEDULED,RESCHEDULED', from: harareLocal(new Date()), order: 'asc',
  });
  return rows.filter(a => a.type === 'VIDEO');
}
