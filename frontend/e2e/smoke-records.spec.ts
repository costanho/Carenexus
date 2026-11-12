import { expect, type Page, test } from '@playwright/test';
import { registerAccount } from './support/accounts';
import { Api, type Clients, disposeAll, loginAll, pagedTotal } from './support/api';
import { JAMES, LINDA, SARAH, THOMAS } from './support/env';
import {
  DOCTOR_SECTIONS, type MonitoredPage, PATIENT_SECTIONS, PROXY_SECTIONS, type Section, apiIdle, expectNoArtifacts,
  signedInPage, smokeTag, visit,
} from './support/smoke';
import { escapeRe, openSectionAt } from './support/ui';

// Smoke: clinical screens show the dev-seed data for Thomas Dube (PAT-00021) and Dr. James Banda (DOC-00002), with
// counts matching the canonical paged endpoints (CONTRACTS 3.2: `totalElements` of GET /api/<resource>?...), and
// screens without seed data show their empty state. Lists show 20 rows per page with "Load more".
//
// Tables written: medication_schedules (one of Thomas's doses of today is marked taken and then undone, so the seed
// state is restored) and the audit_log rows of every list read; users and legal_acceptances (a throwaway
// e2e-smoke-…@carenexus.test caregiver), dependent_access, consent_records and caregiver_activity_log (its lab-only
// grant from Thomas, removed in a finally block); refresh_tokens and audit_log for every sign-in.

const find = (list: Section[], nav: string) => list.find(s => s.nav === nav)!;

/** The number shown on a stat tile ("3" over "Total"), once it has loaded. */
async function statValue(page: Page, label: string): Promise<number> {
  const number = page.getByText(/^\d+$/);
  const card = page.locator('div').filter({ has: page.getByText(label, { exact: true }) }).filter({ has: number }).last();
  await expect(card).toBeVisible();
  return Number(await card.getByText(/^\d+$/).first().innerText());
}

/** Clinic wall-clock "YYYY-MM-DDTHH:mm" now (Africa/Harare). */
function clinicNow(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Harare', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date());
  const v = (t: string) => parts.find(p => p.type === t)!.value;
  return `${v('year')}-${v('month')}-${v('day')}T${v('hour')}:${v('minute')}`;
}

/** End of an appointment on the clinic wall clock ("YYYY-MM-DDTHH:mm"). */
function endOf(a: { scheduledAt: string; durationMinutes?: number | null }): string {
  const start = String(a.scheduledAt).slice(0, 16);
  const [date, time] = start.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [h, min] = time.split(':').map(Number);
  const end = new Date(Date.UTC(y, m - 1, d, h, min + (a.durationMinutes ?? 30)));
  return end.toISOString().slice(0, 16);
}

