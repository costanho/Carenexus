package com.carenexus.api.reminders.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;

/**
 * Trimming and patterns shared by the reminder request bodies. The "optional" patterns accept an empty string so a
 * blank value reports only {@code is required} (from {@code @NotBlank}) where the field is required.
 */
final class ReminderInput {

    static final String OPTIONAL_SYSTEM_ID = "^$|" + ValidationPatterns.SYSTEM_ID;
    static final String OPTIONAL_TIME = "^$|" + ValidationPatterns.TIME_HH_MM;
    /** At least one character (applied after trimming, so a blank value fails). */
    static final String NOT_BLANK = "(?s).+";

    private ReminderInput() {
    }

    /** Trimmed value; null stays null. */
    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
