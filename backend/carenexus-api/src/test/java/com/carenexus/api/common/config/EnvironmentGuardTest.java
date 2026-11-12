package com.carenexus.api.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentInfo, the dev-profile startup check and the LocalProviderGuard rule (no Spring context).
 */
public class EnvironmentGuardTest {

    private static EnvironmentInfo info(String environment, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        EnvironmentInfo info = new EnvironmentInfo(spring, environment);
        info.validate();
        return info;
    }

    // ==================== EnvironmentInfo ====================

    @Test
    void devProfiles_areLocalDockerAndTest() {
        assertTrue(info("development", "local").isDevProfile());
        assertTrue(info("development", "docker").isDevProfile());
        assertTrue(info("development", "test").isDevProfile());
        assertFalse(info("development").isDevProfile());
        assertFalse(info("production", "prod").isDevProfile());
    }

    @Test
    void environment_isNormalised_andDefaultsToDevelopment() {
        assertEquals("production", info(" Production ").environment());
        assertEquals("staging", info("STAGING").environment());
        assertEquals("development", info("").environment());
        assertEquals("development", info(null).environment());
        assertTrue(info("production").isProduction());
        assertFalse(info("staging").isProduction());
    }

    @Test
    void unknownEnvironment_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> info("prod"));
        assertTrue(ex.getMessage().contains("carenexus.environment"));
    }

    // ==================== EnvironmentStartupChecks ====================

    @Test
    void devProfileWithProductionOrStaging_failsStartup() {
        for (String environment : new String[]{"production", "staging"}) {
            for (String profile : new String[]{"local", "docker", "test"}) {
                EnvironmentStartupChecks checks = new EnvironmentStartupChecks(info(environment, profile));
                IllegalStateException ex = assertThrows(IllegalStateException.class, checks::check,
                        environment + " + " + profile);
                assertTrue(ex.getMessage().contains(environment));
            }
        }
    }

    @Test
    void otherCombinations_start() {
        assertDoesNotThrow(() -> new EnvironmentStartupChecks(info("development", "local")).check());
        assertDoesNotThrow(() -> new EnvironmentStartupChecks(info("development", "test")).check());
        assertDoesNotThrow(() -> new EnvironmentStartupChecks(info("development")).check());
        assertDoesNotThrow(() -> new EnvironmentStartupChecks(info("staging")).check());
        assertDoesNotThrow(() -> new EnvironmentStartupChecks(info("production")).check());
    }

    // ==================== LocalProviderGuard ====================

    @Test
    void realProvider_isAlwaysFine() {
        assertDoesNotThrow(() -> new LocalProviderGuard(info("production"), false).check("email", "ses", false));
    }

    @Test
    void localProvider_onDevProfile_isFine() {
        assertDoesNotThrow(() -> new LocalProviderGuard(info("development", "local"), false).check("email", "local", true));
        assertDoesNotThrow(() -> new LocalProviderGuard(info("development", "test"), false).check("storage", "local", true));
    }

    @Test
    void localProvider_inProduction_failsStartup_unlessExplicitlyAllowed() {
        LocalProviderGuard strict = new LocalProviderGuard(info("production"), false);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> strict.check("payments", "local", true));
        assertTrue(ex.getMessage().contains("payments"));
        assertTrue(ex.getMessage().contains("carenexus.allow-local-providers"));

        LocalProviderGuard allowed = new LocalProviderGuard(info("production"), true);
        assertDoesNotThrow(() -> allowed.check("payments", "local", true));
    }

    @Test
    void localProvider_inStagingOrUnprofiledDevelopment_onlyWarns() {
        assertDoesNotThrow(() -> new LocalProviderGuard(info("staging"), false).check("sms", "local", true));
        assertDoesNotThrow(() -> new LocalProviderGuard(info("development"), false).check("sms", "local", true));
    }
}
