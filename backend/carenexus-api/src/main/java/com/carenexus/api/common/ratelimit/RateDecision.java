package com.carenexus.api.common.ratelimit;

/**
 * Answer of a {@link RateLimiter}.
 *
 * @param allowed           true when the call is within the limit
 * @param retryAfterSeconds seconds until the window ends when {@code allowed} is false (at least 1); 0 when allowed
 */
public record RateDecision(boolean allowed, long retryAfterSeconds) {

    private static final RateDecision ALLOWED = new RateDecision(true, 0);

    public static RateDecision allow() {
        return ALLOWED;
    }

    public static RateDecision deny(long retryAfterSeconds) {
        return new RateDecision(false, Math.max(retryAfterSeconds, 1));
    }
}
