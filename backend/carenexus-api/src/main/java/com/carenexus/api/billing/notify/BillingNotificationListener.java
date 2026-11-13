package com.carenexus.api.billing.notify;

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
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the billing domain events into PAYMENT notifications (contract be-billing-notify-receipt, section 7).
 * <p>
 * Every method is a plain {@code @EventListener}: it runs synchronously inside the publisher's transaction, so the
 * notification rows (and the email, SMS and push rows derived from them) commit or roll back with the billing
 * change. A listener never throws for missing data; it stores nothing and logs one WARN line with ids only.
 * <p>
 * Every notification: type PAYMENT, category BILLING (switched off by the recipient's {@code billingNotifications}),
 * target BILLING, resource INVOICE with the invoice id, text limited to the invoice number, an amount and a due date.
 * <table>
 *   <caption>Kinds</caption>
 *   <tr><th>Kind (dedupe prefix)</th><th>Trigger</th><th>Recipients</th></tr>
 *   <tr><td>INVOICE_ISSUED</td><td>InvoiceIssuedEvent</td><td>patient circle (BILLING)</td></tr>
 *   <tr><td>PAYMENT_RECEIVED</td><td>PaymentRecordedEvent COMPLETED</td><td>patient circle except the payer</td></tr>
 *   <tr><td>PAYMENT_FAILED</td><td>PaymentRecordedEvent FAILED</td><td>patient circle, payer included</td></tr>
 *   <tr><td>REFUND_ISSUED</td><td>PaymentRecordedEvent REFUNDED</td><td>patient circle</td></tr>
 *   <tr><td>INVOICE_OVERDUE</td><td>InvoiceStatusChangedEvent to OVERDUE</td><td>patient circle, priority HIGH</td></tr>
 *   <tr><td>INVOICE_PAID</td><td>InvoiceStatusChangedEvent to PAID</td><td>the issuing doctor</td></tr>
 *   <tr><td>CLAIM_STATUS</td><td>InsuranceClaimStatusChangedEvent</td><td>patient circle (submission: except the
 *       submitter)</td></tr>
 * </table>
 */
@Slf4j
@Component
public class BillingNotificationListener {

    public static final String KIND_INVOICE_ISSUED = "INVOICE_ISSUED";
    public static final String KIND_PAYMENT_RECEIVED = "PAYMENT_RECEIVED";
    public static final String KIND_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String KIND_REFUND_ISSUED = "REFUND_ISSUED";
    public static final String KIND_INVOICE_OVERDUE = "INVOICE_OVERDUE";
    public static final String KIND_INVOICE_PAID = "INVOICE_PAID";
    public static final String KIND_CLAIM_STATUS = "CLAIM_STATUS";

    static final String RESOURCE_TYPE = "INVOICE";

    /** Title and message start of each claim status (the invoice number is appended to the message). */
    private static final Map<String, String[]> CLAIM_TEXTS = Map.of(
            InsuranceClaim.STATUS_SUBMITTED, new String[]{"Insurance claim submitted",
                    "An insurance claim was submitted for invoice %s."},
            InsuranceClaim.STATUS_PENDING, new String[]{"Insurance claim under review",
                    "The insurance claim for invoice %s is under review."},
            InsuranceClaim.STATUS_APPROVED, new String[]{"Insurance claim approved",
                    "The insurance claim for invoice %s was approved."},
            InsuranceClaim.STATUS_PARTIAL, new String[]{"Insurance claim partly approved",
                    "The insurance claim for invoice %s was partly approved. The remaining balance is still to be paid."},
            InsuranceClaim.STATUS_REJECTED, new String[]{"Insurance claim rejected",
                    "The insurance claim for invoice %s was rejected."},
            InsuranceClaim.STATUS_APPEALED, new String[]{"Insurance claim appealed",
                    "The insurance claim for invoice %s was appealed and will be reviewed again."});

    private final NotificationPublisher publisher;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InsuranceClaimRepository claimRepository;
    private final Clock clock;

    public BillingNotificationListener(NotificationPublisher publisher, InvoiceRepository invoiceRepository,
                                       PaymentRepository paymentRepository, InsuranceClaimRepository claimRepository,
                                       Clock clock) {
        this.publisher = publisher;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.claimRepository = claimRepository;
        this.clock = clock;
    }

    // =====================================================================================================
    // INVOICE ISSUED
    // =====================================================================================================

    /** DRAFT to SENT: "New invoice" to the patient circle. The event carries everything; no row is read. */
    @EventListener
    public void onInvoiceIssued(InvoiceIssuedEvent event) {
        if (event == null || event.invoiceId() == null || isBlank(event.patientSystemId())) {
            log.warn("Invoice-issued notification skipped: incomplete event (invoice {})",
                    event == null ? null : event.invoiceId());
            return;
        }
        String invoiceNo = invoiceNoOf(event.invoiceNo(), event.invoiceId());
        String message = "Invoice " + invoiceNo + " for " + BillingText.money(event.currency(), event.total())
                + " was issued.";
        if (event.dueDate() != null) {
            message += " Payment is due by " + BillingText.date(event.dueDate()) + ".";
        }
        publisher.publishToPatientCircle(event.patientSystemId(), PatientPermission.BILLING,
                template(event.invoiceId(), "New invoice", message, NotificationPriority.NORMAL, null,
                        KIND_INVOICE_ISSUED + ":" + event.invoiceId()));
    }

    // =====================================================================================================
    // PAYMENTS
    // =====================================================================================================

    /**
     * COMPLETED: "Payment received" to the circle except the payer. FAILED: "Payment failed" to the whole circle.
     * REFUNDED: "Refund issued" to the circle, once per refund. Payments without an invoice and other statuses are
     * ignored.
     */
    @EventListener
    public void onPaymentRecorded(PaymentRecordedEvent event) {
        if (event == null || event.invoiceId() == null || event.status() == null) {
            return;
        }
        String status = event.status();
        if (!PaymentRecordedEvent.COMPLETED.equals(status) && !PaymentRecordedEvent.FAILED.equals(status)
                && !PaymentRecordedEvent.REFUNDED.equals(status)) {
            return;
        }
        if (event.paymentId() == null) {
            log.warn("Payment notification skipped: event without a payment id (invoice {}, status {})",
                    event.invoiceId(), status);
            return;
        }
        Optional<Invoice> found = invoiceRepository.findById(event.invoiceId());
        if (found.isEmpty()) {
            log.warn("Payment notification skipped: invoice {} of payment {} not found", event.invoiceId(),
                    event.paymentId());
            return;
        }
        Invoice invoice = found.get();
        String patient = firstNonBlank(event.patientSystemId(), invoice.getPatientSystemId());
        if (patient == null) {
            log.warn("Payment notification skipped: no patient for payment {}", event.paymentId());
            return;
        }
        String invoiceNo = invoiceNoOf(invoice.getInvoiceNo(), invoice.getInvoiceId());
        String amount = BillingText.money(firstNonBlank(event.currency(), invoice.getCurrency()), event.amount());

        NotificationCommand command = switch (status) {
            case PaymentRecordedEvent.COMPLETED -> template(invoice.getInvoiceId(), "Payment received",
                    "A payment of " + amount + " was received for invoice " + invoiceNo + ".",
                    NotificationPriority.NORMAL, blankToNull(event.initiatedBySystemId()),
                    KIND_PAYMENT_RECEIVED + ":" + event.paymentId());
            case PaymentRecordedEvent.FAILED -> template(invoice.getInvoiceId(), "Payment failed",
                    "A payment of " + amount + " for invoice " + invoiceNo + " did not go through.",
                    NotificationPriority.NORMAL, null,
                    KIND_PAYMENT_FAILED + ":" + event.paymentId());
            default -> template(invoice.getInvoiceId(), "Refund issued",
                    "A refund of " + amount + " was issued for invoice " + invoiceNo + ".",
                    NotificationPriority.NORMAL, blankToNull(event.initiatedBySystemId()),
                    KIND_REFUND_ISSUED + ":" + event.paymentId() + ":" + cumulativeRefund(event));
        };
        publisher.publishToPatientCircle(patient, PatientPermission.BILLING, command);
    }

    /** The payment's cumulative refunded amount (each partial refund differs); the event amount when the row is gone. */
    private String cumulativeRefund(PaymentRecordedEvent event) {
        BigDecimal refunded = paymentRepository.findById(event.paymentId())
                .map(Payment::getRefundedAmount)
                .orElse(event.amount());
        return BillingText.plain(refunded);
    }

    // =====================================================================================================
    // INVOICE STATUS
    // =====================================================================================================

    /**
     * To OVERDUE: "Invoice overdue" (HIGH) to the circle, once per invoice. To PAID: "Invoice paid" to the issuing
     * doctor, once per invoice. Every other status notifies nobody (issue, part payments and refunds have their own
     * events; a cancellation notifies nobody).
     */
    @EventListener
    public void onInvoiceStatusChanged(InvoiceStatusChangedEvent event) {
        if (event == null || event.invoiceId() == null || event.status() == null
                || event.status().equals(event.previousStatus())) {
            return;
        }
        boolean overdue = Invoice.STATUS_OVERDUE.equals(event.status());
        boolean paid = Invoice.STATUS_PAID.equals(event.status());
        if (!overdue && !paid) {
            return;
        }
        Optional<Invoice> found = invoiceRepository.findById(event.invoiceId());
        if (found.isEmpty()) {
            log.warn("Invoice status notification skipped: invoice {} not found (status {})", event.invoiceId(),
                    event.status());
            return;
        }
        Invoice invoice = found.get();
        String patient = firstNonBlank(event.patientSystemId(), invoice.getPatientSystemId());
        String invoiceNo = invoiceNoOf(invoice.getInvoiceNo(), invoice.getInvoiceId());

        if (overdue) {
            if (patient == null) {
                log.warn("Overdue notification skipped: no patient for invoice {}", invoice.getInvoiceId());
                return;
            }
            BigDecimal balance = BillingText.money(invoice.getTotal()).subtract(BillingText.money(invoice.getAmountPaid()));
            if (balance.signum() < 0) {
                balance = BillingText.money(BigDecimal.ZERO);
            }
            publisher.publishToPatientCircle(patient, PatientPermission.BILLING,
                    template(invoice.getInvoiceId(), "Invoice overdue",
                            "Invoice " + invoiceNo + " is overdue. " + BillingText.money(invoice.getCurrency(), balance)
                                    + " is still to be paid.",
                            NotificationPriority.HIGH, null, KIND_INVOICE_OVERDUE + ":" + invoice.getInvoiceId()));
            return;
        }

        String doctor = blankToNull(invoice.getDoctorSystemId());
        if (doctor == null) {
            return;
        }
        publisher.publish(template(invoice.getInvoiceId(), "Invoice paid",
                "Invoice " + invoiceNo + " for " + BillingText.money(invoice.getCurrency(), invoice.getTotal())
                        + " has been paid in full.",
                NotificationPriority.NORMAL, null, KIND_INVOICE_PAID + ":" + invoice.getInvoiceId())
                .toBuilder()
                .recipientSystemId(doctor)
                .patientSystemId(patient)
                .build());
    }

    // =====================================================================================================
    // INSURANCE CLAIMS
    // =====================================================================================================

    /**
     * Every claim status change, submission included (previous status null): one notification to the circle. Only a
     * submission knows its actor (the claim's submitter, who is skipped). The dedupe key ends with the epoch second,
     * so a status reached again later (rejected, appealed, rejected) notifies again.
     */
    @EventListener
    public void onClaimStatusChanged(InsuranceClaimStatusChangedEvent event) {
        if (event == null || event.invoiceId() == null || event.status() == null
                || event.status().equals(event.previousStatus())) {
            return;
        }
        String[] texts = CLAIM_TEXTS.get(event.status());
        if (texts == null) {
            return;
        }
        if (event.claimId() == null) {
            log.warn("Claim notification skipped: event without a claim id (invoice {})", event.invoiceId());
            return;
        }
        Optional<Invoice> found = invoiceRepository.findById(event.invoiceId());
        if (found.isEmpty()) {
            log.warn("Claim notification skipped: invoice {} of claim {} not found", event.invoiceId(),
                    event.claimId());
            return;
        }
        Invoice invoice = found.get();
        String patient = firstNonBlank(event.patientSystemId(), invoice.getPatientSystemId());
        if (patient == null) {
            log.warn("Claim notification skipped: no patient for claim {}", event.claimId());
            return;
        }
        String actor = null;
        if (InsuranceClaim.STATUS_SUBMITTED.equals(event.status())) {
            actor = claimRepository.findById(event.claimId())
                    .map(InsuranceClaim::getSubmittedBySystemId)
                    .map(BillingNotificationListener::blankToNull)
                    .orElse(null);
        }
        String invoiceNo = invoiceNoOf(invoice.getInvoiceNo(), invoice.getInvoiceId());
        long handledAt = Instant.now(clock).getEpochSecond();
        publisher.publishToPatientCircle(patient, PatientPermission.BILLING,
                template(invoice.getInvoiceId(), texts[0], String.format(texts[1], invoiceNo),
                        NotificationPriority.NORMAL, actor,
                        KIND_CLAIM_STATUS + ":" + event.claimId() + ":" + event.status() + ":" + handledAt));
    }

    // =====================================================================================================
    // HELPERS
    // =====================================================================================================

    private static NotificationCommand template(Integer invoiceId, String title, String message,
                                                NotificationPriority priority, String actorSystemId,
                                                String dedupeKey) {
        return NotificationCommand.builder()
                .type(NotificationType.PAYMENT)
                .category(NotificationCategory.BILLING)
                .priority(priority)
                .title(title)
                .message(message)
                .target(NotificationTarget.BILLING)
                .resourceType(RESOURCE_TYPE)
                .resourceId(invoiceId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey)
                .build();
    }

    /** The stored invoice number; {@code #<id>} only for a row that somehow has none. */
    private static String invoiceNoOf(String invoiceNo, Integer invoiceId) {
        return isBlank(invoiceNo) ? "#" + invoiceId : invoiceNo.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : blankToNull(second);
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
