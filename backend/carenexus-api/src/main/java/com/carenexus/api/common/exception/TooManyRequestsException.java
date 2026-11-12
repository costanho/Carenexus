package com.carenexus.api.common.exception;

/**
 * Thrown when a caller exceeded a rate limit. GlobalExceptionHandler maps it to HTTP 429 with code
 * {@code RATE_LIMITED} and a {@code Retry-After} header carrying {@link #getRetryAfterSeconds()}.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds seconds until the caller may try again (values below 1 are reported as 1)
     */
    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(retryAfterSeconds, 1);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
