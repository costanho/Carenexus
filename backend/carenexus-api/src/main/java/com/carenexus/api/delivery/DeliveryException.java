package com.carenexus.api.delivery;

/**
 * A sender could not hand a message to its provider.
 *
 * The message is stored in {@code outbound_messages.last_error} and shown in the development outbox, so it must be
 * a short technical reason ("Provider timeout", "HTTP 503") and must never contain the message text, the recipient
 * address, a token or a secret.
 */
public class DeliveryException extends RuntimeException {

    private final boolean retryable;

    /**
     * @param shortMessage short reason without personal data, at most 300 characters are kept
     * @param retryable    true when a later attempt may succeed (timeout, 5xx, rate limit); false when it cannot
     *                     (rejected address, invalid request), which marks the message FAILED at once
     */
    public DeliveryException(String shortMessage, boolean retryable) {
        super(shortMessage);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
