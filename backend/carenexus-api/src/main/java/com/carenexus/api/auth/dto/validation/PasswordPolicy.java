package com.carenexus.api.auth.dto.validation;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The one password rule of the application: at least 8 characters with at least one letter and one digit, and at
 * most 72 bytes in UTF-8 (BCrypt reads no more, so longer input is refused instead of silently cut).
 * Used by the {@link StrongPassword} constraint on request DTOs and by services that receive a password directly.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    public static final String REQUIRED = "Password is required";
    public static final String TOO_WEAK =
            "Password must be at least 8 characters and contain at least one letter and one number";
    public static final String TOO_LONG = "Password must be at most 72 characters";

    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private PasswordPolicy() {
    }

    /**
     * @return null when the password is acceptable, otherwise the sentence to show ({@link #REQUIRED},
     *         {@link #TOO_WEAK} or {@link #TOO_LONG}); exactly one sentence, the first rule that fails
     */
    public static String violation(String password) {
        if (password == null || password.isBlank()) {
            return REQUIRED;
        }
        if (password.length() < MIN_LENGTH || !HAS_LETTER.matcher(password).find() || !HAS_DIGIT.matcher(password).find()) {
            return TOO_WEAK;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return TOO_LONG;
        }
        return null;
    }
}
