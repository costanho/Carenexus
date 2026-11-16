import { expect, test } from '@playwright/test';
import { Api } from './support/api';
import { databaseReachable, setAppointmentStart } from './support/db';
import { JAMES, THOMAS, runTag } from './support/env';
import { type MonitoredPage, signedInPage } from './support/smoke';
import {
  apiResponse, cardOf, clinicNow, dialog, firstPage, hhmm, jamesBooksThomas, notificationsAbout, openAt,
} from './flows/clinical-helpers';

// Flow: a consultation from an appointment (gap G3 smoke).
// Dr. Banda books a visit for Thomas, the visit is moved to "started a few minutes ago" (local database, as in
// fix-doctor.spec.ts, because the API refuses past times), and the doctor opens the editor from the appointment
// card: Start consultation, chief complaint and diagnosis, Save draft, Complete consultation. The completion creates
// a medical record (title = chief complaint) and an invoice for the visit; once the doctor has issued the invoice
// the patient sees both, and the completed notes cannot be changed any more, in the editor or through the API.
//
// Tables written (for scripts/dev-reset-e2e-data.sh): appointments (reason "e2e…"), consultations (of that
// appointment, chief complaint "e2e…"), medical_records (created by the completion, title "e2e…"), invoices (auto
// invoice of the consultation), notifications (booking, consultation summary and invoice notices about those rows),
// outbound_messages (their fan-out), idempotency_keys, audit_log.

