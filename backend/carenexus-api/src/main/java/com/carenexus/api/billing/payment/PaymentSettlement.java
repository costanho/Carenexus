package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The two final transitions of a PENDING payment, shared by the webhook (and through it the dev simulation) and the
 * reconciliation sweeper. Both run inside the caller's transaction ({@link Propagation#MANDATORY}) on a payment row
 * the caller has locked, save the row and then publish {@link PaymentRecordedEvent} in that transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSettlement {

    /** failureReason when the gateway collected a payment the invoice can no longer take. */
    public static final String INVOICE_REFUSED = "The invoice could no longer accept this payment";

    static final int MAX_FAILURE_REASON = 500;

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * PENDING to COMPLETED: the invoice row is locked and, when it still accepts the amount, the payment is applied
     * to it ({@code invoiceService.applyPayment}) in the same transaction. When it does not (cancelled, voided or
     * settled meanwhile) the payment ends FAILED with {@link #INVOICE_REFUSED} instead; with a real gateway the
     * collected money must then be voided or refunded by the adapter (one WARN line, payment id only).
     * <p>
     * The invoice's acceptance is checked here, under the lock, rather than by catching applyPayment's
     * ConflictException: an exception leaving that transactional facade would mark the whole transaction
     * rollback-only and lose the FAILED state and the event row.
     *
     * @param transactionId confirmation code from the gateway; not stored when another payment already carries it
     * @return true when the payment was completed, false when it ended FAILED
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(Payment payment, String transactionId) {
        if (!invoiceAccepts(payment)) {
            log.warn("Payment {} was confirmed by the gateway but its invoice could no longer accept it",
                    payment.getPaymentId());
            fail(payment, INVOICE_REFUSED);
            return false;
        }
        invoiceService.applyPayment(payment.getInvoiceId(), payment.getAmount());

        LocalDateTime now = now();
        if (transactionId != null && !transactionId.isBlank()
                && !paymentRepository.existsByTransactionIdAndPaymentIdNot(transactionId, payment.getPaymentId())) {
            payment.setTransactionId(transactionId);
        }
        payment.setStatus(Payment.STATUS_COMPLETED);
        payment.setPaidAt(now);
        payment.setNextAction(null);
        payment.setNextActionUrl(null);
        payment.setFailureReason(null);
        payment.setUpdatedAt(now);
        paymentRepository.saveAndFlush(payment);
        publish(payment, PaymentRecordedEvent.COMPLETED, payment.getAmount(), payment.getInitiatedBySystemId());
        return true;
    }

    /** PENDING to FAILED with a displayable reason (at most 500 characters). The invoice is not touched. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(Payment payment, String reason) {
        String text = reason == null || reason.isBlank() ? "Payment failed" : reason.trim();
        if (text.length() > MAX_FAILURE_REASON) {
            text = text.substring(0, MAX_FAILURE_REASON);
        }
        payment.setStatus(Payment.STATUS_FAILED);
        payment.setFailureReason(text);
        payment.setNextAction(null);
        payment.setNextActionUrl(null);
        payment.setUpdatedAt(now());
        paymentRepository.saveAndFlush(payment);
        publish(payment, PaymentRecordedEvent.FAILED, payment.getAmount(), payment.getInitiatedBySystemId());
    }

    /** Publishes {@link PaymentRecordedEvent} for a saved payment. */
    public void publish(Payment payment, String status, BigDecimal amount, String initiatedBySystemId) {
        eventPublisher.publishEvent(new PaymentRecordedEvent(payment.getPaymentId(), payment.getInvoiceId(),
                payment.getPatientSystemId(), initiatedBySystemId, PaymentResponseMapper.money(amount),
                payment.getCurrency(), payment.getMethod(), status));
    }

    /** Locks the invoice and checks it is payable and its balance covers the payment. */
    private boolean invoiceAccepts(Payment payment) {
        if (payment.getInvoiceId() == null) {
            return false;
        }
        Optional<Invoice> invoice = invoiceRepository.findByIdForUpdate(payment.getInvoiceId());
        if (invoice.isEmpty() || !Invoice.PAYABLE_STATUSES.contains(invoice.get().getStatus())) {
            return false;
        }
        BigDecimal outstanding = invoiceService.outstanding(payment.getInvoiceId());
        return PaymentResponseMapper.money(payment.getAmount()).compareTo(outstanding) <= 0;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
