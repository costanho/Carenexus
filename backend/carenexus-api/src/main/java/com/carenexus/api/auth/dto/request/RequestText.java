package com.carenexus.api.auth.dto.request;

import java.util.Locale;

/**
 * Normalisation applied by the setters of the auth request DTOs, so Bean Validation sees the value the service
 * will use: text is trimmed and a blank value counts as absent (null). Passwords and tokens are never passed
 * through here; they are used exactly as sent.
 */
final class RequestText {

    private RequestText() {
    }

    /** Trimmed text, or null when the value is null or blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trimmed and lower-cased (emails), or null when blank. */
    static String cleanLower(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    /** Trimmed and upper-cased (country codes), or null when blank. */
    static String cleanUpper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }
}
