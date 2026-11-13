import { expect, test } from '@playwright/test';
import type { RegisteredUser } from './support/accounts';
import { Api, listLabResults, listPrescriptions } from './support/api';
import { JAMES, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiLogin, clinicDayPlus, doctorSlots, openAt, throwawayPatient,
} from './flows/clinical-helpers';

// Flow: the doctor's patient history leads to the patient's own lists.
// Dr. Banda has a prescription and a lab result for Thomas and, as a control, for another patient of his (a
// throwaway patient who booked a visit with him). From Patients > Thomas > History the doctor opens "View yours"
// (prescriptions) and "View all" (lab results); each list opens filtered by the `patient` URL param, shows Thomas's
// item and not the other patient's.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): appointments (reason "e2e…", throwaway patient),
// prescriptions (special instructions "e2e…", cancelled in afterAll so Thomas's newest active prescriptions are the
// seed's again), medication_schedule_plans and medication_schedules (of those
// prescriptions: no scheduleTimes are sent, so the server derives dose times from the frequency when it can),
// lab_results (test name "e2e…"), notifications (booking, new prescription and new lab result notices about those
// rows, for Thomas, his caregivers such as Sarah, the throwaway patient and Dr. Banda), outbound_messages (their
// fan-out), idempotency_keys, audit_log, users/patients/legal_acceptances (throwaway patient e2e-…@carenexus.test).

test.describe('flow: doctor history to filtered lists', () => {
  const tag = runTag();
  const short = tag.slice(-4);
  const thomasMedication = `Losartan ${short}`;
  const otherMedication = `Valsartan ${short}`;
  const thomasTest = `${tag} Lipid panel`;
  const otherTest = `${tag} Lipid panel, other patient`;
  const pages: MonitoredPage[] = [];
  /** The prescriptions beforeAll issued, cancelled in afterAll. */
  const prescriptionIds: number[] = [];
  let james: Api;
  let other: RegisteredUser;
  let otherApi: Api;

  test.beforeAll(async () => {
    james = await Api.login(JAMES);
    other = await throwawayPatient();
    otherApi = await apiLogin(other);

    // The other patient books a visit with Dr. Banda, which gives the doctor a care relationship with them.
    let booked = false;
    for (let days = 25; days < 45 && !booked; days++) {
      const free = (await doctorSlots(otherApi, JAMES.systemId, clinicDayPlus(days))).filter(s => s.available);
      for (const slot of free.slice(0, 3)) {
        const r = await otherApi.post('/api/appointments', {
          doctorSystemId: JAMES.systemId, scheduledAt: slot.start, durationMinutes: 30, type: 'IN_PERSON',
          reasonForVisit: `${tag} history control visit`,
        });
        if (r.status === 201 || r.status === 200) { booked = true; break; }
      }
    }
    expect(booked, 'the control patient booked a visit with Dr. Banda').toBe(true);

    for (const [patientSystemId, medicationName, testName] of [
      [THOMAS.systemId, thomasMedication, thomasTest],
      [other.systemId, otherMedication, otherTest],
    ] as const) {
      const rx = await james.ok(james.postIdempotent('/api/prescriptions', {
        patientSystemId, medicationName, dosage: '50 mg', frequency: 'As needed',
        specialInstructions: `${tag} history list check`,
      }), `prescription for ${patientSystemId}`);
      prescriptionIds.push(rx.prescriptionId);
      await james.ok(james.postIdempotent('/api/lab-results', {
        patientSystemId, testName, testType: 'BLOOD', status: 'COMPLETED', resultValue: '4.8', unit: 'mmol/L',
      }), `lab result for ${patientSystemId}`);
    }
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    // Cancel the prescriptions (DELETE sets CANCELLED), so later specs see Thomas's seed active prescriptions.
    for (const id of prescriptionIds) {
      const r = await james?.del(`/api/prescriptions/${id}`).catch(() => undefined);
      if (r && r.status !== 204) console.warn(`flow-doctor-history: cancelling prescription ${id} answered ${r.status}`);
    }
    await Promise.all([james?.dispose(), otherApi?.dispose()]);
  });

  test('History opens the patient\'s prescriptions and lab results filtered by the patient param', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;
    const openHistory = async () => {
      await openAt(page, 'doctor', 'patients', 'Patients', { patient: THOMAS.systemId, tab: 'history' });
      await expect(page.getByRole('tab', { name: 'History', exact: true })).toHaveAttribute('aria-selected', 'true');
    };

    await test.step('History lists the newest items from every doctor', async () => {
      await openHistory();
      await expect(page.getByRole('heading', { name: `${thomasMedication} 50 mg`, exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: thomasTest, exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: `${otherMedication} 50 mg`, exact: true })).toHaveCount(0);
    });

    await test.step('"View yours" opens the prescriptions filtered to Thomas', async () => {
      await page.getByRole('button', { name: 'View yours: the prescriptions you wrote for Thomas Dube', exact: true }).click();
      await page.waitForURL(u => u.searchParams.get('section') === 'prescriptions' && u.searchParams.get('patient') === THOMAS.systemId);
      await expect(page.getByRole('main', { name: 'Prescriptions', exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Remove patient filter, Thomas Dube', exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: thomasMedication, exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: otherMedication, exact: true })).toHaveCount(0);
      monitor.expectClean('filtered prescriptions');
    });

    await test.step('"View all" opens the lab results filtered to Thomas', async () => {
      await openHistory();
      await page.getByRole('button', { name: 'View all lab results of Thomas Dube', exact: true }).click();
      await page.waitForURL(u => u.searchParams.get('section') === 'labs' && u.searchParams.get('patient') === THOMAS.systemId);
      await expect(page.getByRole('main', { name: 'Labs & Results', exact: true })).toBeVisible();
      await expect(page.getByText('Patient: Thomas Dube', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Remove patient filter', exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: thomasTest, exact: true }).first()).toBeVisible();
      await expect(page.getByRole('heading', { name: otherTest, exact: true })).toHaveCount(0);
      monitor.expectClean('filtered lab results');
    });

    await test.step('a reload keeps the filter', async () => {
      await page.reload();
      await page.waitForURL(u => u.searchParams.get('section') === 'labs' && u.searchParams.get('patient') === THOMAS.systemId);
      await expect(page.getByText('Patient: Thomas Dube', { exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: thomasTest, exact: true }).first()).toBeVisible();
    });

    await test.step('the server lists agree with the screens', async () => {
      const labs = await listLabResults(james, { patientSystemId: THOMAS.systemId });
      expect(labs.some(r => r.testName === thomasTest)).toBe(true);
      expect(labs.some(r => r.testName === otherTest)).toBe(false);
      const rx = await listPrescriptions(james, { patientSystemId: THOMAS.systemId, doctorSystemId: JAMES.systemId });
      expect(rx.some(p => p.medicationName === thomasMedication)).toBe(true);
      expect(rx.some(p => p.medicationName === otherMedication)).toBe(false);
    });
  });
});