/** A loved one's grant shares lab results: an active grant with full access or the lab-results flag. */
function sharesLabs(d: any, now = new Date()): boolean {
  if (d.accessStatus && d.accessStatus !== 'ACTIVE') return false;
  if (d.isActive === false || d.revokedAt) return false;
  if (d.expiresAt && new Date(d.expiresAt).getTime() <= now.getTime()) return false;
  return d.accessLevel === 'FULL_ACCESS' || d.canViewLabResults === true;
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
      // Patients see finalised consultations only.
      const total = await pagedTotal(api.thomas, '/api/consultations', { patientSystemId: THOMAS.systemId, status: 'COMPLETED' });
      expect(total, 'seed V8 has completed consultations for Thomas').toBeGreaterThan(0);
      await visit(page, find(PATIENT_SECTIONS, 'Consultation History'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(total);
      await expect(page.getByText(/New diagnosis follow-up — hypertension and type 2 diabetes/).first()).toBeVisible();
      const toggle = page.getByRole('button', { name: /^(Show|Hide) full record/ });
      const completed = page.locator('div').filter({ has: page.getByText('Completed', { exact: true }) })
        // Match the toggle in both states: the card must still resolve after "Show full record" turns into "Hide".
        .filter({ has: page.getByText(/New diagnosis follow-up/) }).filter({ has: toggle }).last();
      await completed.getByRole('button', { name: /^Show full record/ }).click();
      await expect(completed.getByText('Diagnosis', { exact: true }).first()).toBeVisible();
      await expect(completed.getByRole('button', { name: /^Hide full record/ })).toBeVisible();
      await expectNoArtifacts(page, 'consultation history');
      monitor.expectClean('consultation history');
    });

    await test.step('prescriptions', async () => {
      // The total from the canonical paged endpoint; the "All" filter pill is named "All, <total>".
      const rx = await api.thomas.ok(api.thomas.page<any>('/api/prescriptions', { patientSystemId: THOMAS.systemId }, 0, 1), 'prescriptions');
      expect(rx.totalElements).toBeGreaterThan(0);
      await visit(page, find(PATIENT_SECTIONS, 'Prescriptions'));
      await apiIdle(page);
      await expect(page.getByRole('button', { name: `All, ${rx.totalElements}`, exact: true })).toBeVisible();
      for (const name of ['Amlodipine 5mg Tablets', 'Metformin 500mg Tablets', 'Atorvastatin 10mg Tablets']) {
        await expect(page.getByText(name, { exact: true }).first(), name).toBeVisible();
      }
      // Today's doses (gap G2): mark one taken and undo it, so the seed state is kept for later runs.
      await expect(page.getByRole('heading', { name: "Today's schedule" })).toBeVisible();
      const mark = page.getByRole('button', { name: /^Mark taken, / }).first();
      if (await mark.isVisible().catch(() => false)) {
        // The dose's own context ("Metformin 850mg at 08:00"), so Undo puts back this dose and not an earlier one
        // that is already Taken (seed data, a manual mark or an aborted run).
        const ctx = (await mark.getAttribute('aria-label'))!.replace(/^Mark taken, /, '');
        const marked = page.waitForResponse(r => /\/api\/medication-schedules\/\d+\/taken$/.test(r.url()) && r.request().method() === 'PATCH');
        await mark.click();
        expect((await marked).status(), 'dose marked taken').toBeLessThan(300);
        await expect(page.getByRole('status').filter({ hasText: new RegExp(`^${escapeRe(ctx)} is now Taken$`) })).toHaveCount(1);
        const undo = page.getByRole('button', { name: `Undo, ${ctx}`, exact: true });
        await expect(undo).toBeVisible();
        const undone = page.waitForResponse(r => /\/api\/medication-schedules\/\d+\/undo$/.test(r.url()) && r.request().method() === 'PATCH');
        await undo.click();
        expect((await undone).status(), 'dose put back').toBeLessThan(300);
        await expect(page.getByRole('status').filter({ hasText: new RegExp(`^${escapeRe(ctx)} is now Due$`) })).toHaveCount(1);
      } else {
        await expect(page.getByText(/^No doses scheduled for (today|this day)$/).first()).toBeVisible();
      }
      await expectNoArtifacts(page, 'prescriptions');
      monitor.expectClean('prescriptions');
    });

    for (const [nav, path, empty] of [
      ['Medical Records', '/api/medical-records', 'No medical records yet'],
      ['Lab Results',     '/api/lab-results',     'No lab results yet'],
      ['Imaging Results', '/api/imaging-results', 'No imaging results yet'],
    ] as const) {
      await test.step(nav, async () => {
        const total = await pagedTotal(api.thomas, path, { patientSystemId: THOMAS.systemId });
        await visit(page, find(PATIENT_SECTIONS, nav));
        await apiIdle(page);
        expect(await statValue(page, 'Total'), `${nav} total matches the API`).toBe(total);
        if (total === 0) await expect(page.getByText(empty).first()).toBeVisible(); // no seed data for Thomas
        else await expect(page.getByText(empty)).toHaveCount(0);
        await expectNoArtifacts(page, nav);
        monitor.expectClean(nav);
      });
    }
  });

  test('patient with seed records (Linda Moyo): medical records and lab results list the first page of rows', async ({ browser }) => {
    const linda = await Api.login(LINDA);
    try {
      up = await signedInPage(browser, LINDA);
      const { page, monitor } = up;
      for (const [nav, path, name] of [
        ['Medical Records', '/api/medical-records', 'title'],
        ['Lab Results',     '/api/lab-results',     'testName'],
      ] as const) {
        await test.step(nav, async () => {
          // The screen shows 20 rows per page, in the endpoint's default order.
          const first = await linda.ok(linda.page<any>(path, { patientSystemId: LINDA.systemId }, 0, 20), nav);
          expect(first.totalElements, `${nav}: Linda has seed rows`).toBeGreaterThan(0);
          await visit(page, find(PATIENT_SECTIONS, nav));
          await apiIdle(page);
          expect(await statValue(page, 'Total'), `${nav} total matches the API`).toBe(first.totalElements);
          for (const r of first.content) await expect(page.getByText(r[name], { exact: true }).first(), `${nav}: ${r[name]}`).toBeVisible();
          if (first.totalElements > first.content.length) {
            await expect(page.getByRole('button', { name: /^Load more/ }).first(), `${nav}: more rows behind "Load more"`).toBeVisible();
          }
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
      const search = await api.james.ok(api.james.get<any>('/api/doctors/patients/search?page=0&size=1'), 'patients');
      await visit(page, find(DOCTOR_SECTIONS, 'Patients'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(search.summary.total);
      // Only the first 20 patients by name are listed: search for Thomas before looking for him.
      await page.getByLabel('Search patients').fill('Thomas');
      await apiIdle(page);
      await expect(page.getByText('PAT-00021', { exact: true })).toBeVisible();
      await expect(page.getByText('thomas.dube@carenexus.com')).toBeVisible();
      await expectNoArtifacts(page, 'doctor patients');
      monitor.expectClean('patients');
    });

    await test.step('consultations', async () => {
      const stats = await api.james.ok(api.james.get<any>('/api/doctors/consultations/stats'), 'consultation stats');
      await visit(page, find(DOCTOR_SECTIONS, 'Consultations'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(stats.totalConsultations);
      await expect.soft(page.getByText('Diabetes and hypertension medication tolerance review. Assess Metformin side effects at 6-week mark.').first())
        .toBeVisible();
      // Each consultation shows its appointment type (In Person / Video / Phone), never a placeholder.
      await expect.soft(page.getByText('Unknown', { exact: true }), 'consultation type badges resolved').toHaveCount(0);
      await expectNoArtifacts(page, 'doctor consultations');
      monitor.expectClean('consultations');
    });

    await test.step('medical records', async () => {
      const total = await pagedTotal(api.james, '/api/medical-records', { doctorSystemId: JAMES.systemId });
      await visit(page, find(DOCTOR_SECTIONS, 'Medical Records'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(total);
      await page.getByPlaceholder('Search by title or ICD-10 code').fill('Pharyngitis');
      await apiIdle(page);
      await expect(page.getByText('GP Consultation — Bacterial Pharyngitis').first()).toBeVisible();
      await expectNoArtifacts(page, 'doctor records');
      monitor.expectClean('medical records');
    });

    await test.step('prescriptions', async () => {
      const total = await pagedTotal(api.james, '/api/prescriptions', { doctorSystemId: JAMES.systemId });
      expect(total).toBeGreaterThan(0);
      await visit(page, find(DOCTOR_SECTIONS, 'Prescriptions'));
      await apiIdle(page);
      // The "All" status tab carries the total.
      const all = page.getByRole('tablist', { name: 'Filter prescriptions by status' }).getByRole('tab').first();
      await expect(all).toContainText('All');
      await expect.soft(all, 'the All tab shows the total of the paged endpoint').toContainText(String(total));
      await expect(page.getByText('Amlodipine 5mg Tablets').first()).toBeVisible();
      await expectNoArtifacts(page, 'doctor prescriptions');
      monitor.expectClean('prescriptions');
    });

    await test.step('labs', async () => {
      // The doctor's own results: GET /api/lab-results without a patient filter.
      const total = await pagedTotal(api.james, '/api/lab-results');
      await visit(page, find(DOCTOR_SECTIONS, 'Labs & Results'));
      await apiIdle(page);
      expect(await statValue(page, 'Total')).toBe(total);
      await expect(page.getByText('Full Blood Count (FBC)').first()).toBeVisible();
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

    // Documents & Results (Lab results tab): the lab results of each loved one whose grant shares them by the
    // effective rule (full access counts even without the lab-results flag), from the paged endpoint.
    const deps = await api.sarah.ok(api.sarah.get<any[]>('/api/caregiver/dependents'), 'dependents');
    let labs = 0;
    let firstLab: string | null = null;
    for (const d of deps.filter(x => sharesLabs(x))) {
      const pageOfLabs = await api.sarah.ok(api.sarah.page<any>('/api/lab-results', { patientSystemId: d.patientSystemId }, 0, 1), `labs ${d.patientSystemId}`);
      labs += pageOfLabs.totalElements;
      firstLab ??= pageOfLabs.content[0]?.testName ?? null;
    }
    await visit(page, find(PROXY_SECTIONS, 'Documents & Results'));
    await apiIdle(page);
    expect(await statValue(page, 'Total'), 'documents total matches the API').toBe(labs);
    if (firstLab) await expect(page.getByText(firstLab, { exact: true }).first()).toBeVisible();
    await expectNoArtifacts(page, 'caregiver documents');
    monitor.expectClean('documents');

    // Appointments: the loved ones' appointments in the activity window (the last 90 days and everything ahead).
    const activity = await api.sarah.ok(api.sarah.get<any>('/api/caregiver/dependents/activity?page=0&size=100'), 'activity');
    const total = activity.appointments.totalElements as number;
    const rows = activity.appointments.content as any[];
    expect(total, 'Sarah\'s loved ones have appointments in the window (seed)').toBeGreaterThan(0);
    await visit(page, find(PROXY_SECTIONS, 'Appointments'));
    await apiIdle(page);
    expect(await statValue(page, 'Total'), 'appointments total matches the API').toBe(total);
    if (rows.length === total) {
      // Upcoming counts active appointments that have not ended yet (upcoming and in progress), clinic time.
      const now = clinicNow();
      const upcoming = rows.filter(a => (a.status === 'SCHEDULED' || a.status === 'RESCHEDULED') && endOf(a) > now).length;
      expect(await statValue(page, 'Upcoming'), 'upcoming count matches the API').toBe(upcoming);
    }
    await expect(page.getByRole('tablist', { name: 'Appointment filter' }).getByRole('tab', { name: /^All/ })).toBeVisible();
    await expect(page.getByText('Thomas Dube', { exact: true }).first()).toBeVisible();
    await expectNoArtifacts(page, 'caregiver appointments');
    monitor.expectClean('appointments');
  });

  test('caregiver with lab results but not imaging is told why the imaging tab is empty (gap G7)', async ({ browser }) => {
    const carer = await registerAccount({ role: 'proxy', tag: smokeTag(), firstName: 'Smoke', lastName: 'Carer' });
    await api.thomas.ok(api.thomas.post('/api/patients/me/caregiver-access', {
      caregiverEmail: carer.email, relationship: 'Neighbour', accessLevel: 'CUSTOM',
      canViewLabResults: true, canViewImaging: false,
    }), 'grant lab results only');
    try {
      up = await signedInPage(browser, carer);
      const { page, monitor } = up;
      await openSectionAt(page, 'proxy', 'documents-results', { tab: 'imaging', patient: THOMAS.systemId }, 'Documents & Results');
      await apiIdle(page);
      await expect(page.getByText(`Imaging results for ${THOMAS.name} are not shared with you.`).first()).toBeVisible();
      await openSectionAt(page, 'proxy', 'documents-results', { tab: 'labs', patient: THOMAS.systemId }, 'Documents & Results');
      await apiIdle(page);
      await expect(page.getByText(`Lab results for ${THOMAS.name} are not shared with you.`)).toHaveCount(0);
      monitor.expectClean('lab-only caregiver');
    } finally {
      const grants = await api.thomas.get<any[]>('/api/patients/me/caregiver-access');
      for (const g of (grants.body ?? []) as any[]) {
        if (g.caregiverEmail === carer.email) await api.thomas.del(`/api/patients/me/caregiver-access/${g.accessId}`);
      }
    }
  });
});
