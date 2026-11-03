package com.carenexus.api.auth.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory brute-force guard for /api/auth/login, keyed by email + client IP.
 * After MAX_FAILURES failed attempts inside WINDOW the key is blocked until the window
 * (measured from the first failure) has passed. A successful login clears the key.
 *
 * Per-instance only: with several API replicas each one counts separately. Replace with a
 * shared store (Redis/Bucket4j) if the API is scaled out.
 */
@Component
public class LoginAttemptThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Attempts(long windowStartMillis, int failures) {}

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String email, String ipAddress) {
        Attempts a = attempts.get(key(email, ipAddress));
        if (a != null && !expired(a, System.currentTimeMillis()) && a.failures() >= MAX_FAILURES) {
            throw new TooManyLoginAttemptsException(
                    "Too many failed sign-in attempts. Please wait 15 minutes and try again.");
        }
    }

    public void recordFailure(String email, String ipAddress) {
        long now = System.currentTimeMillis();
        if (attempts.size() > MAX_TRACKED_KEYS) {
            attempts.values().removeIf(a -> expired(a, now));
        }
        attempts.compute(key(email, ipAddress), (k, a) ->
                (a == null || expired(a, now)) ? new Attempts(now, 1) : new Attempts(a.windowStartMillis(), a.failures() + 1));
    }

    public void recordSuccess(String email, String ipAddress) {
        attempts.remove(key(email, ipAddress));
    }

    private static boolean expired(Attempts a, long now) {
        return now - a.windowStartMillis() > WINDOW.toMillis();
    }

    private static String key(String email, String ipAddress) {
        return (email == null ? "" : email) + "|" + (ipAddress == null ? "" : ipAddress);
    }
}
