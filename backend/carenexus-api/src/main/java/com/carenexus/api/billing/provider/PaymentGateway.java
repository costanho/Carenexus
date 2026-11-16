package com.carenexus.api.billing.provider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Payment gateway adapter (modelled on {@code calls.provider.CallProvider}). The active implementation is chosen by
 * {@code carenexus.billing.provider} in {@code BillingGatewayConfig}; {@link LocalPaymentGateway} ({@code local}) is
 * the built-in one. A real adapter (M-PESA Daraja, Stripe, ...) implements this interface and is selected by its own
 * provider id.
 * <p>
 * Implementations never log card data, tokens, secrets, webhook bodies or webhook headers, and never return them in
 * an exception message. Card numbers and CVVs never reach this interface: a saved method is passed as an opaque token.
 */
public interface PaymentGateway {

    /** Result statuses of {@link #initiate}. */
    String STATUS_PENDING = "PENDING";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED = "FAILED";

    /** Webhook event types every gateway maps its own events onto; any other value is ignored. */
    String EVENT_PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    String EVENT_PAYMENT_FAILED = "PAYMENT_FAILED";

    /** Gateway id: the {@code {provider}} of the webhook path and the {@code gateway} stored on each payment. */
    String name();

    /** True when the gateway can take payments and verify its webhook (credentials or secret present). */
    boolean isConfigured();

    /** Payment methods ({@code PaymentMethod.TYPE_*}) the gateway accepts right now. */
    Set<String> supportedMethods();

    /**
     * Starts a payment. Called once per payment, inside the initiating transaction.
     *
     * @throws PaymentGatewayException when the gateway cannot be reached or refuses the request for a technical
     *                                 reason (nothing is stored; the client retries with the same key)
     */
    InitiateResult initiate(InitiateRequest request);

    /**
     * Refunds (part of) a settled payment.
     *
     * @throws PaymentGatewayException when the refund could not be made (nothing changes)
     */
    RefundResult refund(String providerReference, BigDecimal amount, String currency, String idempotencyKey);

    /** Turns method details into an opaque token the gateway can charge later (local: {@code local_pm_<32 hex>}). */
    String tokenize(String method, Map<String, String> details);

    /**
     * Verifies the signature over the exact raw body and parses the event.
     *
     * @param rawBody the request body as received (UTF-8)
     * @param headers every request header, names lower-cased
     * @throws InvalidWebhookSignatureException when the signature is missing, malformed or does not match
     * @throws com.carenexus.api.billing.provider.gateway.MalformedWebhookPayloadException when the signature is
     *                                          valid but the body is not the gateway's event format
     */
    WebhookEvent parseWebhook(String rawBody, Map<String, String> headers);

    /**
     * @param paymentRef     the API's own reference of the attempt (unique)
     * @param idempotencyKey key the gateway uses to de-duplicate a retried call
     * @param amount         scale 2, above zero
     * @param currency       ISO-4217
     * @param method         {@code PaymentMethod.TYPE_*}
     * @param payerPhone     MPESA: the phone to prompt; null otherwise
     * @param methodToken    token of a saved method, or null
     * @param description    short text shown by the gateway (no clinical data)
     */
    record InitiateRequest(String paymentRef, String idempotencyKey, BigDecimal amount, String currency, String method,
                           String payerPhone, String methodToken, String description) {
    }

    /**
     * @param providerReference the gateway's reference of the payment (what its webhook reports)
     * @param status            {@link #STATUS_PENDING}, {@link #STATUS_COMPLETED} or {@link #STATUS_FAILED}
     * @param nextAction        NONE, STK_PUSH, AWAIT_CONFIRMATION or REDIRECT (meaningful while PENDING)
     * @param redirectUrl       the gateway page for REDIRECT, otherwise null
     * @param failureReason     short displayable sentence for FAILED, otherwise null
     */
    record InitiateResult(String providerReference, String status, String nextAction, String redirectUrl,
                          String failureReason) {
    }

    /** @param status COMPLETED or PENDING (the money is on its way back) */
    record RefundResult(String providerReference, String status) {
    }

    /**
     * Only what the API needs from a webhook.
     *
     * @param eventId           unique per event at this gateway (idempotency key of {@code payment_events})
     * @param eventType         {@link #EVENT_PAYMENT_COMPLETED}, {@link #EVENT_PAYMENT_FAILED} or another value (ignored)
     * @param providerReference the payment's {@code providerReference}
     * @param transactionId     confirmation code reported on completion, or null
     * @param failureReason     reason reported on failure, or null
     */
    record WebhookEvent(String eventId, String eventType, String providerReference, String transactionId,
                        String failureReason) {
    }
}
