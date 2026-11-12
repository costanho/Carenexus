package com.carenexus.api.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Where the application is running. Two independent facts:
 * <ul>
 *   <li>{@link #isDevProfile()}: one of the Spring profiles {@code local}, {@code docker}, {@code test} is active
 *       (developer machine, compose stack, automated tests);</li>
 *   <li>{@link #environment()}: {@code carenexus.environment} = {@code development}, {@code staging} or
 *       {@code production} (default {@code development}).</li>
 * </ul>
 * A dev profile combined with staging or production is refused at startup, see {@link EnvironmentStartupChecks}.
 */
@Component
public class EnvironmentInfo {

    public static final String DEVELOPMENT = "development";
    public static final String STAGING = "staging";
    public static final String PRODUCTION = "production";

    static final Set<String> ENVIRONMENTS = Set.of(DEVELOPMENT, STAGING, PRODUCTION);
    private static final Profiles DEV_PROFILES = Profiles.of("local", "docker", "test");

    private final Environment springEnvironment;
    private final String configuredEnvironment;
    private String environment;

    public EnvironmentInfo(Environment springEnvironment,
                           @Value("${carenexus.environment:development}") String configuredEnvironment) {
        this.springEnvironment = springEnvironment;
        this.configuredEnvironment = configuredEnvironment;
    }

    @PostConstruct
    void validate() {
        String value = configuredEnvironment == null ? "" : configuredEnvironment.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            value = DEVELOPMENT;
        }
        if (!ENVIRONMENTS.contains(value)) {
            throw new IllegalStateException("carenexus.environment must be development, staging or production (was '"
                    + configuredEnvironment + "'); set CARENEXUS_ENVIRONMENT");
        }
        this.environment = value;
    }

    /** True when the local, docker or test profile is active. */
    public boolean isDevProfile() {
        return springEnvironment.acceptsProfiles(DEV_PROFILES);
    }

    /** {@code development}, {@code staging} or {@code production}. */
    public String environment() {
        if (environment == null) {
            validate();
        }
        return environment;
    }

    public boolean isProduction() {
        return PRODUCTION.equals(environment());
    }
}
