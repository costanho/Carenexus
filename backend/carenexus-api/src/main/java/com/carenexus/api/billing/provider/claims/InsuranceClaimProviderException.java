package com.carenexus.api.billing.provider.claims;

/**
 * Thrown by an {@code InsuranceClaimProvider} when the provider cannot be reached or refuses a request. The message
 * is short and safe to store or log: no policy or member numbers, no secrets, no response bodies.
 */
public class InsuranceClaimProviderException extends RuntimeException {

    private final boolean retryable;

    public InsuranceClaimProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public InsuranceClaimProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** True when the same request may succeed later (timeout, provider down). */
    public boolean isRetryable() {
        return retryable;
    }
}
