package com.carenexus.api.common.ratelimit;

/**
 * Counts calls per (bucket, key) and says whether one more is allowed. The bucket names its limit and window
 * ({@code carenexus.rate-limit.buckets.<name>}); the key identifies the caller: a system id, {@code email|ip}, or an
 * ip taken from {@code request.getRemoteAddr()} only.
 *
 * The store is selected by {@code carenexus.rate-limit.store}. {@link InMemoryRateLimiter} ({@code memory}) counts per
 * API instance; a shared store (for example Redis) is a drop-in implementation of this interface.
 * Application code normally calls {@link RateLimitGuard#check}, which also honours {@code carenexus.rate-limit.enabled}.
 */
public interface RateLimiter {

    /** Counts this call. Denied calls report the seconds left in the current window. */
    RateDecision tryAcquire(String bucket, String key);

    /** Forgets the calls counted for this key (for example after a successful login). */
    void reset(String bucket, String key);
}
