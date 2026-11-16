package com.carenexus.api.delivery;

/**
 * Masks recipient addresses for log lines. Log lines of the delivery module carry the channel, the template id and
 * a masked recipient only, never the text of a message.
 */
public final class Redaction {

    private Redaction() {
    }

    /** {@code john.doe@example.com} becomes {@code j***@example.com}; a missing address becomes {@code -}. */
    public static String maskEmail(String address) {
        if (address == null || address.isBlank()) {
            return "-";
        }
        String value = address.trim();
        int at = value.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        return value.charAt(0) + "***" + value.substring(at);
    }

    /** {@code +263771234567} becomes {@code ***4567}; shorter values are fully masked. */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "-";
        }
        String value = phone.trim();
        return value.length() <= 6 ? "***" : "***" + value.substring(value.length() - 4);
    }
}
