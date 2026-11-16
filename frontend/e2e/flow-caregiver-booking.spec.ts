import { expect, type Locator, type Page, test } from '@playwright/test';
import { registerAccount, type RegisteredUser } from './support/accounts';
import { Api, listAppointments } from './support/api';
import { JAMES, runTag } from './support/env';
import { escapeRe, openSectionAt } from './support/ui';
import {
  calendarDayName, cancelAppointments, closeAll, firstFreeSlot, flowPage, type FlowPage, grantCaregiver, marked,
  uniqueSurname, waitForNotifications,
} from './flows/account-helpers';

// Caregiver booking: a caregiver whose grant includes scheduling books an appointment with Dr. Banda for the
// person they care for through the booking form; the patient sees it on their own list and gets one
// "Appointment booked" notification. A caregiver with view-only access sees the appointment but has no booking
// button, and the API refuses a booking from them with 403.
//
// Tables written: users, patients, caregivers, user_settings, legal_acceptances, refresh_tokens, dependent_access
// (two grants), consent_records, appointments, idempotency_keys (the form sends an Idempotency-Key),
// notifications, outbound_messages, audit_log. The appointment's reason starts with the run tag and every account
// is a throwaway e2e-…@carenexus.test, so scripts/dev-reset-e2e-data.sh removes all of it; the spec also cancels
// the appointment itself.

test.describe.configure({ mode: 'serial' });

/** Picks a day in the booking calendar inside `scope`, moving forward month by month as needed. */
async function pickDay(scope: Locator, isoDate: string): Promise<void> {
  const day = scope.getByRole('button', { name: calendarDayName(isoDate), exact: true });
  for (let i = 0; i < 4 && !(await day.isVisible()); i++) {
    await scope.getByRole('button', { name: 'Next month' }).click();
  }
  await day.click();
  await expect(day).toHaveAttribute('aria-pressed', 'true');
}

/** The booking form: the element that holds both the "Date & Time" card and the final Book Appointment button. */
function bookingForm(page: Page): Locator {
  return page.locator('div')
    .filter({ hasText: 'Date & Time' })
    .filter({ has: page.getByRole('button', { name: 'Book Appointment', exact: true }) })
    .last();
}

