package com.carenexus.api.account.mfa.config;

import com.carenexus.api.account.mfa.crypto.MfaSecretCipher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Refuses to start with an MFA configuration that cannot work or is not safe:
 * <ul>
 *   <li>{@code carenexus.auth.mfa-encryption-key} missing, not Base64 or not 32 bytes (any profile);</li>
 *   <li>the committed development key ({@link MfaProperties#DEV_ENCRYPTION_KEY}) outside the local, docker and test
 *       profiles: its value is public, so secrets encrypted with it are not protected;</li>
 *   <li>{@code carenexus.auth.mfa-required-roles} naming a role that does not exist (a typo would silently leave
 *       administrators without mandatory MFA);</li>
 *   <li>{@code carenexus.auth.mfa-challenge-ttl-seconds} not positive.</li>
 * </ul>
 * Logs one WARN line when no role requires MFA outside the developer profiles.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MfaStartupChecks {

    private static final Profiles DEV_PROFILES = Profiles.of("local", "docker", "test");

    private final MfaProperties properties;
    private final Environment environment;

    @PostConstruct
    void check() {
        boolean devProfile = environment.acceptsProfiles(DEV_PROFILES);
        byte[] key = MfaSecretCipher.parseKey(properties.getMfaEncryptionKey());
        if (!devProfile && isDevelopmentKey(key)) {
            throw new IllegalStateException("carenexus.auth.mfa-encryption-key is the development value, which is only "
                    + "allowed in the local, docker and test profiles; set AUTH_MFA_ENCRYPTION_KEY to Base64 of 32 "
                    + "random bytes (for example `openssl rand -base64 32`)");
        }

        Set<String> unknown = new LinkedHashSet<>(properties.requiredRoles());
        unknown.removeAll(MfaProperties.KNOWN_ROLES);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("carenexus.auth.mfa-required-roles names unknown roles " + unknown
                    + "; allowed values are " + MfaProperties.KNOWN_ROLES + " (set AUTH_MFA_REQUIRED_ROLES)");
        }
        if (properties.getMfaChallengeTtlSeconds() <= 0) {
            throw new IllegalStateException("carenexus.auth.mfa-challenge-ttl-seconds must be positive "
                    + "(set AUTH_MFA_CHALLENGE_TTL_SECONDS)");
        }
        if (!devProfile && properties.requiredRoles().isEmpty()) {
            log.warn("carenexus.auth.mfa-required-roles is empty: no role is required to use two-factor authentication");
        }
    }

    private static boolean isDevelopmentKey(byte[] key) {
        byte[] dev = Base64.getDecoder().decode(MfaProperties.DEV_ENCRYPTION_KEY);
        return MessageDigest.isEqual(dev, key);
    }
}
