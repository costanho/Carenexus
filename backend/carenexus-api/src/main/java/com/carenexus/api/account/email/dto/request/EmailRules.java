package com.carenexus.api.account.email.dto.request;

import java.util.Locale;

/** Email rules shared by the request bodies of this module (the same rule as registration). */
final class EmailRules {

    /** One @, a dot in the domain, at least two characters after the last dot, no spaces. */
    static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";
    static final String EMAIL_MESSAGE = "must be a valid email address";

    private EmailRules() {
    }

    /** Trimmed and lower-cased; null when null or blank, so a blank value reports only "must not be blank". */
    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
