package com.carenexus.api.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed-window behaviour of the in-memory rate limiter and bucket resolution (no Spring context).
 */
public class InMemoryRateLimiterTest {

    private RateLimitProperties properties;
    private MutableClock clock;
    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
        limiter = new InMemoryRateLimiter(properties, clock);
    }

    @Test
    void allowsUpToTheLimit_thenDeniesUntilTheWindowEnds() {
        // auth-mfa: 5 per 300 s
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("auth-mfa", "PAT-00001").allowed(), "call " + (i + 1));
        }
        RateDecision denied = limiter.tryAcquire("auth-mfa", "PAT-00001");
        assertFalse(denied.allowed());
        assertEquals(300, denied.retryAfterSeconds());

        clock.advance(Duration.ofSeconds(100));
        RateDecision later = limiter.tryAcquire("auth-mfa", "PAT-00001");
        assertFalse(later.allowed());
        assertEquals(200, later.retryAfterSeconds(), "Retry-After counts down to the end of the window");

        clock.advance(Duration.ofSeconds(199));
        assertFalse(limiter.tryAcquire("auth-mfa", "PAT-00001").allowed(), "one second before the window ends");

        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire("auth-mfa", "PAT-00001").allowed(), "a new window starts when the old one ends");
    }

    @Test
    void theWindowStartsAtTheFirstCall_notAtTheLastOne() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("auth-mfa", "k");
            clock.advance(Duration.ofSeconds(50));
        }
        // 250 s after the first call: still the same window, limit reached
        assertFalse(limiter.tryAcquire("auth-mfa", "k").allowed());
        clock.advance(Duration.ofSeconds(50));
        assertTrue(limiter.tryAcquire("auth-mfa", "k").allowed());
    }

    @Test
    void keysAndBucketsAreCountedSeparately() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("auth-mfa", "a");
        }
        assertFalse(limiter.tryAcquire("auth-mfa", "a").allowed());
        assertTrue(limiter.tryAcquire("auth-mfa", "b").allowed(), "another key has its own counter");
        assertTrue(limiter.tryAcquire("password-change", "a").allowed(), "another bucket has its own counter");
    }

    @Test
    void resetForgetsTheKey() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("auth-login-email-ip", "linda@example.com|203.0.113.9");
        }
        assertFalse(limiter.tryAcquire("auth-login-email-ip", "linda@example.com|203.0.113.9").allowed());

        limiter.reset("auth-login-email-ip", "linda@example.com|203.0.113.9");

        assertTrue(limiter.tryAcquire("auth-login-email-ip", "linda@example.com|203.0.113.9").allowed());
    }

    @Test
    void deniedCallsDoNotExtendTheWindow_andRetryAfterIsAtLeastOneSecond() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("auth-mfa", "k");
        }
        for (int i = 0; i < 1000; i++) {
            assertFalse(limiter.tryAcquire("auth-mfa", "k").allowed());
        }
        clock.advance(Duration.ofMillis(299_500));
        RateDecision almost = limiter.tryAcquire("auth-mfa", "k");
        assertFalse(almost.allowed());
        assertEquals(1, almost.retryAfterSeconds());
        clock.advance(Duration.ofMillis(500));
        assertTrue(limiter.tryAcquire("auth-mfa", "k").allowed());
    }

    @Test
    void predefinedBuckets_haveTheContractDefaults() {
        assertEquals(new RateLimitProperties.Limit(120, 60), properties.resolve("default"));
        assertEquals(new RateLimitProperties.Limit(5, 900), properties.resolve("auth-login-email-ip"));
        assertEquals(new RateLimitProperties.Limit(30, 900), properties.resolve("auth-login-ip"));
        assertEquals(new RateLimitProperties.Limit(20, 3600), properties.resolve("auth-login-account"));
        assertEquals(new RateLimitProperties.Limit(10, 3600), properties.resolve("auth-register-ip"));
        assertEquals(new RateLimitProperties.Limit(120, 900), properties.resolve("auth-refresh-ip"));
        assertEquals(new RateLimitProperties.Limit(3, 3600), properties.resolve("auth-forgot-email"));
        assertEquals(new RateLimitProperties.Limit(10, 3600), properties.resolve("auth-forgot-ip"));
        assertEquals(new RateLimitProperties.Limit(20, 3600), properties.resolve("auth-verify-ip"));
        assertEquals(new RateLimitProperties.Limit(5, 300), properties.resolve("auth-mfa"));
        assertEquals(new RateLimitProperties.Limit(5, 900), properties.resolve("password-change"));
        assertEquals(new RateLimitProperties.Limit(20, 3600), properties.resolve("appointment-book"));
        assertEquals(new RateLimitProperties.Limit(10, 86400), properties.resolve("caregiver-request"));
        assertEquals(new RateLimitProperties.Limit(10, 3600), properties.resolve("reminder-manual"));
        assertEquals(new RateLimitProperties.Limit(60, 3600), properties.resolve("document-upload"));
        assertEquals(new RateLimitProperties.Limit(30, 3600), properties.resolve("payment-initiate"));
        assertEquals(new RateLimitProperties.Limit(3, 86400), properties.resolve("data-export"));
        assertEquals(new RateLimitProperties.Limit(10, 86400), properties.resolve("correction-request"));
        assertEquals(new RateLimitProperties.Limit(60, 60), properties.resolve("message-send"));
        assertEquals(new RateLimitProperties.Limit(20, 3600), properties.resolve("thread-create"));
    }

    @Test
    void unknownBucket_usesDefault_andConfigurationOverridesPartially() {
        assertEquals(new RateLimitProperties.Limit(120, 60), properties.resolve("no-such-bucket"));
        assertEquals(new RateLimitProperties.Limit(120, 60), properties.resolve(null));

        RateLimitProperties.Bucket onlyLimit = new RateLimitProperties.Bucket();
        onlyLimit.setLimit(2);
        properties.getBuckets().put("auth-mfa", onlyLimit);
        assertEquals(new RateLimitProperties.Limit(2, 300), properties.resolve("auth-mfa"),
                "a bucket configured with only a limit keeps its code window");

        RateLimitProperties.Bucket custom = new RateLimitProperties.Bucket();
        custom.setWindowSeconds(10L);
        properties.getBuckets().put("custom-bucket", custom);
        assertEquals(new RateLimitProperties.Limit(120, 10), properties.resolve("custom-bucket"),
                "a bucket that exists only in configuration inherits missing values from default");

        RateLimitProperties.Bucket newDefault = new RateLimitProperties.Bucket();
        newDefault.setLimit(7);
        properties.getBuckets().put("default", newDefault);
        assertEquals(new RateLimitProperties.Limit(7, 60), properties.resolve("no-such-bucket"));

        assertTrue(limiter.tryAcquire("auth-mfa", "k").allowed());
        assertTrue(limiter.tryAcquire("auth-mfa", "k").allowed());
        assertFalse(limiter.tryAcquire("auth-mfa", "k").allowed(), "the configured limit of 2 applies");
    }

    @Test
    void theMapIsBounded_expiredWindowsGoFirst() {
        properties.setMaxTrackedKeys(100);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("auth-mfa", "old-" + i);
        }
        assertEquals(100, limiter.trackedKeys());

        clock.advance(Duration.ofSeconds(301));           // every window above has ended
        limiter.tryAcquire("auth-mfa", "fresh");
        assertEquals(1, limiter.trackedKeys(), "expired windows are dropped when the map is full");

        for (int i = 0; i < 500; i++) {                   // a flood of distinct live keys
            limiter.tryAcquire("auth-mfa", "flood-" + i);
        }
        assertTrue(limiter.trackedKeys() <= 100, "tracked keys: " + limiter.trackedKeys());
    }
}
