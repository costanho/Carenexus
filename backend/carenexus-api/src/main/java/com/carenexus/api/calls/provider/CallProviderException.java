package com.carenexus.api.calls.provider;

/**
 * A provider call failed. The message is short and safe to store in last_error (no secrets, no tokens).
 */
public class CallProviderException extends RuntimeException {

    private final boolean retryable;

    public CallProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
