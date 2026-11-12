import { expect, type Locator, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api, listAppointments } from './support/api';
import { THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import { escapeRe, sectionMain } from './support/ui';
import {
  apiLogin, apiResponse, type BookingRules, bookingRules, clinicalDoctor, clinicDayPlus, doctorSlots, hhmm,
  isoWeekday, longDateLabel, openAt, throwawayPatient,
} from './flows/clinical-helpers';

// Flow: booking against a doctor's availability.
// An approved throwaway doctor sets weekly hours (Monday to Friday, three hours) and a time-off range on one day.
// Another throwaway patient takes one slot. Thomas then books a free phone slot in the web booking form: only the
// doctor's hours are offered, the taken slot and the time off are shown as not available, and the phone number he
// enters is the one the doctor will call. The server rules for phone appointments (a number is required when none
// is on file; other types never keep one) and for video appointments (a call room when video is available, 409
// VIDEO_UNAVAILABLE otherwise) are checked through the API.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): doctor_availability and doctor_time_off (throwaway doctor,
// time-off reason "e2e…"), appointments (throwaway doctor and patient, reasons "e2e…"), call_sessions (video
// appointment, when video is available), notifications (booking and cancellation notices about those appointments),
// outbound_messages (their fan-out), idempotency_keys, users/patients/doctors (throwaway accounts e2e-…@carenexus.test),
// legal_acceptances (throwaway accounts), audit_log.

const SLOT = 30;
const PHONE_IN_FORM = '+263 77 123 4567';
const PHONE_BY_API = '+263 71 555 0100';

const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];

/** Months since year 0 of a calendar heading such as "November 2026". */
function monthIndex(label: string): number {
  const [name, year] = label.trim().split(/\s+/);
  return Number(year) * 12 + MONTH_NAMES.indexOf(name);
}

/**
 * Selects `date` in a booking calendar inside `scope`. Waits for the calendar to render, then moves forward one
 * month at a time (waiting for the heading to change after each step) until the heading names the month of `date`.
 */
async function pickCalendarDay(scope: Locator, date: string): Promise<void> {
  const [y, m] = date.split('-').map(Number);
  const target = `${MONTH_NAMES[m - 1]} ${y}`;
  const next = scope.getByRole('button', { name: 'Next month', exact: true });
  const heading = scope.getByText(new RegExp(`^(${MONTH_NAMES.join('|')}) \\d{4}$`));
  await expect(next, 'the calendar is shown').toBeVisible();
  await expect(heading).toBeVisible();

  for (let i = 0; i < 13; i++) {
    const shown = ((await heading.textContent()) ?? '').trim();
    if (shown === target) break;
    expect(monthIndex(shown), `the calendar shows ${shown}, before ${target}`).toBeLessThan(monthIndex(target));
    await next.click();
    await expect(heading).not.toHaveText(shown);
  }
  await expect(heading).toHaveText(target);

  const day = scope.getByRole('button', { name: longDateLabel(date), exact: true });
  await day.click();
  await expect(day).toHaveAttribute('aria-pressed', 'true');
}

