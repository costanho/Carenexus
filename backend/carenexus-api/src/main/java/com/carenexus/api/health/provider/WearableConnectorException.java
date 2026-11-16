package com.carenexus.api.health.provider;

/**
 * A wearable platform call failed. The message is short and never contains tokens or reading values.
 */
public class WearableConnectorException extends RuntimeException {

    private final boolean retryable;

    public WearableConnectorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
