package com.carenexus.api.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a developer profile (local, docker, test) is combined with
 * {@code carenexus.environment=staging} or {@code production}. Those profiles load demo users with published
 * passwords, committed development secrets and local provider implementations, none of which may ever serve a
 * shared or real environment.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EnvironmentStartupChecks {

    private final EnvironmentInfo environmentInfo;

    @PostConstruct
    void check() {
        String environment = environmentInfo.environment();
        boolean devProfile = environmentInfo.isDevProfile();
        if (devProfile && !EnvironmentInfo.DEVELOPMENT.equals(environment)) {
            throw new IllegalStateException("carenexus.environment=" + environment + " cannot be combined with the "
                    + "local, docker or test profile (they load demo accounts and development secrets). "
                    + "Remove the profile from SPRING_PROFILES_ACTIVE or set CARENEXUS_ENVIRONMENT=development.");
        }
        log.info("Environment: {} (developer profile: {})", environment, devProfile);
    }
}
