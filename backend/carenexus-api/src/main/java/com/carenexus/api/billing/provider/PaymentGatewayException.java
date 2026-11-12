package com.carenexus.api.billing.provider;

/**
 * A gateway call failed. The message is short and safe to log (no secrets, tokens, card data or payloads); the API
 * answers 503 {@code PROVIDER_UNAVAILABLE} with its own wording and never echoes this message to clients.
 */
public class PaymentGatewayException extends RuntimeException {

    private final boolean retryable;

    public PaymentGatewayException(String shortMessage, boolean retryable) {
        super(shortMessage);
        this.retryable = retryable;
    }

    /** True when the same request may succeed later (timeout, gateway outage). */
    public boolean isRetryable() {
        return retryable;
    }
}
