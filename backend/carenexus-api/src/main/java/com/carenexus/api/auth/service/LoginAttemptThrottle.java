package com.carenexus.api.auth.service;

import com.carenexus.api.common.ratelimit.RateDecision;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.common.ratelimit.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Brute-force guard for {@code POST /api/auth/login}, on the shared {@link RateLimiter}
 * (buckets under {@code carenexus.rate-limit.buckets}):
 * <ul>
 *   <li>{@code auth-login-ip}: attempts from one client address, whatever the email (credential stuffing);</li>
 *   <li>{@code auth-login-email-ip}: attempts for one email from one address (password guessing);</li>
 *   <li>{@code auth-login-account}: attempts for one email from any address (distributed guessing). The key is the
 *       email whether or not an account exists, so a refusal never reveals that it does.</li>
 * </ul>
 * Every attempt is counted by {@link #checkAllowed}, before the password is looked at, so a blocked caller is
 * refused even with the right password until the window that started with its first attempt ends. A verified
 * password clears the two email buckets ({@link #recordSuccess}); the address bucket is never cleared.
 *
 * Nothing is counted while {@code carenexus.rate-limit.enabled=false}. The counters live in the configured
 * rate-limit store (in memory by default, so per API instance).
 */
@Component
@RequiredArgsConstructor
public class LoginAttemptThrottle {

    public static final String BUCKET_IP = "auth-login-ip";
    public static final String BUCKET_EMAIL_IP = "auth-login-email-ip";
    public static final String BUCKET_ACCOUNT = "auth-login-account";

    /** Clients build their own wording from Retry-After; this text deliberately names no duration. */
    static final String MESSAGE = "Too many sign-in attempts. Please try again later.";

    private final RateLimiter rateLimiter;
    private final RateLimitProperties properties;

    /**
     * Counts this attempt in the three buckets, in the order above, and throws {@link TooManyLoginAttemptsException}
     * (HTTP 429 with the real remaining seconds) for the first bucket that is exhausted. Buckets after the one that
     * refused are not counted, so an address that is already blocked cannot lock other people's accounts.
     */
    public void checkAllowed(String email, String ipAddress) {
        if (!properties.isEnabled()) {
            return;
        }
        acquire(BUCKET_IP, ipKey(ipAddress));
        acquire(BUCKET_EMAIL_IP, emailIpKey(email, ipAddress));
        acquire(BUCKET_ACCOUNT, accountKey(email));
    }

    /**
     * Kept for callers of the earlier throttle. A failed attempt was already counted by {@link #checkAllowed},
     * so there is nothing more to record.
     */
    public void recordFailure(String email, String ipAddress) {
        // Counted in checkAllowed.
    }

    /** The password was verified: forget the attempts counted for this email (from this address and overall). */
    public void recordSuccess(String email, String ipAddress) {
        rateLimiter.reset(BUCKET_EMAIL_IP, emailIpKey(email, ipAddress));
        rateLimiter.reset(BUCKET_ACCOUNT, accountKey(email));
    }

    private void acquire(String bucket, String key) {
        RateDecision decision = rateLimiter.tryAcquire(bucket, key);
        if (!decision.allowed()) {
            throw new TooManyLoginAttemptsException(MESSAGE, decision.retryAfterSeconds(), bucket);
        }
    }

    private static String ipKey(String ipAddress) {
        return ipAddress == null ? "" : ipAddress;
    }

    private static String accountKey(String email) {
        return email == null ? "" : email;
    }

    private static String emailIpKey(String email, String ipAddress) {
        return accountKey(email) + "|" + ipKey(ipAddress);
    }
}
