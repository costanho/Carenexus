package com.carenexus.api.common.config;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.common.ratelimit.InMemoryRateLimiter;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The configuration the application actually starts with in the test profile: application.yml binds, the
 * foundation beans are wired, and the test profile switches off what tests must drive themselves.
 */
public class ApplicationConfigurationTest extends BaseIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    @Autowired
    private EnvironmentInfo environmentInfo;

    @Test
    void rateLimitBuckets_bindFromApplicationYml_withTheContractValues() {
        assertInstanceOf(InMemoryRateLimiter.class, rateLimiter);
        assertEquals("memory", rateLimitProperties.getStore());
        // every predefined bucket is written in application.yml
        for (String bucket : List.of("default", "auth-login-email-ip", "auth-login-ip", "auth-login-account",
                "auth-register-ip", "auth-refresh-ip", "auth-forgot-email", "auth-forgot-ip", "auth-verify-ip", "auth-mfa",
                "password-change", "appointment-book", "caregiver-request", "reminder-manual", "document-upload",
                "payment-initiate", "data-export", "correction-request", "message-send", "thread-create")) {
            assertTrue(rateLimitProperties.getBuckets().containsKey(bucket), bucket + " is missing from application.yml");
        }
        assertEquals(new RateLimitProperties.Limit(120, 60), rateLimitProperties.resolve("default"));
        assertEquals(new RateLimitProperties.Limit(5, 900), rateLimitProperties.resolve("auth-login-email-ip"));
        assertEquals(new RateLimitProperties.Limit(3, 86400), rateLimitProperties.resolve("data-export"));
        assertEquals(new RateLimitProperties.Limit(10, 86400), rateLimitProperties.resolve("correction-request"));
        assertEquals(new RateLimitProperties.Limit(60, 60), rateLimitProperties.resolve("message-send"));
        assertEquals(new RateLimitProperties.Limit(20, 3600), rateLimitProperties.resolve("thread-create"));
    }

    @Test
    void rateLimitingIsOffInTheTestProfile_andCanBeSwitchedOnByATest() {
        assertFalse(rateLimitProperties.isEnabled(), "application-test.yml must disable rate limiting");
        String key = "application-configuration-test";
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> rateLimitGuard.check("auth-forgot-email", key));   // limit is 3
        }

        rateLimitProperties.setEnabled(true);
        try {
            for (int i = 0; i < 3; i++) {
                rateLimitGuard.check("auth-forgot-email", key);
            }
            assertThrows(com.carenexus.api.common.exception.TooManyRequestsException.class,
                    () -> rateLimitGuard.check("auth-forgot-email", key));
        } finally {
            rateLimitGuard.reset("auth-forgot-email", key);
            rateLimitProperties.setEnabled(false);
        }
    }

    @Test
    void testProfile_switchesOffJobsEmailVerificationAndMfa() {
        assertEquals("false", environment.getProperty("carenexus.jobs.enabled"));
        assertEquals("false", environment.getProperty("carenexus.auth.require-verified-email"));
        assertEquals("", environment.getProperty("carenexus.auth.mfa-required-roles"));
        assertTrue(environmentInfo.isDevProfile());
        assertEquals("development", environmentInfo.environment());
    }

    @Test
    void sharedDefaults_comeFromApplicationYml() {
        assertEquals("graceful", environment.getProperty("server.shutdown"));
        assertEquals("10MB", environment.getProperty("spring.servlet.multipart.max-file-size"));
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", environment.getProperty("carenexus.allow-local-providers"));
        assertEquals("local", environment.getProperty("carenexus.delivery.email.provider"));
        assertEquals("local", environment.getProperty("carenexus.delivery.sms.provider"));
        assertEquals("local", environment.getProperty("carenexus.delivery.push.provider"));
        assertEquals("local", environment.getProperty("carenexus.storage.provider"));
        assertEquals("local", environment.getProperty("carenexus.storage.scanner"));
        assertTrue(String.valueOf(environment.getProperty("carenexus.storage.local-dir")).endsWith("carenexus-storage"));
        assertEquals("local", environment.getProperty("carenexus.billing.provider"));
        assertEquals("manual", environment.getProperty("carenexus.billing.claims-provider"));
        assertEquals("KES", environment.getProperty("carenexus.billing.default-currency"));
        assertEquals("rules", environment.getProperty("carenexus.ai.provider"));
        assertEquals("rules", environment.getProperty("carenexus.ai.interaction-provider"));
        assertEquals("disabled", environment.getProperty("carenexus.health.wearables.provider"));
        assertEquals("bundled", environment.getProperty("carenexus.terminology.provider"));
        assertEquals("30", environment.getProperty("carenexus.retention.tokens-days"));
        assertEquals("http://localhost:8081", environment.getProperty("carenexus.web.base-url"));
        assertTrue(String.valueOf(environment.getProperty("management.endpoints.web.exposure.include")).contains("health"));
    }
}
