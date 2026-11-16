import { expect, test } from '@playwright/test';
import { loginAdmin, registerAccount, type RegisteredUser } from './support/accounts';
import { Api, withQuery } from './support/api';
import { ADMIN, JAMES, runTag } from './support/env';
import { openSectionAt } from './support/ui';
import {
  bookFirstFreeSlot, cancelAppointments, closeAll, flowPage, type FlowPage, grantCaregiver, marked, notificationsOf,
  uniqueSurname, waitForNotifications,
} from './flows/account-helpers';

// Billing: Dr. Banda invoices two visits of a throwaway patient. The patient opens the first invoice, pays it
// online through the local payment gateway (the development "Simulate success" control stands in for the
// provider), and the invoice becomes PAID with exactly one "Invoice paid" notification, to the doctor (the payer is
// not notified of their own payment). The patient submits an insurance claim for the second invoice, which an
// administrator reviews and approves in full. A caregiver whose grant has no billing permission sees the no-access
// state and the API refuses them.
//
// Tables written: users, patients, caregivers, user_settings, legal_acceptances, refresh_tokens, dependent_access,
// consent_records, appointments, invoices, payments, payment_events, insurance_claims, idempotency_keys,
// notifications, outbound_messages, audit_log. All of it belongs to throwaway accounts e2e-…@carenexus.test (line
// descriptions, the insurer name and the visit reasons also start with the run tag) and is removed by
// scripts/dev-reset-e2e-data.sh; the spec cancels its appointments itself.

test.describe.configure({ mode: 'serial' });

const PATIENT_BILLING = 'Billing & Payments';

interface Visit { appointmentId: number; invoice: any }

