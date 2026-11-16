package com.carenexus.api.auth.support;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.UserLoggedInEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.common.spi.LoginGate;
import com.carenexus.api.common.spi.LoginStep;
import com.carenexus.api.common.spi.SessionFlagContributor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test doubles for the extension points of the auth module, the way later modules plug into them: two
 * {@link LoginGate}s, two {@link SessionFlagContributor}s and a listener of the auth events. Every double does
 * nothing until a test switches it on, and {@link #reset()} switches everything off again.
 *
 * Import it with {@code @Import(AuthTestHooks.class)}; the test classes that do share one application context.
 */
@TestConfiguration
public class AuthTestHooks {

    public static final String CHALLENGE_TOKEN = "fake-challenge-token-0123456789";
    public static final long CHALLENGE_TTL_SECONDS = 300;

    @Bean
    public FirstGate firstGate() {
        return new FirstGate();
    }

    @Bean
    public SecondGate secondGate() {
        return new SecondGate();
    }

    @Bean
    public TestFlags testFlags() {
        return new TestFlags();
    }

    @Bean
    public FailingFlags failingFlags() {
        return new FailingFlags();
    }

    @Bean
    public EventRecorder eventRecorder() {
        return new EventRecorder();
    }

    /** Runs first. Can refuse one email (like an email-verification gate) or challenge one (like an MFA gate). */
    public static class FirstGate implements LoginGate {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public final List<LoginContext> contexts = new CopyOnWriteArrayList<>();
        public volatile String challengeEmail;
        public volatile String refuseEmail;

        @Override
        public int getOrder() {
            return 100;
        }

        @Override
        public LoginStep afterPasswordVerified(User user, LoginContext ctx) {
            calls.add("first:" + user.getSystemId());
            contexts.add(ctx);
            if (user.getEmail() != null && user.getEmail().equals(refuseEmail)) {
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.EMAIL_NOT_VERIFIED,
                        "Verify your email address to continue");
            }
            if (user.getEmail() != null && user.getEmail().equals(challengeEmail)) {
                return LoginStep.challenge(LoginStep.MFA, CHALLENGE_TOKEN, CHALLENGE_TTL_SECONDS);
            }
            return LoginStep.proceed();
        }
    }

    /** Runs second and always lets the login continue; records that it ran. */
    public static class SecondGate implements LoginGate {
        public final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public int getOrder() {
            return 200;
        }

        @Override
        public LoginStep afterPasswordVerified(User user, LoginContext ctx) {
            calls.add("second:" + user.getSystemId());
            return LoginStep.proceed();
        }
    }

    /** Contributes {@code testFlag = true} while enabled. */
    public static class TestFlags implements SessionFlagContributor {
        public volatile boolean enabled;

        @Override
        public void contribute(User user, Map<String, Object> flags) {
            if (enabled) {
                flags.put("testFlag", true);
            }
        }
    }

    /** While enabled, writes a key and then fails, like a contributor whose lookup breaks half way. */
    public static class FailingFlags implements SessionFlagContributor {
        public volatile boolean enabled;

        @Override
        public void contribute(User user, Map<String, Object> flags) {
            if (enabled) {
                flags.put("brokenFlag", true);
                throw new IllegalStateException("flag lookup failed");
            }
        }
    }

    /** Records the auth events and can abort one registration, the way the legal-acceptance listener does. */
    public static class EventRecorder {
        public final List<UserRegisteredEvent> registered = new CopyOnWriteArrayList<>();
        public final List<UserLoggedInEvent> loggedIn = new CopyOnWriteArrayList<>();
        public final List<UserLoggedOutEvent> loggedOut = new CopyOnWriteArrayList<>();
        public final List<CaregiverGrantRecordedEvent> grants = new CopyOnWriteArrayList<>();
        public volatile String abortRegistrationOfEmail;

        @EventListener
        public void onRegistered(UserRegisteredEvent event) {
            registered.add(event);
            if (event.email() != null && event.email().equals(abortRegistrationOfEmail)) {
                throw new BadRequestException(ErrorResponse.LEGAL_ACCEPTANCE_REQUIRED,
                        "The terms or privacy policy changed. Review the current version and accept it.");
            }
        }

        @EventListener
        public void onLoggedIn(UserLoggedInEvent event) {
            loggedIn.add(event);
        }

        @EventListener
        public void onLoggedOut(UserLoggedOutEvent event) {
            loggedOut.add(event);
        }

        @EventListener
        public void onGrant(CaregiverGrantRecordedEvent event) {
            grants.add(event);
        }
    }

    /** Switches every double off and forgets what was recorded. */
    public static void reset(FirstGate first, SecondGate second, TestFlags flags, FailingFlags failing,
                             EventRecorder recorder) {
        first.calls.clear();
        first.contexts.clear();
        first.challengeEmail = null;
        first.refuseEmail = null;
        second.calls.clear();
        flags.enabled = false;
        failing.enabled = false;
        recorder.registered.clear();
        recorder.loggedIn.clear();
        recorder.loggedOut.clear();
        recorder.grants.clear();
        recorder.abortRegistrationOfEmail = null;
    }
}