test.describe('flow: caregiver books for a dependent', () => {
  let patient: RegisteredUser;
  let scheduler: RegisteredUser;
  let viewer: RegisteredUser;
  let patientApi: Api;
  const booked: number[] = [];
  const pages: FlowPage[] = [];

  test.beforeAll(async () => {
    patient = await registerAccount({ role: 'patient', lastName: uniqueSurname('Booked') });
    scheduler = await registerAccount({ role: 'proxy', lastName: uniqueSurname('Scheduler') });
    viewer = await registerAccount({ role: 'proxy', lastName: uniqueSurname('Viewer') });
    patientApi = await Api.login(patient);
    await grantCaregiver(patientApi, scheduler.email, 'CUSTOM', { canViewAppointments: true, canScheduleAppointments: true });
    await grantCaregiver(patientApi, viewer.email, 'VIEW_ONLY', { canViewAppointments: true });
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await cancelAppointments(patientApi, booked);
    await patientApi?.dispose();
  });

  test('a caregiver with scheduling books for the patient, who sees it and is notified once', async ({ browser }) => {
    const reason = marked(runTag(), 'check-up booked by caregiver');
    const patientName = `${patient.firstName} ${patient.lastName}`;
    const schedulerApi = await Api.login(scheduler);
    let appointmentId = 0;
    try {
      const slot = await firstFreeSlot(schedulerApi, JAMES.systemId, { fromDays: 3 });
      const c = await flowPage(browser, scheduler);
      pages.push(c);

      await test.step('the caregiver fills in the booking form for the patient', async () => {
        await openSectionAt(c.page, 'proxy', 'appointments', { mode: 'book', patient: patient.systemId }, 'Appointments');
        await expect(c.page.getByRole('heading', { name: 'Book an Appointment' })).toBeVisible();
        const who = c.page.getByRole('radiogroup', { name: 'Book for' }).getByRole('radio', { name: new RegExp(`^${escapeRe(patientName)}`) });
        if ((await who.getAttribute('aria-checked')) !== 'true') await who.click();
        await expect(who).toHaveAttribute('aria-checked', 'true');

        const form = bookingForm(c.page);
        await form.getByRole('group', { name: 'Appointment type' }).getByRole('button', { name: /^In Person\b/ }).click();
        await form.getByLabel('Search doctors', { exact: true }).fill('Banda');
        await form.getByRole('button', { name: /^Dr\. James Banda\b/ }).click();
        const thirty = form.getByRole('group', { name: 'Duration' }).getByRole('button', { name: '30 min', exact: true });
        if (await thirty.isVisible()) await thirty.click();
        await pickDay(form, slot.date);
        await form.getByRole('button', { name: slot.time, exact: true }).click();
        // In-person visits ask for a clinic location once the clinic has facilities; wait for that list first.
        await expect(form.getByText('Loading clinic locations…')).toHaveCount(0, { timeout: 20_000 });
        const location = form.getByLabel('Clinic location', { exact: true });
        if (await location.isVisible()) await location.selectOption({ index: 1 });
        await form.getByLabel('Reason for visit', { exact: true }).fill(reason);

        const created = c.page.waitForResponse(r => r.url().endsWith('/api/appointments') && r.request().method() === 'POST');
        await form.getByRole('button', { name: 'Book Appointment', exact: true }).click();
        const response = await created;
        expect(response.status()).toBe(201);
        const body = await response.json();
        expect(body.patientSystemId).toBe(patient.systemId);
        expect(body.doctorSystemId).toBe(JAMES.systemId);
        expect(String(body.scheduledAt).slice(0, 16)).toBe(slot.start.slice(0, 16));
        appointmentId = body.appointmentId as number;
        booked.push(appointmentId);
        await expect(c.page.getByText(new RegExp(`^Appointment booked for ${escapeRe(patientName)} with `))).toBeVisible();
      });

      await test.step('the patient has the appointment and exactly one "Appointment booked" notification', async () => {
        const mine = await listAppointments(patientApi, { patientSystemId: patient.systemId });
        expect(mine.find(a => a.appointmentId === appointmentId)?.reasonForVisit).toBe(reason);
        const about = (n: any) => n.type === 'APPOINTMENT' && n.resourceId === appointmentId && n.title === 'Appointment booked';
        await waitForNotifications(patientApi, about, 'appointment booked by the caregiver');
        // Give a duplicate a moment to show up, then count.
        await new Promise(r => setTimeout(r, 1_500));
        const rows = await waitForNotifications(patientApi, about, 'appointment booked by the caregiver');
        expect(rows, 'one notification for the patient').toHaveLength(1);
        expect(rows[0].actionUrl).toBe(`/dashboard/patient?section=my-appointments&id=${appointmentId}`);

        const p = await flowPage(browser, patient);
        pages.push(p);
        await openSectionAt(p.page, 'patient', 'my-appointments', { id: appointmentId }, 'My Appointments');
        await expect(p.page.getByText(reason, { exact: true }).first()).toBeVisible();
      });
    } finally {
      await schedulerApi.dispose();
    }
  });

  test('a view-only caregiver sees no booking button and the API answers 403', async ({ browser }) => {
    const patientName = `${patient.firstName} ${patient.lastName}`;
    const v = await flowPage(browser, viewer);
    pages.push(v);

    await openSectionAt(v.page, 'proxy', 'patient-profile', { patient: patient.systemId }, 'Patient Profile');
    await expect(v.page.getByRole('button', { name: `View appointments, ${patientName}` })).toBeVisible();
    await expect(v.page.getByRole('button', { name: `Book appointment for ${patientName}` })).toHaveCount(0);

    await openSectionAt(v.page, 'proxy', 'appointments', { patient: patient.systemId }, 'Appointments');
    await expect(v.page.getByRole('button', { name: 'Book appointment, unavailable: no loved one\'s access allows scheduling' }))
      .toBeDisabled();
    if (booked.length > 0) {
      const reasons = await listAppointments(patientApi, { patientSystemId: patient.systemId });
      const reason = reasons.find(a => a.appointmentId === booked[0])?.reasonForVisit as string;
      await expect(v.page.getByText(reason, { exact: true }).first(), 'view access still shows the appointment').toBeVisible();
    }

    const viewerApi = await Api.login(viewer);
    try {
      const slot = await firstFreeSlot(viewerApi, JAMES.systemId, { fromDays: 5 });
      const refused = await viewerApi.post('/api/appointments', {
        patientSystemId: patient.systemId, doctorSystemId: JAMES.systemId, facilityId: null, scheduledAt: slot.start,
        durationMinutes: 30, type: 'IN_PERSON', reasonForVisit: marked(runTag(), 'not allowed'),
      });
      expect(refused.status, `view-only booking refused: ${refused.body?.message ?? ''}`).toBe(403);
      if (refused.status < 300 && refused.body?.appointmentId) booked.push(refused.body.appointmentId);
    } finally {
      await viewerApi.dispose();
    }
  });
});
