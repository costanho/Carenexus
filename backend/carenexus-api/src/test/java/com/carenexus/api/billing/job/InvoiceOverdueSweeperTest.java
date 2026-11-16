package com.carenexus.api.billing.job;

import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.notify.BillingNotificationListener;
import com.carenexus.api.billing.notify.BillingNotifyFixtures;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InvoiceOverdueSweeper: SENT and PARTIALLY_PAID invoices whose due date plus the grace days has passed become
 * OVERDUE exactly once through InvoiceService.markOverdue (UPDATE / INVOICE audit row, status event, one HIGH
 * "Invoice overdue" notification per circle member); paid, cancelled, refunded, draft, already overdue, undated and
 * not yet due invoices are left alone; a second run changes nothing; another instance holding the job lock makes the
 * run a no-op.
 * <p>
 * The sweeper under test gets a fixed clock at the current instant in the clinic zone (InvoiceService re-checks the
 * due date against the application clock, so the test clock must not run ahead of it).
 */
class InvoiceOverdueSweeperTest extends CommittedIntegrationTest {

    @Autowired
    private InvoiceOverdueSweeper sweeper;

    @Autowired
    private JobSupport jobSupport;

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
    private PlatformTransactionManager transactionManager;

    private BillingNotifyFixtures fixtures;
    private LocalDate today;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new BillingNotifyFixtures(invoiceRepository, paymentRepository, claimRepository,
                userSettingsRepository, jdbc);
        today = BillingNotifyFixtures.clinicToday();
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanManageBilling(true));
    }

    private InvoiceOverdueSweeper sweeperWithGrace(int graceDays) {
        BillingProperties properties = new BillingProperties();
        properties.setOverdueGraceDays(graceDays);
        Clock fixed = Clock.fixed(Instant.now(), BillingNotifyFixtures.CLINIC_ZONE);
        return new InvoiceOverdueSweeper(jobSupport, invoiceRepository, invoiceService, properties, fixed);
    }

    private String status(Invoice invoice) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE invoice_id = ?", String.class,
                invoice.getInvoiceId());
    }

    private long updateAuditRows(Invoice invoice) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' "
                + "AND resource_type = 'INVOICE' AND resource_id = ?", Long.class, invoice.getInvoiceId());
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> overdueRows(com.carenexus.api.auth.model.User user) {
        return fixtures.notificationsOfKind(user, BillingNotificationListener.KIND_INVOICE_OVERDUE);
    }

    @Test
    void movesDueOpenInvoicesOnceAndLeavesEverythingElseAlone() {
        Invoice sentPastDue = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(3));
        Invoice partlyPaidPastDue = fixtures.invoice(patient, doctor, Invoice.STATUS_PARTIALLY_PAID,
                today.minusDays(1), new BigDecimal("900.00"));
        Invoice dueToday = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today);
        Invoice dueTomorrow = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.plusDays(1));
        Invoice noDueDate = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, null);
        Invoice paid = fixtures.invoice(patient, doctor, Invoice.STATUS_PAID, today.minusDays(10),
                new BigDecimal("2900.00"));
        Invoice cancelled = fixtures.invoice(patient, doctor, Invoice.STATUS_CANCELLED, today.minusDays(10));
        Invoice refunded = fixtures.invoice(patient, doctor, Invoice.STATUS_REFUNDED, today.minusDays(10));
        Invoice draft = fixtures.invoice(patient, doctor, Invoice.STATUS_DRAFT, today.minusDays(10));
        Invoice alreadyOverdue = fixtures.invoice(patient, doctor, Invoice.STATUS_OVERDUE, today.minusDays(10));

        assertEquals(2, sweeper.sweepOnce());

        assertEquals(Invoice.STATUS_OVERDUE, status(sentPastDue));
        assertEquals(Invoice.STATUS_OVERDUE, status(partlyPaidPastDue));
        assertEquals(Invoice.STATUS_SENT, status(dueToday));
        assertEquals(Invoice.STATUS_SENT, status(dueTomorrow));
        assertEquals(Invoice.STATUS_SENT, status(noDueDate));
        assertEquals(Invoice.STATUS_PAID, status(paid));
        assertEquals(Invoice.STATUS_CANCELLED, status(cancelled));
        assertEquals(Invoice.STATUS_REFUNDED, status(refunded));
        assertEquals(Invoice.STATUS_DRAFT, status(draft));
        assertEquals(Invoice.STATUS_OVERDUE, status(alreadyOverdue));
        assertEquals(1, updateAuditRows(sentPastDue));
        assertEquals(1, updateAuditRows(partlyPaidPastDue));
        assertEquals(0, updateAuditRows(dueToday));
        assertEquals(0, updateAuditRows(paid));

        // One HIGH notification per moved invoice for the patient and the caregiver with BILLING.
        for (var user : List.of(patient, caregiver)) {
            List<Map<String, Object>> rows = overdueRows(user);
            assertEquals(2, rows.size(), "overdue rows of " + user.getSystemId());
            assertTrue(rows.stream().allMatch(row -> "HIGH".equals(row.get("priority"))));
            assertEquals(List.of(sentPastDue.getInvoiceId(), partlyPaidPastDue.getInvoiceId()),
                    rows.stream().map(row -> row.get("resource_id")).sorted().toList());
        }
        assertEquals("Invoice " + partlyPaidPastDue.getInvoiceNo() + " is overdue. KES 2,000.00 is still to be paid.",
                overdueRows(patient).stream()
                        .filter(row -> partlyPaidPastDue.getInvoiceId().equals(row.get("resource_id")))
                        .findFirst().orElseThrow().get("message"));
        for (var user : List.of(otherCaregiver, doctor, admin)) {
            assertTrue(overdueRows(user).isEmpty(), "no overdue rows for " + user.getSystemId());
        }

        // A second run changes and notifies nothing.
        assertEquals(0, sweeper.sweepOnce());
        assertEquals(1, updateAuditRows(sentPastDue));
        assertEquals(2, overdueRows(patient).size());
        assertEquals(2, overdueRows(caregiver).size());
    }

    @Test
    void anInvoiceThatReturnsToOverdueAfterAPartPaymentIsNotNotifiedAgain() {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(2));
        assertEquals(1, sweeper.sweepOnce());

        // A part payment moves the invoice back to PARTIALLY_PAID; the next sweep moves it to OVERDUE again.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                invoiceService.applyPayment(invoice.getInvoiceId(), new BigDecimal("500.00")));
        assertEquals(Invoice.STATUS_PARTIALLY_PAID, status(invoice));

        assertEquals(1, sweeper.sweepOnce());
        assertEquals(Invoice.STATUS_OVERDUE, status(invoice));
        assertEquals(1, overdueRows(patient).size());
        assertEquals(1, overdueRows(caregiver).size());
    }

    @Test
    void graceDaysDelayTheMoveAndANegativeGraceCountsAsZero() {
        Invoice threeDaysLate = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(3));
        Invoice sixDaysLate = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(6));
        Invoice fiveDaysLate = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(5));

        // due_date < today - 5: only the invoice due six days ago.
        assertEquals(1, sweeperWithGrace(5).sweepOnce());
        assertEquals(Invoice.STATUS_OVERDUE, status(sixDaysLate));
        assertEquals(Invoice.STATUS_SENT, status(fiveDaysLate));
        assertEquals(Invoice.STATUS_SENT, status(threeDaysLate));

        assertEquals(2, sweeperWithGrace(-4).sweepOnce());
        assertEquals(Invoice.STATUS_OVERDUE, status(fiveDaysLate));
        assertEquals(Invoice.STATUS_OVERDUE, status(threeDaysLate));
    }

    @Test
    void runIsSkippedWhileAnotherInstanceHoldsTheJobLock() {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(2));

        Integer moved = new TransactionTemplate(transactionManager).execute(tx -> {
            jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class,
                    InvoiceOverdueSweeper.JOB_NAME);
            return sweeper.sweepOnce();
        });

        assertEquals(0, moved);
        assertEquals(Invoice.STATUS_SENT, status(invoice));
        assertTrue(overdueRows(patient).isEmpty());
        assertEquals(1, sweeper.sweepOnce());
    }

    @Test
    void sweepsMoreThanOneBatch() {
        int count = InvoiceOverdueSweeper.BATCH_SIZE + 5;
        for (int i = 0; i < count; i++) {
            fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, today.minusDays(1 + (i % 20)));
        }

        assertEquals(count, sweeper.sweepOnce());
        Long open = jdbc.queryForObject("SELECT COUNT(*) FROM invoices WHERE status = 'SENT'", Long.class);
        assertEquals(0L, open);
        assertEquals(count, overdueRows(patient).size());
    }
}
