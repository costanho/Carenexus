package com.carenexus.api.account.email.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One-time tokens of the email flows: 256 bits from {@link SecureRandom}, URL-safe base64 without padding
 * (43 characters). Only {@link #hash(String)} is stored. Tokens are never logged.
 */
final class OneTimeTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Same rule as registration: one @, a dot in the domain, no spaces. */
    static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private OneTimeTokens() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the token as sent (trimmed). */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Trimmed, lower-case address, or null when blank. */
    static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** First name for a mail greeting; never blank. */
    static String greetingName(String firstName) {
        return firstName == null || firstName.isBlank() ? "there" : firstName.trim();
    }
}
