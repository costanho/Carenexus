package com.carenexus.api.auth.service;

import com.carenexus.api.common.exception.TooManyRequestsException;

/**
 * Thrown when a login rate limit refuses a sign-in attempt. GlobalExceptionHandler maps it (as a
 * {@link TooManyRequestsException}) to HTTP 429 with code RATE_LIMITED and a Retry-After header.
 */
public class TooManyLoginAttemptsException extends TooManyRequestsException {

    /** The email+address window is 15 minutes; used when the caller does not know the remaining time. */
    static final long DEFAULT_RETRY_AFTER_SECONDS = 900;

    /** The rate-limit bucket that refused the attempt; null when the thrower did not say. */
    private final String bucket;

    public TooManyLoginAttemptsException(String message) {
        this(message, DEFAULT_RETRY_AFTER_SECONDS);
    }

    public TooManyLoginAttemptsException(String message, long retryAfterSeconds) {
        this(message, retryAfterSeconds, null);
    }

    public TooManyLoginAttemptsException(String message, long retryAfterSeconds, String bucket) {
        super(message, retryAfterSeconds);
        this.bucket = bucket;
    }

    public String getBucket() {
        return bucket;
    }

    /** True when the per-account limit refused the attempt: the account is locked for the rest of its window. */
    public boolean isAccountLocked() {
        return LoginAttemptThrottle.BUCKET_ACCOUNT.equals(bucket);
    }
}