test.describe('flow: booking from a doctor\'s availability', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const pages: MonitoredPage[] = [];
  let thomas: Api;
  let doctor: RegisteredUser;
  let doctorApi: Api;
  let other: RegisteredUser;
  let otherApi: Api;
  let rules: BookingRules;
  /** The clinic day everything is booked on (a weekday a few days ahead). */
  let day = '';
  /** First minute of the doctor's hours on that day. */
  let start = 0;

  const at = (offset: number) => `${day}T${hhmm(start + offset)}:00`;

  test.beforeAll(async () => {
    thomas = await Api.login(THOMAS);
    doctor = await clinicalDoctor();
    doctorApi = await apiLogin(doctor);
    other = await throwawayPatient();
    otherApi = await apiLogin(other);
    rules = await bookingRules(thomas);

    for (let d = 2; d <= Math.min(16, rules.maxAdvanceDays); d++) {
      const candidate = clinicDayPlus(d);
      if (isoWeekday(candidate) <= 5) { day = candidate; break; }
    }
    expect(day, 'a weekday inside the booking horizon').not.toBe('');
    start = Math.max(9, rules.openHour) * 60;
    expect(start + 180, 'three hours of clinic time from the first hour').toBeLessThanOrEqual(rules.closeHour * 60);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    if (doctorApi) {
      // Free Thomas's diary again: every active appointment with the throwaway doctor is cancelled.
      const active = await listAppointments(doctorApi, { doctorSystemId: doctor.systemId, status: 'SCHEDULED,RESCHEDULED' }).catch(() => []);
      for (const a of active) await doctorApi.del(`/api/appointments/${a.appointmentId}`);
    }
    await Promise.all([thomas?.dispose(), doctorApi?.dispose(), otherApi?.dispose()]);
  });

  test('the doctor\'s weekly hours and time off decide which times can be booked', async () => {
    await test.step('the doctor saves weekly hours and a time-off range', async () => {
      const blocks = [1, 2, 3, 4, 5].map(dayOfWeek => ({ dayOfWeek, startTime: hhmm(start), endTime: hhmm(start + 180), isActive: true }));
      const saved = await doctorApi.ok(doctorApi.put('/api/doctors/availability', { slotMinutes: SLOT, blocks }), 'save weekly hours');
      expect(saved.source).toBe('TEMPLATE');
      expect(saved.slotMinutes).toBe(SLOT);
      expect(saved.blocks).toHaveLength(5);

      const off = await doctorApi.ok(doctorApi.postIdempotent('/api/doctors/availability/time-off', {
        startsAt: at(120), endsAt: at(180), reason: `${tag} clinic meeting`,
      }), 'add time off');
      expect(off.startsAt).toBe(at(120));
      expect(off.conflictingAppointments).toBe(0);
    });

    await test.step('another patient takes one slot', async () => {
      const taken = await otherApi.ok(otherApi.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(60), durationMinutes: SLOT, type: 'IN_PERSON',
        reasonForVisit: `${tag} taken slot`, contactPhone: PHONE_BY_API,
      }), 'book the slot that is then taken');
      expect(taken.status).toBe('SCHEDULED');
      expect(taken.contactPhone, 'an in-person visit keeps no contact phone').toBeNull();
    });

    await test.step('the slots follow the hours, the booking and the time off', async () => {
      const slots = await doctorSlots(thomas, doctor.systemId, day);
      expect(slots.map(s => s.start), 'only start times inside the weekly hours').toEqual(
        [0, 30, 60, 90, 120, 150].map(at));
      expect(Object.fromEntries(slots.map(s => [s.start.slice(11, 16), s.available]))).toEqual({
        [hhmm(start)]: true, [hhmm(start + 30)]: true, [hhmm(start + 60)]: false,
        [hhmm(start + 90)]: true, [hhmm(start + 120)]: false, [hhmm(start + 150)]: false,
      });
    });

    await test.step('the server refuses the taken slot, the time off and times outside the hours', async () => {
      // Thomas, not the patient who holds the slot, so the refusal is the doctor's slot rule and not the rule that
      // a patient cannot have two appointments at once.
      const taken = await thomas.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(60), durationMinutes: SLOT, type: 'IN_PERSON', reasonForVisit: `${tag} taken by another`,
      });
      expect(taken.status, 'a slot another patient holds').toBe(409);
      expect(taken.body?.code ?? null, 'a plain conflict, no error code').toBeNull();
      expect(taken.body?.message).toBe('This time slot is no longer available. Please choose another time.');

      const away = await otherApi.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(120), durationMinutes: SLOT, type: 'IN_PERSON', reasonForVisit: `${tag} time off`,
      });
      expect(away.status, 'inside the time off').toBe(400);
      expect(away.body?.code).toBe('DOCTOR_UNAVAILABLE');

      if (start - 60 >= rules.openHour * 60) {
        const outside = await otherApi.post('/api/appointments', {
          doctorSystemId: doctor.systemId, scheduledAt: at(-60), durationMinutes: SLOT, type: 'IN_PERSON', reasonForVisit: `${tag} outside`,
        });
        expect(outside.status, 'inside clinic hours but outside the doctor\'s hours').toBe(400);
        expect(outside.body?.code).toBe('DOCTOR_UNAVAILABLE');
      }
    });
  });

  test('phone and video appointments follow their rules on the server', async () => {
    await test.step('a phone appointment needs a number when none is on file', async () => {
      const refused = await otherApi.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(90), durationMinutes: SLOT, type: 'PHONE', reasonForVisit: `${tag} phone`,
      });
      expect(refused.status).toBe(400);
      expect(refused.body?.code).toBe('CONTACT_PHONE_REQUIRED');

      const phone = await otherApi.ok(otherApi.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(90), durationMinutes: SLOT, type: 'PHONE',
        reasonForVisit: `${tag} phone`, contactPhone: PHONE_BY_API,
      }), 'book a phone appointment');
      expect(phone.type).toBe('PHONE');
      expect(phone.contactPhone).toBe(PHONE_BY_API);
      expect(phone.callStatus ?? null, 'no call room for a phone appointment').toBeNull();
      expect(phone.videoConsultationLink ?? null).toBeNull();
    });

    await test.step(rules.videoCallsAvailable ? 'a video appointment gets a call room' : 'video appointments are refused', async () => {
      const r = await otherApi.post('/api/appointments', {
        doctorSystemId: doctor.systemId, scheduledAt: at(0), durationMinutes: SLOT, type: 'VIDEO',
        reasonForVisit: `${tag} video`, contactPhone: PHONE_BY_API,
      });
      if (rules.videoCallsAvailable) {
        expect(r.status, `book video: ${r.body?.message ?? ''}`).toBe(201);
        expect(r.body.type).toBe('VIDEO');
        expect(r.body.callJoinRoute).toBe(`/dashboard/call/${r.body.appointmentId}`);
        expect(r.body.callStatus, 'a video appointment has a call session').not.toBeNull();
        expect(r.body.contactPhone, 'a video appointment keeps no contact phone').toBeNull();
        expect(r.body.videoConsultationLink ?? null).toBeNull();
      } else {
        expect(r.status).toBe(409);
        expect(r.body?.code).toBe('VIDEO_UNAVAILABLE');
      }
    });
  });

  test('the patient books a free phone slot in the booking form', async ({ browser }) => {
    const patient = await signedInPage(browser, THOMAS);
    pages.push(patient);
    const { page, monitor } = patient;
    const main = sectionMain(page, 'Book Appointment');
    const reason = `${tag} phone visit`;

    await test.step('the appointment types follow the clinic\'s video setting', async () => {
      await openAt(page, 'patient', 'book-appointment', 'Book Appointment');
      const video = main.getByRole('button', { name: 'Video Call, Consult from anywhere' });
      if (rules.videoCallsAvailable) {
        await expect(video).toBeVisible();
      } else {
        await expect(video).toHaveCount(0);
        await expect(main.getByText(/^Video appointments are not available at the moment\./)).toBeVisible();
      }
      await expect(main.getByLabel('Contact number')).toHaveCount(0);
      const phone = main.getByRole('button', { name: 'Phone Call, Audio consultation' });
      await phone.click();
      await expect(phone).toHaveAttribute('aria-pressed', 'true');
      await expect(main.getByLabel('Contact number')).toBeVisible();
    });

    await test.step('only the doctor\'s free times are offered', async () => {
      await main.getByLabel('Search doctors').fill(doctor.lastName);
      const card = main.getByRole('group', { name: 'Doctors' }).getByRole('button', { name: new RegExp(escapeRe(doctor.lastName)) });
      await card.click();
      await expect(card).toHaveAttribute('aria-pressed', 'true');

      await pickCalendarDay(main, day);
      await expect(main.getByRole('button', { name: hhmm(start + 30), exact: true })).toBeEnabled();
      await expect(main.getByRole('button', { name: `${hhmm(start + 60)}, not available`, exact: true })).toBeDisabled();
      await expect(main.getByRole('button', { name: `${hhmm(start + 120)}, not available`, exact: true })).toBeDisabled();
      await expect(main.getByRole('button', { name: `${hhmm(start + 150)}, not available`, exact: true })).toBeDisabled();
      // Nothing outside the weekly hours is offered at all.
      await expect(main.getByRole('button', { name: new RegExp(`^${escapeRe(hhmm(start - 30))}(,|$)`) })).toHaveCount(0);
      await expect(main.getByRole('button', { name: new RegExp(`^${escapeRe(hhmm(start + 180))}(,|$)`) })).toHaveCount(0);
    });

    await test.step('the patient books the phone slot with his own number', async () => {
      const slot = main.getByRole('button', { name: hhmm(start + 30), exact: true });
      await slot.click();
      await expect(slot).toHaveAttribute('aria-pressed', 'true');
      await main.getByLabel('Contact number').fill(PHONE_IN_FORM);
      await main.getByLabel('Reason for visit').fill(reason);
      await expect(main.getByText(`The doctor will call you on ${PHONE_IN_FORM}.`, { exact: true })).toBeVisible();

      const created = apiResponse(page, 'POST', /^\/api\/appointments$/);
      await main.getByRole('button', { name: 'Book Appointment', exact: true }).click();
      expect((await created).status(), 'booking accepted').toBe(201);
      await expect(page.getByRole('heading', { name: 'Appointment Booked!' })).toBeVisible();
      await expect(page.getByText(`The doctor will call you on ${PHONE_IN_FORM}.`, { exact: true })).toBeVisible();
      monitor.expectClean('patient booking');
    });

    await test.step('the server stored a phone appointment and the slot is no longer free', async () => {
      const mine = await listAppointments(thomas, { patientSystemId: THOMAS.systemId, doctorSystemId: doctor.systemId });
      const booked = mine.find(a => a.reasonForVisit === reason);
      expect(booked, 'the booked appointment').toBeTruthy();
      expect(booked.type).toBe('PHONE');
      expect(booked.status).toBe('SCHEDULED');
      expect(String(booked.scheduledAt).slice(0, 16)).toBe(at(30).slice(0, 16));
      expect(booked.contactPhone).toBe(PHONE_IN_FORM);
      expect(booked.callStatus ?? null).toBeNull();

      const slots = await doctorSlots(thomas, doctor.systemId, day);
      expect(slots.find(s => s.start === at(30))?.available, 'the booked slot').toBe(false);
    });
  });
});
