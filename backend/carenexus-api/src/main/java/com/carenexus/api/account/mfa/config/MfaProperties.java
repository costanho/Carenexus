package com.carenexus.api.account.mfa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MFA settings under {@code carenexus.auth} (application.yml):
 * <ul>
 *   <li>{@code mfa-required-roles}: comma-separated roles (PATIENT, DOCTOR, CAREGIVER, ADMIN) that cannot obtain a
 *       session without MFA. Default ADMIN; empty in the local, docker and test profiles.</li>
 *   <li>{@code mfa-encryption-key}: Base64 of a 32-byte AES key that encrypts TOTP secrets at rest. Developer
 *       profiles carry {@link #DEV_ENCRYPTION_KEY}, which {@code MfaStartupChecks} refuses anywhere else.</li>
 *   <li>{@code mfa-challenge-ttl-seconds}: lifetime of a sign-in challenge (default 300).</li>
 * </ul>
 * The bean is mutable on purpose: a test that needs forced enrolment sets the required roles and restores them.
 * Other {@code carenexus.auth.*} keys belong to other modules and are ignored here.
 */
@ConfigurationProperties(prefix = "carenexus.auth")
public class MfaProperties {

    /** The value committed for the local, docker and test profiles. Never valid outside them. */
    public static final String DEV_ENCRYPTION_KEY = "Y2FyZW5leHVzLWRldi1vbmx5LW1mYS1rZXktMzJieXQ=";

    /** Roles the setting may name (users.role values). */
    public static final Set<String> KNOWN_ROLES = Set.of("PATIENT", "DOCTOR", "CAREGIVER", "ADMIN");

    public static final long DEFAULT_CHALLENGE_TTL_SECONDS = 300;

    private volatile String mfaRequiredRoles = "ADMIN";
    private String mfaEncryptionKey;
    private long mfaChallengeTtlSeconds = DEFAULT_CHALLENGE_TTL_SECONDS;

    public String getMfaRequiredRoles() {
        return mfaRequiredRoles;
    }

    public void setMfaRequiredRoles(String mfaRequiredRoles) {
        this.mfaRequiredRoles = mfaRequiredRoles;
    }

    public String getMfaEncryptionKey() {
        return mfaEncryptionKey;
    }

    public void setMfaEncryptionKey(String mfaEncryptionKey) {
        this.mfaEncryptionKey = mfaEncryptionKey;
    }

    public long getMfaChallengeTtlSeconds() {
        return mfaChallengeTtlSeconds;
    }

    public void setMfaChallengeTtlSeconds(long mfaChallengeTtlSeconds) {
        this.mfaChallengeTtlSeconds = mfaChallengeTtlSeconds;
    }

    /** The configured roles, trimmed and upper case; empty when none is configured. */
    public Set<String> requiredRoles() {
        String value = mfaRequiredRoles;
        if (value == null || value.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(value.split(","))
                .map(role -> role.trim().toUpperCase(Locale.ROOT))
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when accounts with this role (users.role, any case) must use MFA. */
    public boolean isRequiredFor(String role) {
        return role != null && requiredRoles().contains(role.trim().toUpperCase(Locale.ROOT));
    }

    /** Challenge lifetime, never below one second. */
    public long challengeTtlSeconds() {
        return Math.max(1, mfaChallengeTtlSeconds);
    }

    /** The key is a secret: keep it out of logs. */
    @Override
    public String toString() {
        return "MfaProperties[mfaRequiredRoles=" + mfaRequiredRoles + ", mfaChallengeTtlSeconds="
                + mfaChallengeTtlSeconds + "]";
    }
}
