package com.carenexus.api.billing.notify;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Billing rows for the notification, sweeper and receipt tests, written straight through the repositories so the
 * tests do not depend on the payment and claim slices (built in parallel).
 */
public final class BillingNotifyFixtures {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    /** Two lines: 2,000.00 + 500.00, tax 400.00, total 2,900.00. */
    public static final String TWO_LINES = "[{\"description\":\"Video consultation\",\"quantity\":1,"
            + "\"unitPrice\":2000.00,\"amount\":2000.00},{\"description\":\"Medical report\",\"quantity\":1,"
            + "\"unitPrice\":500.00,\"amount\":500.00}]";

    private static final AtomicInteger NUMBER = new AtomicInteger(1000);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final InsuranceClaimRepository claims;
    private final UserSettingsRepository settings;
    private final JdbcTemplate jdbc;

    public BillingNotifyFixtures(InvoiceRepository invoices, PaymentRepository payments,
                                 InsuranceClaimRepository claims, UserSettingsRepository settings, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.payments = payments;
        this.claims = claims;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    /** Clinic calendar date today (the application clock's zone). */
    public static LocalDate clinicToday() {
        return LocalDate.now(Clock.system(CLINIC_ZONE));
    }

    public static LocalDateTime clinicNow() {
        return LocalDateTime.now(Clock.system(CLINIC_ZONE)).truncatedTo(ChronoUnit.SECONDS);
    }

    /** An issued invoice of 2,900.00 (nothing paid) in {@code status}, issued by {@code doctor}. */
    public Invoice invoice(User patient, User doctor, String status, LocalDate dueDate) {
        return invoice(patient, doctor, status, dueDate, BigDecimal.ZERO);
    }

    /** An invoice of 2,900.00 with {@code amountPaid}; DRAFT rows are never issued, PAID rows get a paid time. */
    public Invoice invoice(User patient, User doctor, String status, LocalDate dueDate, BigDecimal amountPaid) {
        LocalDateTime now = clinicNow();
        boolean issued = !Invoice.STATUS_DRAFT.equals(status);
        return invoices.saveAndFlush(Invoice.builder()
                .patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor == null ? null : doctor.getSystemId())
                .createdBySystemId(doctor == null ? null : doctor.getSystemId())
                .invoiceNo(String.format("INV-%d-%06d", now.getYear(), NUMBER.incrementAndGet()))
                .lineItems(TWO_LINES)
                .subtotal(new BigDecimal("2500.00"))
                .tax(new BigDecimal("400.00"))
                .discount(new BigDecimal("0.00"))
                .total(new BigDecimal("2900.00"))
                .amountPaid(BillingText.money(amountPaid))
                .currency("KES")
                .status(status)
                .dueDate(dueDate)
                .issuedAt(issued ? now.minusDays(14) : null)
                .paidAt(Invoice.STATUS_PAID.equals(status) ? now.minusHours(1) : null)
                .createdAt(now.minusDays(14))
                .updatedAt(now.minusDays(14))
                .build());
    }

    /** A settled payment of the invoice. */
    public Payment payment(Invoice invoice, String status, String method, BigDecimal amount, BigDecimal refunded,
                           String transactionId, String providerReference, LocalDateTime paidAt,
                           String initiatedBy) {
        return payments.saveAndFlush(Payment.builder()
                .patientSystemId(invoice.getPatientSystemId())
                .invoiceId(invoice.getInvoiceId())
                .amount(BillingText.money(amount))
                .currency("KES")
                .status(status)
                .method(method)
                .transactionId(transactionId)
                .providerReference(providerReference)
                .gateway("CASH".equals(method) || "BANK_TRANSFER".equals(method) ? "MANUAL" : "local")
                .initiatedBySystemId(initiatedBy)
                .refundedAmount(BillingText.money(refunded))
                .paidAt(paidAt)
                .createdAt(clinicNow())
                .updatedAt(clinicNow())
                .build());
    }

    /** A claim on the invoice, submitted by {@code submitter}. */
    public InsuranceClaim claim(Invoice invoice, User submitter, String status) {
        LocalDateTime now = clinicNow();
        return claims.saveAndFlush(InsuranceClaim.builder()
                .patientSystemId(invoice.getPatientSystemId())
                .invoiceId(invoice.getInvoiceId())
                .claimNo("CLM-" + NUMBER.incrementAndGet())
                .insuranceProvider("Test Insurer")
                .policyNumber("POL-123")
                .claimAmount(new BigDecimal("2900.00"))
                .documentIds(InsuranceClaim.NO_DOCUMENTS)
                .status(status)
                .submittedBySystemId(submitter == null ? null : submitter.getSystemId())
                .submittedAt(now)
                .updatedAt(now)
                .build());
    }

    /** Switches the user's billing notifications on or off (every other toggle keeps its default). */
    public void billingNotifications(User user, boolean enabled) {
        UserSettings row = settings.findBySystemId(user.getSystemId()).orElseGet(() -> {
            UserSettings created = new UserSettings();
            created.setSystemId(user.getSystemId());
            return created;
        });
        row.setBillingNotifications(enabled);
        settings.saveAndFlush(row);
    }

    /** Stored PAYMENT rows of a user, oldest first. */
    public List<Map<String, Object>> paymentNotifications(User user) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE system_id = ? AND type = 'PAYMENT' "
                + "ORDER BY notification_id", user.getSystemId());
    }

    /** Stored PAYMENT rows of a user whose dedupe key starts with {@code kind + ":"}. */
    public List<Map<String, Object>> notificationsOfKind(User user, String kind) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE system_id = ? AND type = 'PAYMENT' "
                + "AND dedupe_key LIKE ? ORDER BY notification_id", user.getSystemId(), kind + ":%");
    }
}
