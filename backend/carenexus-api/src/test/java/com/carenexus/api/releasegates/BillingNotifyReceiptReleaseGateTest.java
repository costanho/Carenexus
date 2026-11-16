package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.invoice.InvoiceFixtures;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.job.InvoiceOverdueSweeper;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.notify.BillingNotificationListener;
import com.carenexus.api.billing.notify.BillingNotifyFixtures;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate of be-billing-notify-receipt, end to end through the real invoice endpoints and facade: a doctor
 * sends an invoice (one "New invoice" per circle member), the overdue sweeper marks it OVERDUE (one HIGH "Invoice
 * overdue", nothing on a second run), the patient pays it in full (no "Payment received" for the payer, one for the
 * caregiver, one "Invoice paid" for the issuing doctor), the notifications reach GET /api/notifications with their
 * billing links, the receipt is a printable "Receipt" for the circle and refused to everyone else, and a caregiver
 * without canViewBilling never receives anything.
 * <p>
 * The payment itself is stored and announced the way be-billing-payments does it (payment row, facade
 * applyPayment, PaymentRecordedEvent in one transaction), because that slice is built in parallel.
 */
class BillingNotifyReceiptReleaseGateTest extends CommittedIntegrationTest {

    private static final String INVOICES = "/api/billing/invoices";

    @Autowired
    private InvoiceOverdueSweeper sweeper;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InsuranceClaimRepository claimRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private InvoiceFixtures visits;
    private BillingNotifyFixtures fixtures;

