package com.carenexus.api.terminology.provider;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The ICD-10 code format accepted by CareNexus: a letter, a digit, a digit or letter, optionally a dot and one to
 * four letters or digits ({@code I10}, {@code J06.9}, {@code E11.65}, {@code S52.501A}). Codes are compared and
 * stored trimmed and upper-cased.
 */
public final class IcdCodeFormat {

    /** The accepted form, after trimming and upper-casing. */
    public static final String PATTERN = "^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$";

    private static final Pattern CODE = Pattern.compile(PATTERN);

    private IcdCodeFormat() {
    }

    /** Trimmed, upper-cased code; null stays null. */
    public static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** True when the normalised code matches {@link #PATTERN}. */
    public static boolean isWellFormed(String code) {
        String normalized = normalize(code);
        return normalized != null && CODE.matcher(normalized).matches();
    }

    /** The code without its dot, upper-cased ({@code j06.9} becomes {@code J069}); used for prefix matching. */
    public static String compact(String code) {
        String normalized = normalize(code);
        return normalized == null ? "" : normalized.replace(".", "");
    }
}
