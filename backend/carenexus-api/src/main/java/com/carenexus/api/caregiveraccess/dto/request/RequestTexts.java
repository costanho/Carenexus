package com.carenexus.api.caregiveraccess.dto.request;

import java.util.Locale;

/**
 * Normalisation used by the setters of this module's request DTOs, so Bean Validation sees the value the service
 * will use: text is trimmed and a blank value counts as absent (null).
 */
final class RequestTexts {

    private RequestTexts() {
    }

    /** Trimmed text, or null when the value is null or blank. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Trimmed and upper-cased (enum values), or null when blank. */
    static String cleanUpper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }
}
