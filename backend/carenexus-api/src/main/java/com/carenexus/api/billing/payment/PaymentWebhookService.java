package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentEvent;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.repository.PaymentEventRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Processes gateway callbacks (POST /api/integrations/payments/{provider}/webhook and the dev-only simulation).
 * One transaction per event:
 * <ol>
 *   <li>the event is recorded in {@code payment_events} with insert-if-absent; a repeated delivery stops here;</li>
 *   <li>the payment is found by gateway and provider reference and locked; unknown reference or event type:
 *       outcome IGNORED;</li>
 *   <li>a payment that is no longer PENDING never changes: outcome STALE (late, duplicate or out-of-order events,
 *       also a late success after the reconciliation sweeper failed the payment);</li>
 *   <li>PAYMENT_COMPLETED / PAYMENT_FAILED on a PENDING payment: {@link PaymentSettlement}, outcome APPLIED.</li>
 * </ol>
 * Neither the body nor the headers are logged or stored. There is no authenticated actor, so no audit_log row is
 * written for these transitions; the trail is the payment_events row.
 */
@Service
@RequiredArgsConstructor
public class PaymentWebhookService {

    /** failureReason of a PAYMENT_FAILED event that carries none. */
    public static final String DEFAULT_FAILURE_REASON = "Payment was declined";

    /** Outcome reported for a delivery that was already recorded (not stored: the first row stays). */
    public static final String OUTCOME_DUPLICATE = "DUPLICATE";

    static final int MAX_EVENT_TYPE = 50;

    private final PaymentGateway paymentGateway;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentSettlement settlement;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /**
     * Verifies and parses the raw callback with the active gateway, then {@link #process}es it. The signature is
     * checked before any database work: a forged or unsigned callback never opens a transaction or takes a pooled
     * connection. Processing runs in its own transaction, or joins the caller's (the dev-only simulation).
     *
     * @param headers every request header, names lower-cased
     * @return APPLIED, IGNORED, STALE or {@link #OUTCOME_DUPLICATE}
     * @throws com.carenexus.api.billing.provider.InvalidWebhookSignatureException bad or missing signature
     * @throws com.carenexus.api.billing.provider.gateway.MalformedWebhookPayloadException signed but unparseable
     */
    public String receive(String rawBody, Map<String, String> headers) {
        PaymentGateway.WebhookEvent event = paymentGateway.parseWebhook(rawBody, headers);
        String provider = paymentGateway.name();
        return new TransactionTemplate(transactionManager).execute(status -> process(provider, event));
    }

    /** Applies one verified event of {@code provider}. See the class comment for the steps. */
    @Transactional
    public String process(String provider, PaymentGateway.WebhookEvent event) {
        String eventType = event.eventType() == null ? "" : event.eventType();
        String storedType = eventType.length() > MAX_EVENT_TYPE ? eventType.substring(0, MAX_EVENT_TYPE) : eventType;
        if (paymentEventRepository.insertIfAbsent(provider, event.eventId(), storedType) == 0) {
            return OUTCOME_DUPLICATE;
        }
        PaymentEvent row = paymentEventRepository.findByProviderAndEventId(provider, event.eventId())
                .orElseThrow(() -> new IllegalStateException("Recorded payment event could not be read back"));

        boolean known = PaymentGateway.EVENT_PAYMENT_COMPLETED.equals(eventType)
                || PaymentGateway.EVENT_PAYMENT_FAILED.equals(eventType);
        Optional<Payment> payment = known ? findAndLock(provider, event.providerReference()) : Optional.empty();
        if (payment.isEmpty()) {
            return finish(row, null, PaymentEvent.OUTCOME_IGNORED);
        }
        Payment target = payment.get();
        if (!Payment.STATUS_PENDING.equals(target.getStatus())) {
            return finish(row, target.getPaymentId(), PaymentEvent.OUTCOME_STALE);
        }
        if (PaymentGateway.EVENT_PAYMENT_COMPLETED.equals(eventType)) {
            settlement.complete(target, event.transactionId());
        } else {
            settlement.fail(target, event.failureReason() == null ? DEFAULT_FAILURE_REASON : event.failureReason());
        }
        return finish(row, target.getPaymentId(), PaymentEvent.OUTCOME_APPLIED);
    }

    private Optional<Payment> findAndLock(String provider, String providerReference) {
        if (providerReference == null || providerReference.isBlank()) {
            return Optional.empty();
        }
        // Only the id first: the row is then read fresh under its lock, never from an unlocked earlier read.
        return paymentRepository.findIdsByGatewayAndProviderReference(provider, providerReference, PageRequest.of(0, 1))
                .stream().findFirst()
                .flatMap(paymentRepository::findByIdForUpdate);
    }

    private String finish(PaymentEvent row, Integer paymentId, String outcome) {
        row.setPaymentId(paymentId);
        row.setOutcome(outcome);
        row.setProcessedAt(clock.instant());
        paymentEventRepository.save(row);
        return outcome;
    }
}
