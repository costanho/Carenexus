package com.carenexus.api.admin.user.dto.request;

import java.util.Locale;

/**
 * Normalisation applied by the admin request records before Bean Validation runs, so the constraints see the value
 * the service stores: text is trimmed and a blank value counts as absent (null). Passwords never pass through here.
 */
public final class AdminText {

    private AdminText() {
    }

    /** Trimmed text, or null when the value is null or blank. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trimmed and lower-cased (emails), or null when blank. */
    public static String cleanLower(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    /** Trimmed and upper-cased (codes), or null when blank. */
    public static String cleanUpper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }
}
