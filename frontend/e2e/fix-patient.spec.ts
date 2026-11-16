import { expect, type Locator, test } from '@playwright/test';
import { type Clients, disposeAll, loginAll } from './support/api';
import { sql } from './support/db';
import { JAMES, LABELS, THOMAS } from './support/env';
import { escapeRe } from './support/ui';
import {
  bookWithBanda, cancelFixAppointments, dateIn, fixTag, freeSlot, pickDate, pickTime, registerFixCaregiver,
  removeFixGrants, revealAppointment, signedInCountingPage, soonSlot,
} from './support/fix-patient';
import {
  type MonitoredPage, PATIENT_SECTIONS, type Section, apiIdle, expectNoArtifacts, futureDay, signedInPage, visit,
} from './support/smoke';

// Patient web fixes: doctor names, Patient ID, reschedule confirmation, one shared appointment
// list across panels, finalised consultations only, booking-form selection state, section
// headings, caregiver grant end dates in UTC+2, "Message Dr X" and the call-time-pending state.
// Data created here starts with "e2e-fix-patient-" and is removed in afterAll.

const section = (nav: string): Section => PATIENT_SECTIONS.find(s => s.nav === nav)!;
const pressed = (l: Locator) => expect(l).toHaveAttribute('aria-pressed', 'true');

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
    const more = page.getByText(/^Show \d+ more doctors?$/);
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
    const from = await freeSlot(api.james, api.thomas);
    let to = await freeSlot(api.james, api.thomas);
    while (to.days === from.days) to = await freeSlot(api.james, api.thomas);
    await bookWithBanda(api.thomas, reason, from);

    const up = await signedInPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor } = up;
    await visit(page, section('My Appointments'));
    await apiIdle(page);
    const card = await revealAppointment(page, reason);
    await card.getByRole('button', { name: /Reschedule$/ }).click();

    const panel = page.locator('div')
      .filter({ has: page.getByText('Choose a new date and time', { exact: true }) })
      .filter({ has: page.getByRole('radiogroup', { name: 'Minute' }) }).last();
    await pickDate(panel, to.days);
    await pickTime(panel, to.hour24, to.minute);
    const put = page.waitForResponse(r => /\/api\/appointments\/\d+$/.test(r.url()) && r.request().method() === 'PUT');
    const refetch = page.waitForResponse(r => r.url().includes(`/api/appointments/patient/${THOMAS.systemId}`));
    await panel.getByRole('button', { name: 'Confirm new time' }).click();
    expect((await put).status()).toBe(200);
    await refetch;

    const notice = page.getByText('Your appointment has been moved to the new time.', { exact: true });
    await expect(notice).toBeVisible();
    await expect(notice).toHaveCount(1);
    const moved = await revealAppointment(page, reason);
    await expect(moved.getByText('Rescheduled', { exact: true })).toBeVisible();
    await expect(moved.getByText(futureDay(to.days).date.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }))).toBeVisible();
    monitor.expectClean('reschedule');
  });

  test('one appointment request per screen, and the right rail updates after booking without a reload', async ({ browser }) => {
    const reason = `${fixTag()} right rail`;
    const slot = soonSlot();
    const upcoming = (await api.thomas.ok(api.thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`), 'appointments'))
      .filter(a => (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') && new Date(a.scheduledAt).getTime() > Date.now())
      .map(a => String(a.scheduledAt).slice(0, 16)).sort();
    const ours = `${futureDay(slot.days).iso}T${String(slot.hour24).padStart(2, '0')}:${String(slot.minute).padStart(2, '0')}`;
    expect(upcoming[0] === undefined || ours < upcoming[0], `precondition: ${ours} is before the next appointment ${upcoming[0]}`).toBe(true);

    const up = await signedInCountingPage(browser, THOMAS);
    pages.push(up);
    const { page, monitor, gets } = up;
    const apptList = /^\/api\/appointments\/patient\//;
    const doctorList = /^\/api\/doctors\/profile\/list\/active$/;
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
    await pickTime(form, slot.hour24, slot.minute);
    await page.getByPlaceholder('Describe your symptoms or reason for the appointment...').fill(reason);
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
    // matches a draft's complaint).
    const history = page.locator('div')
      .filter({ has: page.getByRole('heading', { name: 'Consultation History', exact: true }) })
      .filter({ has: page.getByText('Show full record ▼', { exact: true }) }).last();
    await expect(history.getByText('Show full record ▼', { exact: true })).toHaveCount(completed.length);
    await expect(history.getByText(completed[0].complaint.slice(0, 40), { exact: false }).first()).toBeVisible();
    for (const d of hidden) await expect(history.getByText(d.complaint.slice(0, 40), { exact: false })).toHaveCount(0);
    await expect(history.getByText('Total', { exact: true })).toBeVisible();
    await expect(page.getByText('Draft', { exact: true })).toHaveCount(0);
    await expect(page.getByText('In Progress', { exact: true })).toHaveCount(0);
    monitor.expectClean('consultations');
  });

  test('booking form announces the chosen type, doctor, date, time and duration', async ({ browser }) => {
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

    const form = page.locator('div')
      .filter({ has: page.getByText('Date & Time', { exact: true }) })
      .filter({ has: page.getByText('Select Date', { exact: true }) }).last();
    await pickDate(form, 40);
    await pressed(form.getByRole('button', { name: futureDay(40).label, exact: true }));
    await pickTime(form, 15, 30);
    await expect(form.getByRole('radiogroup', { name: 'AM or PM' }).getByRole('radio', { name: 'PM', exact: true })).toBeChecked();
    await expect(form.getByRole('radiogroup', { name: 'AM or PM' }).getByRole('radio', { name: 'AM', exact: true })).not.toBeChecked();
    await expect(form.getByRole('radiogroup', { name: 'Hour' }).getByRole('radio', { name: '3', exact: true })).toBeChecked();
    await expect(form.getByRole('radiogroup', { name: 'Minute' }).getByRole('radio', { name: ':30', exact: true })).toBeChecked();

    const duration = page.getByRole('radiogroup', { name: 'Duration' });
    await expect(duration.getByRole('radio', { name: '30 min', exact: true })).toBeChecked();
    await duration.getByRole('radio', { name: '45 min', exact: true }).click();
    await expect(duration.getByRole('radio', { name: '45 min', exact: true })).toBeChecked();
    await expect(duration.getByRole('radio', { name: '30 min', exact: true })).not.toBeChecked();
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
    const carer = await registerFixCaregiver();
    const up = await signedInPage(browser, THOMAS, { timezoneId: 'Africa/Harare' });
    pages.push(up);
    const { page, monitor } = up;
    const end = dateIn('Africa/Harare', 200);

    await visit(page, section('My Proxies (Caregivers)'));
    await page.getByRole('button', { name: 'Grant access to a caregiver' }).click();
    await page.getByLabel('Caregiver email').fill(carer.email);
    await page.getByLabel('Relationship').fill('Neighbour');
    await page.getByRole('radio', { name: 'View only' }).click();
    await page.getByPlaceholder('YYYY-MM-DD (leave blank for no end date)', { exact: true }).filter({ visible: true }).first().fill(end);
    const granted = page.waitForResponse(r => r.url().endsWith('/api/patients/me/caregiver-access') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Grant access', exact: true }).click();
    await expect(page.getByText('Use the format YYYY-MM-DD.')).toHaveCount(0);
    expect((await granted).status()).toBeLessThan(300);
    await expect(page.getByText('Access granted.')).toBeVisible();

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
    const pending = (await api.thomas.ok(api.thomas.get<any[]>(`/api/appointments/patient/${THOMAS.systemId}`), 'appointments'))
      .filter(a => a.type === 'VIDEO' && a.callStatus === 'NEEDS_TIME_CONFIRMATION'
        && (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') && new Date(a.scheduledAt).getTime() > Date.now());
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