test.describe('flow: invoices, online payment and insurance claims', () => {
  const tag = runTag();
  let patient: RegisteredUser;
  let carer: RegisteredUser;
  let patientApi: Api;
  let james: Api;
  let admin: Api;
  const visits: Visit[] = [];
  const pages: FlowPage[] = [];

  /** Books a visit with Dr. Banda for the patient, and the doctor invoices and sends it. */
  async function invoicedVisit(what: string, unitPrice: number): Promise<Visit> {
    // The first free slot moves on by itself once an earlier visit has taken it.
    const { appointmentId } = await bookFirstFreeSlot(patientApi, patient.systemId, JAMES.systemId, marked(tag, `${what} visit`));
    const draft = await james.ok(james.postIdempotent('/api/billing/invoices', {
      appointmentId,
      lineItems: [{ description: marked(tag, what), quantity: 1, unitPrice }],
    }), `invoice ${what}`);
    expect(draft.status).toBe('DRAFT');
    const sent = await james.ok(james.post(`/api/billing/invoices/${draft.invoiceId}/send`), `send invoice ${draft.invoiceNo}`);
    expect(sent.status).toBe('SENT');
    return { appointmentId, invoice: sent };
  }

  test.beforeAll(async () => {
    patient = await registerAccount({ role: 'patient', lastName: uniqueSurname('Billed') });
    carer = await registerAccount({ role: 'proxy', lastName: uniqueSurname('Nobilling') });
    [patientApi, james, admin] = await Promise.all([Api.login(patient), Api.login(JAMES), loginAdmin()]);
    await grantCaregiver(patientApi, carer.email, 'VIEW_ONLY', { canViewAppointments: true, canViewBilling: false });
    visits.push(await invoicedVisit('consultation', 40));
    visits.push(await invoicedVisit('follow-up consultation', 25));
  });

  test.afterAll(async () => {
    await closeAll(pages);
    await cancelAppointments(patientApi, visits.map(v => v.appointmentId));
    await Promise.all([patientApi?.dispose(), james?.dispose(), admin?.dispose()]);
  });

  test('the patient pays an invoice through the local gateway: PAID, and one notification', async ({ browser }) => {
    const { invoice } = visits[0];
    const config = await patientApi.ok(patientApi.get('/api/billing/config'), 'billing config');
    expect(config.simulationEnabled, 'the local gateway with simulation is active (local or docker profile)').toBe(true);

    const p = await flowPage(browser, patient);
    pages.push(p);
    await openSectionAt(p.page, 'patient', 'billing', { id: invoice.invoiceId }, PATIENT_BILLING);
    await expect(p.page.getByText(invoice.invoiceNo).first()).toBeVisible();
    await expect(p.page.getByText(marked(tag, 'consultation'), { exact: true }).first(), 'the invoice line').toBeVisible();

    await p.page.getByRole('button', { name: new RegExp(`^Pay invoice ${invoice.invoiceNo}, `) }).click();
    const dialog = p.page.getByRole('dialog', { name: `Pay invoice ${invoice.invoiceNo}` });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel('Pay with', { exact: true }).selectOption({ label: 'New card' });

    const started = p.page.waitForResponse(r => r.url().endsWith(`/api/billing/invoices/${invoice.invoiceId}/payments`) && r.request().method() === 'POST');
    await dialog.getByRole('button', { name: 'Pay now' }).click();
    const startResponse = await started;
    expect(startResponse.status()).toBe(201);
    const payment = await startResponse.json();
    expect(payment).toMatchObject({ status: 'PENDING', method: 'CARD', nextAction: 'AWAIT_CONFIRMATION' });

    const settled = p.page.waitForResponse(r => r.url().endsWith(`/api/billing/payments/${payment.paymentId}/simulate`));
    await dialog.getByRole('button', { name: 'Simulate success' }).click();
    expect((await settled).status()).toBe(200);
    await expect(dialog.getByText(new RegExp(`^Payment received\\. .+ was paid onto invoice ${invoice.invoiceNo}\\.$`))).toBeVisible();

    await test.step('the invoice is PAID with nothing outstanding', async () => {
      const after = await patientApi.ok(patientApi.get(`/api/billing/invoices/${invoice.invoiceId}`), 'invoice after payment');
      expect(after.status).toBe('PAID');
      expect(Number(after.outstanding)).toBe(0);
      const payments = await patientApi.ok(patientApi.get(`/api/billing/invoices/${invoice.invoiceId}/payments`), 'payments');
      const rows = (Array.isArray(payments) ? payments : payments.content) as { paymentId: number; status: string }[];
      expect(rows.filter(r => r.status === 'COMPLETED').map(r => r.paymentId)).toEqual([payment.paymentId]);
      await p.page.reload();
      await expect(p.page.getByText('Paid', { exact: true }).first()).toBeVisible();
    });

    await test.step('exactly one notification: the doctor\'s "Invoice paid"; the payer gets none for their own payment', async () => {
      // Billing links always carry the invoice id (be-billing-notify-receipt 7.4), so rows are matched on it.
      const doctorLink = `/dashboard/doctor?section=billing&id=${invoice.invoiceId}`;
      const paidForThis = (n: any) => n.title === 'Invoice paid' && n.actionUrl === doctorLink;
      await waitForNotifications(james, paidForThis, `invoice ${invoice.invoiceNo} paid`);
      await new Promise(r => setTimeout(r, 1_500));
      const doctorRows = (await notificationsOf(james)).filter(paidForThis);
      expect(doctorRows).toHaveLength(1);
      expect(doctorRows[0].message).toContain(invoice.invoiceNo);
      const patientLink = `/dashboard/patient?section=billing&id=${invoice.invoiceId}`;
      const payerRows = (await notificationsOf(patientApi)).filter(n => n.title === 'Payment received' && n.actionUrl === patientLink);
      expect(payerRows, 'no "Payment received" for the payer').toHaveLength(0);
    });
  });

  test('the patient submits a claim and an administrator approves it in full', async ({ browser }) => {
    const { invoice } = visits[1];
    const insurer = marked(tag, 'Health Insurance');
    const p = await flowPage(browser, patient);
    pages.push(p);
    let claim: any;

    await test.step('the patient submits an insurance claim for the whole balance', async () => {
      await openSectionAt(p.page, 'patient', 'billing', { id: invoice.invoiceId }, PATIENT_BILLING);
      await p.page.getByRole('button', { name: `Submit an insurance claim for invoice ${invoice.invoiceNo}` }).click();
      const dialog = p.page.getByRole('dialog', { name: `Insurance claim for ${invoice.invoiceNo}` });
      await dialog.getByLabel('Insurer', { exact: true }).fill(insurer);
      await dialog.getByLabel('Policy number', { exact: true }).fill('E2E-POLICY-1');
      const res = p.page.waitForResponse(r => r.url().endsWith(`/api/billing/invoices/${invoice.invoiceId}/claims`) && r.request().method() === 'POST');
      await dialog.getByRole('button', { name: 'Submit claim' }).click();
      const response = await res;
      expect(response.status()).toBe(201);
      claim = await response.json();
      expect(claim.status).toBe('SUBMITTED');
      expect(Number(claim.claimAmount)).toBe(Number(invoice.outstanding));
      await expect(p.page.getByText(`Claim ${claim.claimNo} was submitted to ${insurer}. You can follow it under Insurance claims.`)).toBeVisible();
    });

    await test.step('the administrator starts the review and approves the claim in full', async () => {
      const a = await flowPage(browser, ADMIN);
      pages.push(a);
      await openSectionAt(a.page, 'admin', 'billing', { tab: 'claims', id: claim.claimId }, 'Billing');
      const panel = a.page.getByRole('dialog', { name: `Claim ${claim.claimNo}` });
      await expect(panel).toBeVisible();
      await expect(panel.getByText(insurer).first()).toBeVisible();
      const review = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/billing/claims/${claim.claimId}`) && r.request().method() === 'PATCH');
      await panel.getByRole('button', { name: 'Start review' }).click();
      expect((await review).status()).toBe(200);
      await panel.getByRole('button', { name: 'Approve in full' }).click();
      const confirm = a.page.getByRole('dialog', { name: 'Approve claim in full' });
      const decided = a.page.waitForResponse(r => r.url().endsWith(`/api/admin/billing/claims/${claim.claimId}`) && r.request().method() === 'PATCH');
      await confirm.getByRole('button', { name: 'Approve in full' }).click();
      expect((await decided).status()).toBe(200);
      // The notice shows in the open claim panel (the admin's view); the screen's copy of it sits behind the modal.
      await expect(panel.getByText(`Claim ${claim.claimNo} was approved in full.`)).toBeVisible();
    });

    await test.step('the claim is approved and the approved amount settled the invoice', async () => {
      const after = await patientApi.ok(patientApi.get(`/api/billing/claims/${claim.claimId}`), 'claim after decision');
      expect(after.status).toBe('APPROVED');
      expect(Number(after.approvedAmount)).toBe(Number(claim.claimAmount));
      const paid = await patientApi.ok(patientApi.get(`/api/billing/invoices/${invoice.invoiceId}`), 'invoice after claim');
      expect(paid.status).toBe('PAID');
      const patientLink = `/dashboard/patient?section=billing&id=${invoice.invoiceId}`;
      await waitForNotifications(patientApi, n => n.title === 'Insurance claim approved' && n.actionUrl === patientLink,
        `claim ${claim.claimNo} approved`);
    });
  });

  test('a caregiver without the billing permission sees the no-access state and the API refuses', async ({ browser }) => {
    const patientName = `${patient.firstName} ${patient.lastName}`;
    const c = await flowPage(browser, carer);
    pages.push(c);
    await openSectionAt(c.page, 'proxy', 'billing', { patient: patient.systemId }, PATIENT_BILLING);
    await expect(c.page.getByText(`You don't have permission to see billing for ${patientName}.`)).toBeVisible();
    await expect(c.page.getByText(visits[0].invoice.invoiceNo)).toHaveCount(0);

    const carerApi = await Api.login(carer);
    try {
      expect((await carerApi.get(withQuery('/api/billing/invoices', { patientSystemId: patient.systemId }))).status).toBe(403);
      expect((await carerApi.get(`/api/billing/invoices/${visits[1].invoice.invoiceId}`)).status).toBe(403);
      expect((await carerApi.post(`/api/billing/invoices/${visits[0].invoice.invoiceId}/payments`, { method: 'CARD' })).status).toBe(403);
    } finally {
      await carerApi.dispose();
    }
  });
});
