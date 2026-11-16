package com.carenexus.api.common.ratelimit;

import com.carenexus.api.common.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What application code calls to enforce a rate limit:
 * <pre>
 * rateLimitGuard.check("appointment-book", authorizationService.currentSystemId());
 * rateLimitGuard.check("auth-register-ip", request.getRemoteAddr());
 * </pre>
 * Throws {@link TooManyRequestsException} (HTTP 429, code RATE_LIMITED, Retry-After header) when the caller is over
 * the bucket's limit. Does nothing when {@code carenexus.rate-limit.enabled=false}.
 * Keys are a system id, {@code email|ip}, or an ip from {@code request.getRemoteAddr()} only (never a client header).
 */
@Component
@RequiredArgsConstructor
public class RateLimitGuard {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    /** Counts one call for (bucket, key) and throws when the limit is exceeded. */
    public void check(String bucket, String key) {
        if (!properties.isEnabled()) {
            return;
        }
        RateDecision decision = rateLimiter.tryAcquire(bucket, key);
        if (!decision.allowed()) {
            throw new TooManyRequestsException(message(decision.retryAfterSeconds()), decision.retryAfterSeconds());
        }
    }

    /** Forgets the calls counted for this key (for example the failed logins after a successful one). */
    public void reset(String bucket, String key) {
        rateLimiter.reset(bucket, key);
    }

    static String message(long retryAfterSeconds) {
        if (retryAfterSeconds < 60) {
            return "Too many requests. Please try again in " + retryAfterSeconds
                    + (retryAfterSeconds == 1 ? " second." : " seconds.");
        }
        long minutes = (retryAfterSeconds + 59) / 60;
        if (minutes < 120) {
            return "Too many requests. Please try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
        }
        long hours = (minutes + 59) / 60;
        return "Too many requests. Please try again in " + hours + " hours.";
    }
}