test.describe('flow: consultation from an appointment', () => {
  test.describe.configure({ mode: 'serial' });

  const tag = runTag();
  const reason = `${tag} consultation visit`;
  const complaint = `${tag} chest tightness on exertion`;
  const diagnosis = `${tag} stable angina, suspected`;
  const clinicalNotes = `${tag} resting ECG reviewed, no acute changes`;
  const pages: MonitoredPage[] = [];
  let james: Api;
  let thomas: Api;
  let appointmentId = 0;
  let consultationId = 0;
  let recordId = 0;

  test.beforeAll(async () => {
    expect(await databaseReachable(), 'the local database (support/db.ts) is needed to move the visit into the past').toBe(true);
    [james, thomas] = await Promise.all([Api.login(JAMES), Api.login(THOMAS)]);
    appointmentId = await jamesBooksThomas(james, reason);
    // Started up to ten minutes ago, and still today in clinic time (so it is listed under "Today").
    const now = clinicNow();
    const started = Math.max(0, Math.floor((now.minutes - 10) / 5) * 5);
    await setAppointmentStart(appointmentId, `${now.date}T${hhmm(started)}:00`);
  });

  test.afterAll(async () => {
    for (const p of pages) await p.context.close().catch(() => {});
    await Promise.all([james?.dispose(), thomas?.dispose()]);
  });

  test('the doctor starts, documents and completes the consultation from the appointment', async ({ browser }) => {
    const doctor = await signedInPage(browser, JAMES);
    pages.push(doctor);
    const { page, monitor } = doctor;

    await test.step('the appointment card opens the consultation editor', async () => {
      await openAt(page, 'doctor', 'appointments', 'Appointments', { tab: 'today', patient: THOMAS.systemId });
      const start = page.getByRole('button', { name: 'Start consultation with Thomas Dube', exact: true });
      const card = cardOf(page, reason, start);
      await card.getByRole('button', { name: 'Start consultation with Thomas Dube', exact: true }).click();
      await page.waitForURL(u => u.searchParams.get('section') === 'consultations'
        && u.searchParams.get('appointmentId') === String(appointmentId));
      await expect(page.getByText('No consultation yet', { exact: true })).toBeVisible();
    });

    await test.step('Start consultation opens it in progress', async () => {
      const created = apiResponse(page, 'POST', /^\/api\/consultations$/);
      await page.getByRole('button', { name: 'Start consultation', exact: true }).click();
      const res = await created;
      expect(res.status(), 'consultation created').toBe(201);
      const body = await res.json();
      consultationId = body.consultationId;
      expect(body.status).toBe('IN_PROGRESS');
      await expect(page.getByLabel('Chief complaint', { exact: true })).toBeEditable();
    });

    await test.step('the doctor fills the notes and saves a draft', async () => {
      await page.getByLabel('Chief complaint', { exact: true }).fill(complaint);
      await page.getByLabel('Diagnosis', { exact: true }).fill(diagnosis);
      await page.getByLabel('Clinical notes', { exact: true }).fill(clinicalNotes);
      const saved = apiResponse(page, 'PUT', new RegExp(`^/api/consultations/${consultationId}$`));
      await page.getByRole('button', { name: 'Save draft', exact: true }).click();
      expect((await saved).status(), 'draft saved').toBe(200);
      await expect(page.getByText(/^Saved at /)).toBeVisible();
    });

    await test.step('Complete consultation, confirmed in the dialog', async () => {
      await page.getByRole('button', { name: 'Complete consultation', exact: true }).click();
      const confirm = dialog(page, 'Complete this consultation?');
      await expect(confirm).toBeVisible();
      const completed = apiResponse(page, 'PATCH',
        new RegExp(`^/api/(consultations/${consultationId}/status|doctors/consultations/${consultationId}/mark-completed)$`));
      await confirm.getByRole('button', { name: 'Complete consultation', exact: true }).click();
      expect((await completed).status(), 'consultation completed').toBe(200);
      await expect(confirm).toHaveCount(0);
    });

    await test.step('the completed notes are read-only and link to the medical record', async () => {
      await expect(page.getByText(/^This consultation was completed on .*The notes can no longer be changed\.$/)).toBeVisible();
      await expect(page.getByText(complaint, { exact: true }).first()).toBeVisible();
      await expect(page.getByRole('button', { name: 'View medical record of Thomas Dube', exact: true })).toBeVisible();
      await expect(page.getByLabel('Chief complaint', { exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Save draft', exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Complete consultation', exact: true })).toHaveCount(0);
      monitor.expectClean('consultation editor');
    });
  });

  test('the server keeps the completed consultation unchanged', async () => {
    const c = await james.ok(james.get(`/api/consultations/${consultationId}`), 'read consultation');
    expect(c.status).toBe('COMPLETED');
    expect(c.chiefComplaint).toBe(complaint);
    expect(c.diagnosis).toBe(diagnosis);
    expect(c.medicalRecordId, 'the completion created a medical record').not.toBeNull();
    recordId = c.medicalRecordId;

    const edit = await james.put(`/api/consultations/${consultationId}`, { diagnosis: `${tag} changed afterwards` });
    expect(edit.status, 'editing completed notes').toBe(409);
    const reopen = await james.patch(`/api/consultations/${consultationId}/status?status=IN_PROGRESS`);
    expect(reopen.status, 'reopening a completed consultation').toBe(409);
    const after = await james.ok(james.get(`/api/consultations/${consultationId}`), 'read consultation again');
    expect(after.diagnosis).toBe(diagnosis);

    await expect.poll(async () => (await thomas.get(`/api/appointments/${appointmentId}`)).body?.status,
      { message: 'the appointment is completed with the consultation', timeout: 15_000 }).toBe('COMPLETED');
  });

  test('a medical record and an invoice appear for the patient', async ({ browser }) => {
    let invoiceNo = '';

    await test.step('the record is in the patient\'s records', async () => {
      const record = await thomas.ok(thomas.get(`/api/medical-records/${recordId}`), 'patient reads the record');
      expect(record.title).toBe(complaint);
      expect(record.source).toBe('CONSULTATION');
      expect(record.consultationId).toBe(consultationId);
      await expect.poll(async () => (await notificationsAbout(thomas, 'CONSULTATION', consultationId)).map(n => n.title),
        { message: 'consultation summary notification', timeout: 20_000 }).toEqual(['Consultation summary available']);
    });

    await test.step('the doctor issues the visit\'s invoice', async () => {
      let [invoice] = await firstPage(james, '/api/doctors/billing/invoices', { consultationId }, 5);
      if (!invoice) {
        // Auto-invoicing switched off on this stack: the doctor creates it, as the consultation's Invoice card does.
        invoice = await james.ok(james.postIdempotent('/api/billing/invoices', { consultationId }), 'create invoice');
      }
      expect(invoice.consultationId).toBe(consultationId);
      expect(invoice.patientSystemId).toBe(THOMAS.systemId);
      expect(Number(invoice.total), 'priced from the fee schedule').toBeGreaterThan(0);
      if (invoice.status === 'DRAFT') {
        invoice = await james.ok(james.post(`/api/billing/invoices/${invoice.invoiceId}/send`), 'send invoice');
      }
      expect(invoice.status).toBe('SENT');
      invoiceNo = invoice.invoiceNo;
      const mine = await firstPage(thomas, '/api/billing/invoices/me', { consultationId }, 5);
      expect(mine.map(i => i.invoiceNo), 'the patient sees the issued invoice').toEqual([invoiceNo]);
    });

    await test.step('the patient sees the record and the invoice on the web', async () => {
      const patient = await signedInPage(browser, THOMAS);
      pages.push(patient);
      const { page, monitor } = patient;
      await openAt(page, 'patient', 'medical-records', 'Medical Records');
      await expect(page.getByText(complaint, { exact: true }).first()).toBeVisible();
      await openAt(page, 'patient', 'billing', 'Billing & Payments');
      await expect(page.getByRole('button', { name: new RegExp(`^${invoiceNo}, `) })).toBeVisible();
      monitor.expectClean('patient record and invoice');
    });
  });
});
