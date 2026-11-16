package com.carenexus.api.auth.service;

import com.carenexus.api.common.ratelimit.InMemoryRateLimiter;
import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * LoginAttemptThrottle on the shared rate limiter: the three buckets, the real Retry-After, what a successful
 * sign-in clears and what happens when the window ends. No Spring context, a clock the test moves.
 */
class LoginAttemptThrottleTest {

    private static final String EMAIL = "linda@example.com";
    private static final String IP = "203.0.113.7";

    private RateLimitProperties properties;
    private MutableClock clock;
    private LoginAttemptThrottle throttle;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        clock = new MutableClock(Instant.parse("2026-11-10T09:00:00Z"));
        throttle = new LoginAttemptThrottle(new InMemoryRateLimiter(properties, clock), properties);
    }

    private TooManyLoginAttemptsException refused(String email, String ip) {
        return catchThrowableOfType(() -> throttle.checkAllowed(email, ip), TooManyLoginAttemptsException.class);
    }

    private void attempts(int count, String email, String ip) {
        for (int i = 0; i < count; i++) {
            throttle.checkAllowed(email, ip);
            throttle.recordFailure(email, ip);
        }
    }

    @Test
    void sixthAttemptForOneEmailFromOneAddress_isRefused_withTheRemainingSecondsOfTheWindow() {
        attempts(5, EMAIL, IP);
        clock.advance(Duration.ofMinutes(5));

        TooManyLoginAttemptsException refusal = refused(EMAIL, IP);

        assertThat(refusal).isNotNull();
        assertThat(refusal.getMessage()).isEqualTo("Too many sign-in attempts. Please try again later.");
        assertThat(refusal.getRetryAfterSeconds()).isEqualTo(600);   // 15 minutes from the first attempt, 5 gone
        assertThat(refusal.getBucket()).isEqualTo("auth-login-email-ip");
        assertThat(refusal.isAccountLocked()).isFalse();

        // Another address, and another email from the same address, are not affected.
        assertThatCode(() -> throttle.checkAllowed(EMAIL, "203.0.113.8")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.checkAllowed("someone.else@example.com", IP)).doesNotThrowAnyException();
    }

    @Test
    void windowEnds_fifteenMinutesAfterTheFirstAttempt() {
        attempts(5, EMAIL, IP);
        assertThat(refused(EMAIL, IP)).isNotNull();

        clock.advance(Duration.ofMinutes(15));

        assertThatCode(() -> throttle.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void verifiedPassword_clearsTheEmailCounts_butNotTheAddressCount() {
        attempts(4, EMAIL, IP);
        throttle.checkAllowed(EMAIL, IP);        // the fifth attempt carries the right password
        throttle.recordSuccess(EMAIL, IP);

        // A new count starts: five more attempts are allowed, the sixth is refused.
        attempts(5, EMAIL, IP);
        assertThat(refused(EMAIL, IP)).isNotNull();

        // The address has now made 11 attempts in total; its own bucket (30) kept counting through the success.
        for (int i = 0; i < 19; i++) {
            throttle.checkAllowed("user" + i + "@example.com", IP);
        }
        TooManyLoginAttemptsException refusal = refused("one.more@example.com", IP);
        assertThat(refusal).isNotNull();
        assertThat(refusal.getBucket()).isEqualTo("auth-login-ip");
    }

    @Test
    void oneAddressTryingManyEmails_isRefusedByTheAddressBucket() {
        for (int i = 0; i < 30; i++) {
            throttle.checkAllowed("user" + i + "@example.com", IP);
        }

        TooManyLoginAttemptsException refusal = refused("user31@example.com", IP);

        assertThat(refusal).isNotNull();
        assertThat(refusal.getBucket()).isEqualTo("auth-login-ip");
        assertThat(refusal.getRetryAfterSeconds()).isEqualTo(900);
        assertThat(refusal.isAccountLocked()).isFalse();
    }

    @Test
    void oneEmailTriedFromManyAddresses_locksTheAccountForTheHour() {
        for (int i = 0; i < 20; i++) {
            throttle.checkAllowed(EMAIL, "198.51.100." + i);
        }
        clock.advance(Duration.ofMinutes(10));

        TooManyLoginAttemptsException refusal = refused(EMAIL, "198.51.100.200");

        assertThat(refusal).isNotNull();
        assertThat(refusal.getBucket()).isEqualTo("auth-login-account");
        assertThat(refusal.isAccountLocked()).isTrue();
        assertThat(refusal.getRetryAfterSeconds()).isEqualTo(3000);   // one hour from the first attempt, 10 minutes gone
    }

    @Test
    void attemptsRefusedForAnAddress_areNotCountedAgainstTheAccount() {
        attempts(5, EMAIL, IP);
        // The blocked address keeps hammering: each attempt stops at the email+address bucket.
        for (int i = 0; i < 20; i++) {
            assertThat(refused(EMAIL, IP)).isNotNull();
        }

        // The account bucket (20 per hour) holds only the first five, so the owner is not locked out elsewhere.
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> throttle.checkAllowed(EMAIL, "198.51.100.9")).doesNotThrowAnyException();
        }
    }

    @Test
    void switchedOff_nothingIsCountedOrRefused() {
        properties.setEnabled(false);
        for (int i = 0; i < 100; i++) {
            throttle.checkAllowed(EMAIL, IP);
        }

        properties.setEnabled(true);
        assertThatCode(() -> attempts(5, EMAIL, IP)).doesNotThrowAnyException();
        assertThat(refused(EMAIL, IP)).isNotNull();
    }

    @Test
    void missingEmailOrAddress_doesNotBreakTheKeys() {
        assertThatCode(() -> {
            throttle.checkAllowed(null, null);
            throttle.recordFailure(null, null);
            throttle.recordSuccess(null, null);
        }).doesNotThrowAnyException();
    }
}
