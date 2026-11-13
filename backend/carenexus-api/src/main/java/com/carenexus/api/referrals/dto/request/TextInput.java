package com.carenexus.api.referrals.dto.request;

/**
 * Trimming rules shared by the referral request bodies: every string is trimmed; an optional string that is blank
 * after trimming becomes null.
 */
final class TextInput {

    private TextInput() {
    }

    /** Trimmed value; null stays null, an empty result stays empty (for fields that must reject "" themselves). */
    static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** Trimmed value, or null when nothing is left. */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