    @BeforeEach
    void setUpCircle() {
        visits = new InvoiceFixtures(jdbc);
        fixtures = new BillingNotifyFixtures(invoiceRepository, paymentRepository, claimRepository,
                userSettingsRepository, jdbc);
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanManageBilling(true));
    }

    @AfterEach
    void restoreFees() {
        visits.restoreDefaultFees();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int count(User user, String kind) {
        return fixtures.notificationsOfKind(user, kind).size();
    }

    @Test
    void invoiceLifecycleNotifiesOncePerKindAndPrintsTheReceipt() throws Exception {
        // ---- the doctor issues an invoice ----
        InvoiceFixtures.Visit visit = visits.completedVisit(patient, doctor, "VIDEO");
        JsonNode draft = ok(doPost(INVOICES, doctor, Map.of("consultationId", visit.consultationId())), 201);
        int invoiceId = draft.get("invoiceId").asInt();
        String invoiceNo = draft.get("invoiceNo").asText();
        JsonNode sent = ok(doPost(INVOICES + "/" + invoiceId + "/send", doctor, null), 200);
        assertEquals("SENT", sent.get("status").asText());
        BigDecimal total = new BigDecimal(sent.get("total").asText());

        for (User member : List.of(patient, caregiver)) {
            List<Map<String, Object>> rows = fixtures.notificationsOfKind(member,
                    BillingNotificationListener.KIND_INVOICE_ISSUED);
            assertEquals(1, rows.size(), "New invoice for " + member.getSystemId());
            assertEquals("New invoice", rows.get(0).get("title"));
            assertTrue(((String) rows.get(0).get("message")).startsWith("Invoice " + invoiceNo + " for KES "));
        }

        // ---- the due date passes: the sweeper marks it OVERDUE once ----
        jdbc.update("UPDATE invoices SET due_date = ? WHERE invoice_id = ?",
                BillingNotifyFixtures.clinicToday().minusDays(2), invoiceId);
        assertEquals(1, sweeper.sweepOnce());
        assertEquals(0, sweeper.sweepOnce());
        assertEquals("OVERDUE", ok(doGet(INVOICES + "/" + invoiceId, patient), 200).get("status").asText());
        for (User member : List.of(patient, caregiver)) {
            List<Map<String, Object>> rows = fixtures.notificationsOfKind(member,
                    BillingNotificationListener.KIND_INVOICE_OVERDUE);
            assertEquals(1, rows.size(), "Invoice overdue for " + member.getSystemId());
            assertEquals("HIGH", rows.get(0).get("priority"));
        }

        // ---- the patient pays the whole balance (as be-billing-payments records a completed payment) ----
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
            Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "MPESA", total, BigDecimal.ZERO,
                    "LOC-GATE-0001", "PR-GATE", BillingNotifyFixtures.clinicNow(), patient.getSystemId());
            invoiceService.applyPayment(invoiceId, total);
            eventPublisher.publishEvent(new PaymentRecordedEvent(payment.getPaymentId(), invoiceId,
                    patient.getSystemId(), patient.getSystemId(), total, "KES", "MPESA",
                    PaymentRecordedEvent.COMPLETED));
        });

        assertEquals(0, count(patient, BillingNotificationListener.KIND_PAYMENT_RECEIVED), "payer is skipped");
        assertEquals(1, count(caregiver, BillingNotificationListener.KIND_PAYMENT_RECEIVED));
        assertEquals(1, count(doctor, BillingNotificationListener.KIND_INVOICE_PAID));
        assertEquals(0, count(patient, BillingNotificationListener.KIND_INVOICE_PAID));

        // ---- nobody outside the circle got anything ----
        for (User outsider : List.of(otherCaregiver, otherPatient, otherDoctor, admin)) {
            assertTrue(fixtures.paymentNotifications(outsider).isEmpty(), "nothing for " + outsider.getSystemId());
        }
        assertEquals(1, fixtures.paymentNotifications(doctor).size());

        // ---- the caregiver's notification list shows the three rows with proxy billing links ----
        JsonNode list = ok(doGet("/api/notifications?type=PAYMENT&size=50", caregiver), 200);
        assertEquals(3, list.get("content").size(), list.toString());
        for (JsonNode row : list.get("content")) {
            assertEquals("PAYMENT", row.get("type").asText());
            assertEquals("BILLING", row.get("target").asText());
            assertEquals("/dashboard/proxy?section=billing&patient=" + patient.getSystemId() + "&id=" + invoiceId,
                    row.get("actionUrl").asText());
        }
        JsonNode doctorList = ok(doGet("/api/notifications?type=PAYMENT", doctor), 200);
        assertEquals("/dashboard/doctor?section=billing&id=" + invoiceId,
                doctorList.get("content").get(0).get("actionUrl").asText());

        // ---- the printable receipt ----
        for (User reader : List.of(patient, caregiver, doctor, admin)) {
            MvcResult receipt = doGet(INVOICES + "/" + invoiceId + "/receipt", reader);
            assertEquals(200, receipt.getResponse().getStatus());
            assertEquals("attachment; filename=\"receipt-" + invoiceNo + ".html\"",
                    receipt.getResponse().getHeader("Content-Disposition"));
            String html = new String(receipt.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
            assertTrue(html.contains("<h1 id=\"doc-title\">Receipt</h1>"));
            assertTrue(html.contains("<span id=\"invoice-status\">Paid</span>"));
            assertTrue(html.contains("<td>M-PESA</td><td>LOC-GATE-0001</td>"));
            assertFalse(html.contains("<script"));
        }
        for (User refused : List.of(otherCaregiver, otherPatient, otherDoctor)) {
            assertEquals(403, doGet(INVOICES + "/" + invoiceId + "/receipt", refused).getResponse().getStatus());
        }
    }

    @Test
    void billingToggleOffSilencesTheWholeLifecycleForThatRecipient() throws Exception {
        fixtures.billingNotifications(caregiver, false);
        fixtures.billingNotifications(doctor, false);

        InvoiceFixtures.Visit visit = visits.completedVisit(patient, doctor, "IN_PERSON");
        int invoiceId = ok(doPost(INVOICES, doctor, Map.of("consultationId", visit.consultationId())), 201)
                .get("invoiceId").asInt();
        BigDecimal total = new BigDecimal(ok(doPost(INVOICES + "/" + invoiceId + "/send", doctor, null), 200)
                .get("total").asText());
        jdbc.update("UPDATE invoices SET due_date = ? WHERE invoice_id = ?",
                BillingNotifyFixtures.clinicToday().minusDays(1), invoiceId);
        assertEquals(1, sweeper.sweepOnce());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Invoice invoice = invoiceRepository.findById(invoiceId).orElseThrow();
            Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "CASH", total, BigDecimal.ZERO,
                    null, "RCPT-1", BillingNotifyFixtures.clinicNow(), admin.getSystemId());
            invoiceService.applyPayment(invoiceId, total);
            eventPublisher.publishEvent(new PaymentRecordedEvent(payment.getPaymentId(), invoiceId,
                    patient.getSystemId(), admin.getSystemId(), total, "KES", "CASH",
                    PaymentRecordedEvent.COMPLETED));
        });

        assertTrue(fixtures.paymentNotifications(caregiver).isEmpty());
        assertTrue(fixtures.paymentNotifications(doctor).isEmpty());
        assertEquals(1, count(patient, BillingNotificationListener.KIND_INVOICE_ISSUED));
        assertEquals(1, count(patient, BillingNotificationListener.KIND_INVOICE_OVERDUE));
        assertEquals(1, count(patient, BillingNotificationListener.KIND_PAYMENT_RECEIVED));
    }
}
