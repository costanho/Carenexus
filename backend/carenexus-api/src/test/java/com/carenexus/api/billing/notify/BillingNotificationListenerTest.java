package com.carenexus.api.billing.notify;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.event.InsuranceClaimStatusChangedEvent;
import com.carenexus.api.common.event.InvoiceIssuedEvent;
import com.carenexus.api.common.event.InvoiceStatusChangedEvent;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BillingNotificationListener (contract be-billing-notify-receipt, section 7): for each consumed event, exactly one
 * PAYMENT notification per recipient of the patient circle with BILLING (patient, caregiver with canViewBilling,
 * caregiver with FULL_ACCESS), none for a caregiver without the flag (also with only canManageBilling), none for an
 * expired grant, none for a recipient with billingNotifications off, the actor rules, the dedupe keys, the exact
 * texts and the action links.
 * <p>
 * The events are published by the test inside a committed transaction, as the payment and claim slices (built in
 * parallel) will publish them.
 */
class BillingNotificationListenerTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InsuranceClaimRepository claimRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    private BillingNotifyFixtures fixtures;

    /** Caregiver with canViewBilling (and canManageBilling). */
    private User viewer;
    /** Caregiver whose grant has only canManageBilling: no BILLING view permission. */
    private User managerOnly;
    /** Caregiver with access level FULL_ACCESS. */
    private User fullAccess;
    /** Caregiver whose canViewBilling grant expired yesterday. */
    private User expired;

    @BeforeEach
    void setUpCircle() {
        fixtures = new BillingNotifyFixtures(invoiceRepository, paymentRepository, claimRepository,
                userSettingsRepository, jdbc);
        viewer = caregiver;
        managerOnly = otherCaregiver;
        fullAccess = createAndSaveUser(8, "CGV-00003", "full.access@example.com", "CAREGIVER");
        expired = createAndSaveUser(9, "CGV-00004", "expired@example.com", "CAREGIVER");
        grantAccess(viewer, patient, a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
        });
        grantAccess(managerOnly, patient, a -> a.setCanManageBilling(true));
        grantAccess(fullAccess, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(expired, patient, a -> {
            a.setCanViewBilling(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
    }

    // ==================== HELPERS ====================

    private void publish(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }

    private Invoice sentInvoice() {
        return fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, BillingNotifyFixtures.clinicToday().plusDays(14));
    }

    private List<Map<String, Object>> rows(User user, String kind) {
        return fixtures.notificationsOfKind(user, kind);
    }

    private Map<String, Object> single(User user, String kind) {
        List<Map<String, Object>> rows = rows(user, kind);
        assertEquals(1, rows.size(), "exactly one " + kind + " notification for " + user.getSystemId());
        return rows.get(0);
    }

    private void assertNone(User user, String kind) {
        assertEquals(0, rows(user, kind).size(), "no " + kind + " notification for " + user.getSystemId());
    }

    /** One row each for the patient circle with BILLING, none for anybody else. */
    private Map<User, Map<String, Object>> assertCircleOnly(String kind, User... skipped) {
        List<User> skippedList = List.of(skipped);
        Map<User, Map<String, Object>> result = new LinkedHashMap<>();
        for (User user : List.of(patient, viewer, fullAccess)) {
            if (skippedList.contains(user)) {
                assertNone(user, kind);
            } else {
                result.put(user, single(user, kind));
            }
        }
        for (User user : List.of(managerOnly, expired, doctor, otherDoctor, otherPatient, admin)) {
            assertNone(user, kind);
        }
        return result;
    }

    private static void assertCommonColumns(Map<String, Object> row, Invoice invoice, String priority) {
        assertEquals("PAYMENT", row.get("type"));
        assertEquals("BILLING", row.get("category"));
        assertEquals("BILLING", row.get("target"));
        assertEquals("INVOICE", row.get("resource_type"));
        assertEquals(invoice.getInvoiceId(), row.get("resource_id"));
        assertEquals(invoice.getPatientSystemId(), row.get("patient_system_id"));
        assertEquals(priority, row.get("priority"));
    }

    private String patientUrl(Invoice invoice) {
        return "/dashboard/patient?section=billing&id=" + invoice.getInvoiceId();
    }

    private String proxyUrl(Invoice invoice) {
        return "/dashboard/proxy?section=billing&patient=" + patient.getSystemId() + "&id=" + invoice.getInvoiceId();
    }

    private PaymentRecordedEvent paymentEvent(Payment payment, Invoice invoice, User initiatedBy, String amount,
                                              String status) {
        return new PaymentRecordedEvent(payment.getPaymentId(), invoice.getInvoiceId(), patient.getSystemId(),
                initiatedBy == null ? null : initiatedBy.getSystemId(), new BigDecimal(amount), "KES",
                payment.getMethod(), status);
    }

    // ==================== INVOICE ISSUED ====================

    @Test
    void invoiceIssuedNotifiesTheBillingCircleOnceWithLinksAndText() {
        Invoice invoice = sentInvoice();
        LocalDate due = LocalDate.of(2026, 11, 24);
        InvoiceIssuedEvent event = new InvoiceIssuedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(),
                patient.getSystemId(), doctor.getSystemId(), new BigDecimal("2900.00"), "KES", due);

        publish(event);
        publish(event);

        Map<User, Map<String, Object>> rows = assertCircleOnly(BillingNotificationListener.KIND_INVOICE_ISSUED);
        for (Map<String, Object> row : rows.values()) {
            assertCommonColumns(row, invoice, "NORMAL");
            assertEquals("New invoice", row.get("title"));
            assertEquals("Invoice " + invoice.getInvoiceNo() + " for KES 2,900.00 was issued. "
                    + "Payment is due by 24 Nov 2026.", row.get("message"));
            assertEquals("INVOICE_ISSUED:" + invoice.getInvoiceId(), row.get("dedupe_key"));
        }
        assertEquals(patientUrl(invoice), rows.get(patient).get("action_url"));
        assertEquals(proxyUrl(invoice), rows.get(viewer).get("action_url"));
        assertEquals(proxyUrl(invoice), rows.get(fullAccess).get("action_url"));
    }

    @Test
    void invoiceIssuedWithoutDueDateLeavesOutTheSecondSentence() {
        Invoice invoice = sentInvoice();
        publish(new InvoiceIssuedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                doctor.getSystemId(), new BigDecimal("1234567.5"), "USD", null));

        assertEquals("Invoice " + invoice.getInvoiceNo() + " for USD 1,234,567.50 was issued.",
                single(patient, BillingNotificationListener.KIND_INVOICE_ISSUED).get("message"));
    }

    @Test
    void billingToggleOffSilencesOnlyThatRecipient() {
        fixtures.billingNotifications(viewer, false);
        fixtures.billingNotifications(patient, true);
        Invoice invoice = sentInvoice();

        publish(new InvoiceIssuedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                doctor.getSystemId(), new BigDecimal("2900.00"), "KES", null));

        assertCircleOnly(BillingNotificationListener.KIND_INVOICE_ISSUED, viewer);
    }

    @Test
    void revokedGrantGetsNothing() {
        grantAccess(viewer, patient, a -> {
            a.setCanViewBilling(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(5));
        });
        Invoice invoice = sentInvoice();

        publish(new InvoiceIssuedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                doctor.getSystemId(), new BigDecimal("2900.00"), "KES", null));

        assertCircleOnly(BillingNotificationListener.KIND_INVOICE_ISSUED, viewer);
    }

    // ==================== PAYMENTS ====================

    @Test
    void completedPaymentSkipsThePayerAndNotifiesTheRestOfTheCircleOnce() {
        Invoice invoice = sentInvoice();
        Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "MPESA", new BigDecimal("900.00"),
                BigDecimal.ZERO, "LOC-1", "REF-1", BillingNotifyFixtures.clinicNow(), patient.getSystemId());
        PaymentRecordedEvent event = paymentEvent(payment, invoice, patient, "900.00", PaymentRecordedEvent.COMPLETED);

        publish(event);
        publish(event);

        Map<User, Map<String, Object>> rows = assertCircleOnly(BillingNotificationListener.KIND_PAYMENT_RECEIVED,
                patient);
        for (Map<String, Object> row : rows.values()) {
            assertCommonColumns(row, invoice, "NORMAL");
            assertEquals("Payment received", row.get("title"));
            assertEquals("A payment of KES 900.00 was received for invoice " + invoice.getInvoiceNo() + ".",
                    row.get("message"));
            assertEquals("PAYMENT_RECEIVED:" + payment.getPaymentId(), row.get("dedupe_key"));
        }
    }

    @Test
    void completedPaymentByCaregiverNotifiesThePatientButNotTheCaregiver() {
        Invoice invoice = sentInvoice();
        Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "CARD", new BigDecimal("2900.00"),
                BigDecimal.ZERO, "LOC-2", null, BillingNotifyFixtures.clinicNow(), viewer.getSystemId());

        publish(paymentEvent(payment, invoice, viewer, "2900.00", PaymentRecordedEvent.COMPLETED));

        assertCircleOnly(BillingNotificationListener.KIND_PAYMENT_RECEIVED, viewer);
    }

    @Test
    void manualPaymentByAdministratorNotifiesTheWholeCircleAndNotTheAdministrator() {
        Invoice invoice = sentInvoice();
        Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "CASH", new BigDecimal("500.00"),
                BigDecimal.ZERO, null, "RCPT-77", BillingNotifyFixtures.clinicNow(), admin.getSystemId());

        publish(paymentEvent(payment, invoice, admin, "500.00", PaymentRecordedEvent.COMPLETED));

        assertCircleOnly(BillingNotificationListener.KIND_PAYMENT_RECEIVED);
    }

    @Test
    void failedPaymentNotifiesTheWholeCircleIncludingThePayer() {
        Invoice invoice = sentInvoice();
        Payment payment = fixtures.payment(invoice, Payment.STATUS_FAILED, "MPESA", new BigDecimal("2900.00"),
                BigDecimal.ZERO, null, "REF-F", null, patient.getSystemId());
        PaymentRecordedEvent event = paymentEvent(payment, invoice, patient, "2900.00", PaymentRecordedEvent.FAILED);

        publish(event);
        publish(event);

        Map<User, Map<String, Object>> rows = assertCircleOnly(BillingNotificationListener.KIND_PAYMENT_FAILED);
        for (Map<String, Object> row : rows.values()) {
            assertEquals("Payment failed", row.get("title"));
            assertEquals("A payment of KES 2,900.00 for invoice " + invoice.getInvoiceNo() + " did not go through.",
                    row.get("message"));
            assertEquals("PAYMENT_FAILED:" + payment.getPaymentId(), row.get("dedupe_key"));
        }
    }

    @Test
    void eachPartialRefundNotifiesOnceAndARepeatedRefundEventNotifiesNothing() {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_PAID, BillingNotifyFixtures.clinicToday(),
                new BigDecimal("2900.00"));
        Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "CARD", new BigDecimal("2900.00"),
                new BigDecimal("1000.00"), "LOC-R", null, BillingNotifyFixtures.clinicNow(), patient.getSystemId());

        PaymentRecordedEvent first = paymentEvent(payment, invoice, admin, "1000.00", PaymentRecordedEvent.REFUNDED);
        publish(first);
        publish(first);
        jdbc.update("UPDATE payments SET refunded_amount = 1500.00 WHERE payment_id = ?", payment.getPaymentId());
        publish(paymentEvent(payment, invoice, admin, "500.00", PaymentRecordedEvent.REFUNDED));

        for (User user : List.of(patient, viewer, fullAccess)) {
            List<Map<String, Object>> rows = rows(user, BillingNotificationListener.KIND_REFUND_ISSUED);
            assertEquals(2, rows.size(), "two refunds for " + user.getSystemId());
            assertEquals("REFUND_ISSUED:" + payment.getPaymentId() + ":1000.00", rows.get(0).get("dedupe_key"));
            assertEquals("REFUND_ISSUED:" + payment.getPaymentId() + ":1500.00", rows.get(1).get("dedupe_key"));
            assertEquals("Refund issued", rows.get(0).get("title"));
            assertEquals("A refund of KES 1,000.00 was issued for invoice " + invoice.getInvoiceNo() + ".",
                    rows.get(0).get("message"));
            assertEquals("A refund of KES 500.00 was issued for invoice " + invoice.getInvoiceNo() + ".",
                    rows.get(1).get("message"));
        }
        for (User user : List.of(managerOnly, expired, admin, doctor)) {
            assertNone(user, BillingNotificationListener.KIND_REFUND_ISSUED);
        }
    }

    @Test
    void paymentEventsThatDoNotApplyStoreNothingAndNeverFail() {
        Invoice invoice = sentInvoice();
        Payment pending = fixtures.payment(invoice, Payment.STATUS_PENDING, "MPESA", new BigDecimal("100.00"),
                BigDecimal.ZERO, null, "REF-P", null, patient.getSystemId());

        publish(paymentEvent(pending, invoice, null, "100.00", "PENDING"));
        publish(new PaymentRecordedEvent(pending.getPaymentId(), null, patient.getSystemId(), null,
                new BigDecimal("100.00"), "KES", "MPESA", PaymentRecordedEvent.COMPLETED));
        assertDoesNotThrow(() -> publish(new PaymentRecordedEvent(pending.getPaymentId(), 999_999,
                patient.getSystemId(), null, new BigDecimal("100.00"), "KES", "MPESA",
                PaymentRecordedEvent.COMPLETED)));
        assertDoesNotThrow(() -> publish(new PaymentRecordedEvent(null, invoice.getInvoiceId(),
                patient.getSystemId(), null, new BigDecimal("100.00"), "KES", "MPESA",
                PaymentRecordedEvent.FAILED)));

        for (User user : List.of(patient, viewer, fullAccess)) {
            assertTrue(fixtures.paymentNotifications(user).isEmpty(), "nothing for " + user.getSystemId());
        }
    }

    // ==================== INVOICE STATUS ====================

    @Test
    void overdueNotifiesTheCircleOnceWithHighPriorityAndTheOpenBalance() {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_OVERDUE,
                BillingNotifyFixtures.clinicToday().minusDays(3), new BigDecimal("900.00"));

        publish(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                Invoice.STATUS_PARTIALLY_PAID, Invoice.STATUS_OVERDUE));
        // A later return to OVERDUE (after a part payment) notifies nobody again.
        publish(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                Invoice.STATUS_PARTIALLY_PAID, Invoice.STATUS_OVERDUE));

        Map<User, Map<String, Object>> rows = assertCircleOnly(BillingNotificationListener.KIND_INVOICE_OVERDUE);
        for (Map<String, Object> row : rows.values()) {
            assertCommonColumns(row, invoice, "HIGH");
            assertEquals("Invoice overdue", row.get("title"));
            assertEquals("Invoice " + invoice.getInvoiceNo() + " is overdue. KES 2,000.00 is still to be paid.",
                    row.get("message"));
            assertEquals("INVOICE_OVERDUE:" + invoice.getInvoiceId(), row.get("dedupe_key"));
        }
    }

    @Test
    void paidNotifiesOnlyTheIssuingDoctorOnce() {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_PAID, BillingNotifyFixtures.clinicToday(),
                new BigDecimal("2900.00"));
        InvoiceStatusChangedEvent event = new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(),
                patient.getSystemId(), Invoice.STATUS_SENT, Invoice.STATUS_PAID);

        publish(event);
        publish(event);
        publish(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                Invoice.STATUS_PARTIALLY_PAID, Invoice.STATUS_PAID));

        Map<String, Object> row = single(doctor, BillingNotificationListener.KIND_INVOICE_PAID);
        assertCommonColumns(row, invoice, "NORMAL");
        assertEquals("Invoice paid", row.get("title"));
        assertEquals("Invoice " + invoice.getInvoiceNo() + " for KES 2,900.00 has been paid in full.",
                row.get("message"));
        assertEquals("/dashboard/doctor?section=billing&id=" + invoice.getInvoiceId(), row.get("action_url"));
        for (User user : List.of(patient, viewer, fullAccess, managerOnly, otherDoctor, admin)) {
            assertNone(user, BillingNotificationListener.KIND_INVOICE_PAID);
        }
    }

    @Test
    void paidNotifiesNoDoctorWhoSwitchedBillingOff() {
        fixtures.billingNotifications(doctor, false);
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_PAID, BillingNotifyFixtures.clinicToday(),
                new BigDecimal("2900.00"));

        publish(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(), patient.getSystemId(),
                Invoice.STATUS_SENT, Invoice.STATUS_PAID));

        assertNone(doctor, BillingNotificationListener.KIND_INVOICE_PAID);
    }

    @Test
    void otherStatusChangesNotifyNobody() {
        Invoice invoice = sentInvoice();
        String[][] transitions = {
                {Invoice.STATUS_DRAFT, Invoice.STATUS_SENT},
                {Invoice.STATUS_SENT, Invoice.STATUS_PARTIALLY_PAID},
                {Invoice.STATUS_SENT, Invoice.STATUS_CANCELLED},
                {Invoice.STATUS_PAID, Invoice.STATUS_REFUNDED},
                {Invoice.STATUS_OVERDUE, Invoice.STATUS_OVERDUE}};
        for (String[] transition : transitions) {
            publish(new InvoiceStatusChangedEvent(invoice.getInvoiceId(), invoice.getInvoiceNo(),
                    patient.getSystemId(), transition[0], transition[1]));
        }
        for (User user : List.of(patient, viewer, fullAccess, doctor)) {
            assertTrue(fixtures.paymentNotifications(user).isEmpty(), "nothing for " + user.getSystemId());
        }
    }

    // ==================== INSURANCE CLAIMS ====================

    @Test
    void claimSubmissionSkipsTheSubmitterAndNotifiesTheRestOfTheCircle() {
        Invoice invoice = sentInvoice();
        InsuranceClaim claim = fixtures.claim(invoice, patient, InsuranceClaim.STATUS_SUBMITTED);

        publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), invoice.getInvoiceId(),
                patient.getSystemId(), null, InsuranceClaim.STATUS_SUBMITTED));

        Map<User, Map<String, Object>> rows = assertCircleOnly(BillingNotificationListener.KIND_CLAIM_STATUS, patient);
        for (Map<String, Object> row : rows.values()) {
            assertCommonColumns(row, invoice, "NORMAL");
            assertEquals("Insurance claim submitted", row.get("title"));
            assertEquals("An insurance claim was submitted for invoice " + invoice.getInvoiceNo() + ".",
                    row.get("message"));
            assertTrue(((String) row.get("dedupe_key")).matches("CLAIM_STATUS:" + claim.getClaimId()
                    + ":SUBMITTED:\\d+"), (String) row.get("dedupe_key"));
        }
    }

    @Test
    void everyClaimStatusNotifiesTheWholeCircleWithItsOwnText() {
        Invoice invoice = sentInvoice();
        InsuranceClaim claim = fixtures.claim(invoice, viewer, InsuranceClaim.STATUS_PENDING);
        Map<String, String[]> expected = new LinkedHashMap<>();
        String no = invoice.getInvoiceNo();
        expected.put(InsuranceClaim.STATUS_PENDING, new String[]{"Insurance claim under review",
                "The insurance claim for invoice " + no + " is under review."});
        expected.put(InsuranceClaim.STATUS_APPROVED, new String[]{"Insurance claim approved",
                "The insurance claim for invoice " + no + " was approved."});
        expected.put(InsuranceClaim.STATUS_PARTIAL, new String[]{"Insurance claim partly approved",
                "The insurance claim for invoice " + no
                        + " was partly approved. The remaining balance is still to be paid."});
        expected.put(InsuranceClaim.STATUS_REJECTED, new String[]{"Insurance claim rejected",
                "The insurance claim for invoice " + no + " was rejected."});
        expected.put(InsuranceClaim.STATUS_APPEALED, new String[]{"Insurance claim appealed",
                "The insurance claim for invoice " + no + " was appealed and will be reviewed again."});

        String previous = InsuranceClaim.STATUS_SUBMITTED;
        for (String status : expected.keySet()) {
            publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), invoice.getInvoiceId(),
                    patient.getSystemId(), previous, status));
            previous = status;
        }

        for (User user : List.of(patient, viewer, fullAccess)) {
            List<Map<String, Object>> rows = rows(user, BillingNotificationListener.KIND_CLAIM_STATUS);
            assertEquals(expected.size(), rows.size(), "claim rows for " + user.getSystemId());
            int i = 0;
            for (Map.Entry<String, String[]> entry : expected.entrySet()) {
                Map<String, Object> row = rows.get(i++);
                assertEquals(entry.getValue()[0], row.get("title"));
                assertEquals(entry.getValue()[1], row.get("message"));
                assertTrue(((String) row.get("dedupe_key")).startsWith("CLAIM_STATUS:" + claim.getClaimId() + ":"
                        + entry.getKey() + ":"));
            }
        }
        for (User user : List.of(managerOnly, expired, doctor, admin)) {
            assertNone(user, BillingNotificationListener.KIND_CLAIM_STATUS);
        }
    }

    @Test
    void claimEventsThatDoNotApplyStoreNothing() {
        Invoice invoice = sentInvoice();
        InsuranceClaim claim = fixtures.claim(invoice, patient, InsuranceClaim.STATUS_PENDING);

        publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), invoice.getInvoiceId(),
                patient.getSystemId(), InsuranceClaim.STATUS_PENDING, InsuranceClaim.STATUS_PENDING));
        publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), invoice.getInvoiceId(),
                patient.getSystemId(), InsuranceClaim.STATUS_PENDING, "UNKNOWN"));
        publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), null,
                patient.getSystemId(), InsuranceClaim.STATUS_PENDING, InsuranceClaim.STATUS_APPROVED));
        assertDoesNotThrow(() -> publish(new InsuranceClaimStatusChangedEvent(claim.getClaimId(), 999_999,
                patient.getSystemId(), InsuranceClaim.STATUS_PENDING, InsuranceClaim.STATUS_APPROVED)));

        for (User user : List.of(patient, viewer, fullAccess)) {
            assertTrue(fixtures.paymentNotifications(user).isEmpty(), "nothing for " + user.getSystemId());
        }
    }
}
