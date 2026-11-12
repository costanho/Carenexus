import { expect, type Page, test } from '@playwright/test';
import { Api, type Clients, disposeAll, loginAll } from './support/api';
import { JAMES, LINDA, SARAH, THOMAS } from './support/env';
import {
  DOCTOR_SECTIONS, type MonitoredPage, PATIENT_SECTIONS, PROXY_SECTIONS, type Section, apiIdle, expectNoArtifacts,
  signedInPage, visit,
} from './support/smoke';

// Smoke: clinical screens show the dev-seed data for Thomas Dube (PAT-00021) and Dr. James Banda
// (DOC-00002), with counts matching the API; screens without seed data show their empty state.

const find = (list: Section[], nav: string) => list.find(s => s.nav === nav)!;

/** The number shown on a stat card ("3 Total"). */
async function statValue(page: Page, label: string): Promise<number> {
  const number = page.getByText(/^\d+$/);
  const card = page.locator('div').filter({ has: page.getByText(label, { exact: true }) }).filter({ has: number }).last();
  await expect(card).toBeVisible();
  return Number(await card.getByText(/^\d+$/).first().innerText());
}

test.describe('smoke clinical records', () => {
  let api: Clients;
  let up: MonitoredPage | undefined;

  test.beforeAll(async () => { api = await loginAll(); });
  test.afterEach(async () => { await up?.context.close().catch(() => {}); up = undefined; });
  test.afterAll(async () => { await disposeAll(api); });

  test('patient: consultations, prescriptions, records, labs and imaging show seed data or empty states', async ({ browser }) => {
    up = await signedInPage(browser, THOMAS);
    const { page, monitor } = up;

    await test.step('consultation history', async () => {
      const consultations = await api.thomas.ok(api.thomas.get<any[]>(`/api/consultations/patient/${THOMAS.systemId}`), 'consultations');
      expect(consultations.length, 'seed V8 has consultations for Thomas').toBeGreaterThan(0);
      await visit(page, find(PATIENT_SECTIONS, 'Consultation History'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(consultations.length);
      await expect(page.getByText(/New diagnosis follow-up — hypertension and type 2 diabetes/)).toBeVisible();
      const completed = page.locator('div').filter({ has: page.getByText('Completed', { exact: true }) })
        // Match the toggle in both states: the card must still resolve after "Show full record" turns into "Hide".
        .filter({ has: page.getByText(/New diagnosis follow-up/) }).filter({ has: page.getByText(/^(Show full record ▼|Hide ▲)$/) }).last();
      await completed.getByText('Show full record ▼').click();
      await expect(completed.getByText('Diagnosis', { exact: true })).toBeVisible();
      await expect(completed.getByText('Hide ▲')).toBeVisible();
      await expectNoArtifacts(page, 'consultation history');
      monitor.expectClean('consultation history');
    });

    await test.step('prescriptions', async () => {
      const rx = await api.thomas.ok(api.thomas.get<any[]>(`/api/prescriptions/patient/${THOMAS.systemId}`), 'prescriptions');
      expect(rx.length).toBeGreaterThan(0);
      await visit(page, find(PATIENT_SECTIONS, 'Prescriptions'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(rx.length);
      for (const name of ['Amlodipine 5mg Tablets', 'Metformin 500mg Tablets', 'Atorvastatin 10mg Tablets']) {
        await expect(page.getByText(name, { exact: true }).first(), name).toBeVisible();
      }
      await page.getByText('Show details ▼').first().click();
      await expect(page.getByText('Hide ▲')).toBeVisible();
      await expectNoArtifacts(page, 'prescriptions');
      monitor.expectClean('prescriptions');
    });

    for (const [nav, path, empty] of [
      ['Medical Records', `/api/medical-records/patient/${THOMAS.systemId}`, 'No medical records found.'],
      ['Lab Results',     `/api/lab-results/patient/${THOMAS.systemId}`,     'No lab results found.'],
      ['Imaging Results', `/api/imaging-results/patient/${THOMAS.systemId}`, 'No imaging results found.'],
    ] as const) {
      await test.step(nav, async () => {
        const rows = await api.thomas.ok(api.thomas.get<any[]>(path), nav);
        await visit(page, find(PATIENT_SECTIONS, nav));
        await apiIdle(page);
        expect(await statValue(page, 'Total'), `${nav} total matches the API`).toBe(rows.length);
        if (rows.length === 0) await expect(page.getByText(empty)).toBeVisible(); // no seed data for Thomas
        else await expect(page.getByText(empty)).toHaveCount(0);
        await expectNoArtifacts(page, nav);
        monitor.expectClean(nav);
      });
    }
  });

  test('patient with seed records (Linda Moyo): medical records and lab results list every row', async ({ browser }) => {
    const linda = await Api.login(LINDA);
    try {
      up = await signedInPage(browser, LINDA);
      const { page, monitor } = up;
      for (const [nav, path, name] of [
        ['Medical Records', `/api/medical-records/patient/${LINDA.systemId}`, 'title'],
        ['Lab Results',     `/api/lab-results/patient/${LINDA.systemId}`,     'testName'],
      ] as const) {
        await test.step(nav, async () => {
          const rows = await linda.ok(linda.get<any[]>(path), nav);
          expect(rows.length, `${nav}: Linda has seed rows`).toBeGreaterThan(0);
          await visit(page, find(PATIENT_SECTIONS, nav));
          await apiIdle(page);
          expect(await statValue(page, 'Total'), `${nav} total matches the API`).toBe(rows.length);
          for (const r of rows) await expect(page.getByText(r[name], { exact: true }).first(), `${nav}: ${r[name]}`).toBeVisible();
          await expectNoArtifacts(page, `Linda ${nav}`);
          monitor.expectClean(`Linda ${nav}`);
        });
      }
    } finally {
      await linda.dispose();
    }
  });

  test('doctor: patients, consultations, records, prescriptions and labs show seed data', async ({ browser }) => {
    up = await signedInPage(browser, JAMES);
    const { page, monitor } = up;

    await test.step('patients', async () => {
      const patients = await api.james.ok(api.james.get<any[]>('/api/doctors/patients'), 'patients');
      await visit(page, find(DOCTOR_SECTIONS, 'Patients'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(patients.length);
      await expect(page.getByText('PAT-00021', { exact: true })).toBeVisible();
      await expect(page.getByText('thomas.dube@carenexus.com')).toBeVisible();
      await expectNoArtifacts(page, 'doctor patients');
      monitor.expectClean('patients');
    });

    await test.step('consultations', async () => {
      const list = await api.james.ok(api.james.get<any[]>(`/api/consultations/doctor/${JAMES.systemId}`), 'consultations');
      await visit(page, find(DOCTOR_SECTIONS, 'Consultations'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(list.length);
      await expect(page.getByText('Diabetes and hypertension medication tolerance review. Assess Metformin side effects at 6-week mark.')).toBeVisible();
      // Each consultation shows its appointment type (In Person / Video / Phone), never a placeholder.
      await expect.soft(page.getByText('Unknown', { exact: true }), 'consultation type badges resolved').toHaveCount(0);
      await expectNoArtifacts(page, 'doctor consultations');
      monitor.expectClean('consultations');
    });

    await test.step('medical records', async () => {
      const list = await api.james.ok(api.james.get<any[]>(`/api/medical-records/doctor/${JAMES.systemId}`), 'records');
      await visit(page, find(DOCTOR_SECTIONS, 'Medical Records'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(list.length);
      await expect(page.getByText('GP Consultation — Bacterial Pharyngitis')).toBeVisible();
      await expectNoArtifacts(page, 'doctor records');
      monitor.expectClean('medical records');
    });

    await test.step('prescriptions', async () => {
      const list = await api.james.ok(api.james.get<any[]>(`/api/prescriptions/doctor/${JAMES.systemId}`), 'prescriptions');
      await visit(page, find(DOCTOR_SECTIONS, 'Prescriptions'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(list.length);
      await expect(page.getByText('Amlodipine 5mg Tablets').first()).toBeVisible();
      await expectNoArtifacts(page, 'doctor prescriptions');
      monitor.expectClean('prescriptions');
    });

    await test.step('labs', async () => {
      const list = await api.james.ok(api.james.get<any[]>(`/api/lab-results/doctor/${JAMES.systemId}`), 'labs');
      await visit(page, find(DOCTOR_SECTIONS, 'Labs & Results'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(list.length);
      await expect(page.getByText('Full Blood Count (FBC)')).toBeVisible();
      await expectNoArtifacts(page, 'doctor labs');
      monitor.expectClean('labs');
    });
  });

  test('caregiver: medications, health summary, documents and appointments match the API', async ({ browser }) => {
    up = await signedInPage(browser, SARAH);
    const { page, monitor } = up;
    await visit(page, find(PROXY_SECTIONS, 'Medications'));
    await apiIdle(page);
    const thomasRx = page.locator('div').filter({ has: page.getByText('Amlodipine 5mg Tablets', { exact: true }) }).filter({ has: page.getByText('Thomas Dube', { exact: true }) }).last();
    await expect(thomasRx).toBeVisible();
    await expectNoArtifacts(page, 'caregiver medications');
    monitor.expectClean('medications');

    await visit(page, find(PROXY_SECTIONS, 'Health Summary'));
    await apiIdle(page);
    const card = page.locator('div').filter({ has: page.getByText('Thomas Dube', { exact: true }) }).filter({ has: page.getByText('Hypertension', { exact: true }) }).last();
    await expect(card).toBeVisible();
    await expect(card.getByText('Aspirin', { exact: true })).toBeVisible();
    await expectNoArtifacts(page, 'caregiver health summary');
    monitor.expectClean('health summary');

    // Documents & Results: every lab result of each loved one whose grant allows lab results.
    const deps = await api.sarah.ok(api.sarah.get<any[]>('/api/caregiver/dependents'), 'dependents');
    let labs: any[] = [];
    for (const d of deps.filter(x => x.canViewLabResults)) {
      labs = labs.concat(await api.sarah.ok(api.sarah.get<any[]>(`/api/lab-results/patient/${d.patientSystemId}`), `labs ${d.patientSystemId}`));
    }
    await visit(page, find(PROXY_SECTIONS, 'Documents & Results'));
    await apiIdle(page);
    expect(await statValue(page, 'Total'), 'documents total matches the API').toBe(labs.length);
    if (labs.length > 0) await expect(page.getByText(labs[0].testName, { exact: true }).first()).toBeVisible();
    await expectNoArtifacts(page, 'caregiver documents');
    monitor.expectClean('documents');

    // Appointments: every appointment of the loved ones (GET /api/caregiver/dependents/activity).
    const activity = await api.sarah.ok(api.sarah.get<any>('/api/caregiver/dependents/activity'), 'activity');
    const appts = (activity.appointments ?? []) as any[];
    expect(appts.length, 'Sarah\'s loved ones have appointments (seed)').toBeGreaterThan(0);
    await visit(page, find(PROXY_SECTIONS, 'Appointments'));
    await apiIdle(page);
    expect(await statValue(page, 'Total'), 'appointments total matches the API').toBe(appts.length);
    expect(await statValue(page, 'Upcoming'), 'upcoming count matches the API').toBe(appts.filter(a => a.status === 'SCHEDULED').length);
    await expect(page.getByText('Thomas Dube', { exact: true }).first()).toBeVisible();
    await expectNoArtifacts(page, 'caregiver appointments');
    monitor.expectClean('appointments');
  });
});
