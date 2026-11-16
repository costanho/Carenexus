package com.carenexus.api.account.mfa;

import com.carenexus.api.account.mfa.config.MfaProperties;
import com.carenexus.api.account.mfa.config.MfaStartupChecks;
import com.carenexus.api.account.mfa.crypto.MfaSecretCipher;
import com.carenexus.api.account.mfa.crypto.MfaTokens;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secret encryption, tokens, recovery codes and the startup checks of the MFA module (no Spring context). */
class MfaCryptoTest {

    private static final String PRODUCTION_LIKE_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    private static MfaProperties properties(String key, String roles) {
        MfaProperties properties = new MfaProperties();
        properties.setMfaEncryptionKey(key);
        properties.setMfaRequiredRoles(roles);
        return properties;
    }

    // ==================== CIPHER ====================

    @Test
    void cipher_roundTrips_withARandomIvEachTime() {
        MfaSecretCipher cipher = new MfaSecretCipher(properties(PRODUCTION_LIKE_KEY, "ADMIN"));
        byte[] secret = "twenty-byte-secret!!".getBytes(StandardCharsets.US_ASCII);

        String first = cipher.encrypt(secret, "ADM-00001");
        String second = cipher.encrypt(secret, "ADM-00001");

        assertTrue(first.startsWith("v1:"));
        assertNotEquals(first, second, "a fresh IV per encryption");
        assertFalse(first.contains("twenty"));
        assertTrue(first.length() <= 255, "fits the column");
        assertArrayEquals(secret, cipher.decrypt(first, "ADM-00001"));
        assertArrayEquals(secret, cipher.decrypt(second, "ADM-00001"));
    }

    @Test
    void cipher_refusesAnotherAccount_aTamperedValue_andAnotherKey() {
        MfaSecretCipher cipher = new MfaSecretCipher(properties(PRODUCTION_LIKE_KEY, "ADMIN"));
        String stored = cipher.encrypt(new byte[20], "PAT-00001");

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(stored, "PAT-00002"));

        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered, "PAT-00001"));

        MfaSecretCipher other = new MfaSecretCipher(properties(MfaProperties.DEV_ENCRYPTION_KEY, "ADMIN"));
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored, "PAT-00001"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("plain-text", "PAT-00001"));
    }

    @Test
    void cipher_needsA32ByteBase64Key() {
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(properties(null, "")));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(properties(" ", "")));
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(properties("not base64 !", "")));
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new MfaSecretCipher(properties(sixteenBytes, "")));
        assertEquals(32, Base64.getDecoder().decode(MfaProperties.DEV_ENCRYPTION_KEY).length);
    }

    // ==================== TOKENS AND RECOVERY CODES ====================

    @Test
    void challengeTokens_are43UrlSafeCharacters_storedAsSha256Hex() {
        String token = MfaTokens.newChallengeToken();
        assertTrue(token.matches("^[A-Za-z0-9_-]{43}$"), token);
        assertNotEquals(token, MfaTokens.newChallengeToken());
        String hash = MfaTokens.sha256Hex(token);
        assertTrue(hash.matches("^[0-9a-f]{64}$"));
        assertEquals(hash, MfaTokens.sha256Hex(token));
    }

    @Test
    void recoveryCodes_areTenDistinctCodesInDisplayForm() {
        List<String> codes = MfaTokens.newRecoveryCodes();
        assertEquals(10, codes.size());
        assertEquals(10, new HashSet<>(codes).size());
        codes.forEach(code -> assertTrue(code.matches(MfaFixtures.RECOVERY_CODE_REGEX), code));
    }

    @Test
    void recoveryCodeInput_isNormalisedBeforeHashing() {
        String hash = MfaTokens.recoveryCodeHash("K7QMP-2XWRT");
        assertEquals(hash, MfaTokens.recoveryCodeHash("k7qmp2xwrt"));
        assertEquals(hash, MfaTokens.recoveryCodeHash(" K7QMP-2xwrt "));
        assertEquals(MfaTokens.sha256Hex("K7QMP2XWRT"), hash);
        assertNull(MfaTokens.recoveryCodeHash("K7QMP--2XWRT"));
        assertNull(MfaTokens.recoveryCodeHash("K7QMP"));
        assertNull(MfaTokens.recoveryCodeHash(null));
    }

    // ==================== PROPERTIES ====================

    @Test
    void requiredRoles_areParsedCaseInsensitively() {
        MfaProperties properties = properties(PRODUCTION_LIKE_KEY, " admin , Doctor,,");
        assertEquals(List.of("ADMIN", "DOCTOR"), List.copyOf(properties.requiredRoles()));
        assertTrue(properties.isRequiredFor("ADMIN"));
        assertTrue(properties.isRequiredFor("doctor"));
        assertFalse(properties.isRequiredFor("PATIENT"));
        assertFalse(properties.isRequiredFor(null));
        assertTrue(properties(PRODUCTION_LIKE_KEY, "").requiredRoles().isEmpty());
        assertFalse(properties.toString().contains(PRODUCTION_LIKE_KEY), "the key stays out of toString");
    }

    // ==================== STARTUP CHECKS ====================

    private static void runChecks(MfaProperties properties, String... profiles) throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        MfaStartupChecks checks = new MfaStartupChecks(properties, environment);
        Method check = MfaStartupChecks.class.getDeclaredMethod("check");
        check.setAccessible(true);
        try {
            check.invoke(checks);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    @Test
    void startupChecks_allowTheDevelopmentKeyOnlyInDeveloperProfiles() {
        assertDoesNotThrow(() -> runChecks(properties(MfaProperties.DEV_ENCRYPTION_KEY, ""), "local"));
        assertDoesNotThrow(() -> runChecks(properties(MfaProperties.DEV_ENCRYPTION_KEY, ""), "docker"));
        assertDoesNotThrow(() -> runChecks(properties(MfaProperties.DEV_ENCRYPTION_KEY, ""), "test"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> runChecks(properties(MfaProperties.DEV_ENCRYPTION_KEY, "ADMIN"), "prod"));
        assertTrue(refused.getMessage().contains("development value"));
        assertFalse(refused.getMessage().contains(MfaProperties.DEV_ENCRYPTION_KEY), "never echoes the key");
        assertThrows(IllegalStateException.class,
                () -> runChecks(properties(" " + MfaProperties.DEV_ENCRYPTION_KEY + " ", "ADMIN")));

        assertDoesNotThrow(() -> runChecks(properties(PRODUCTION_LIKE_KEY, "ADMIN"), "prod"));
        assertDoesNotThrow(() -> runChecks(properties(PRODUCTION_LIKE_KEY, "")));
    }

    @Test
    void startupChecks_refuseAMissingKey_unknownRoles_andANonPositiveTtl() {
        assertThrows(IllegalStateException.class, () -> runChecks(properties("", "ADMIN"), "prod"));
        assertThrows(IllegalStateException.class, () -> runChecks(properties(null, ""), "test"));

        IllegalStateException unknownRole = assertThrows(IllegalStateException.class,
                () -> runChecks(properties(PRODUCTION_LIKE_KEY, "ADMIN,ADMINS"), "prod"));
        assertTrue(unknownRole.getMessage().contains("ADMINS"));

        MfaProperties zeroTtl = properties(PRODUCTION_LIKE_KEY, "ADMIN");
        zeroTtl.setMfaChallengeTtlSeconds(0);
        assertThrows(IllegalStateException.class, () -> runChecks(zeroTtl, "prod"));
    }
}
