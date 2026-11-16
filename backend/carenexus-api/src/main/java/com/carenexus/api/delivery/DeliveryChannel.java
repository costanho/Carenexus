package com.carenexus.api.delivery;

import java.util.List;
import java.util.Locale;

/**
 * The external delivery channels ({@code outbound_messages.channel}). The in-app channel is not part of the outbox.
 */
public final class DeliveryChannel {

    public static final String EMAIL = "EMAIL";
    public static final String SMS = "SMS";
    public static final String PUSH = "PUSH";

    /** Fixed evaluation order. */
    public static final List<String> ALL = List.of(EMAIL, SMS, PUSH);

    private DeliveryChannel() {
    }

    /** Trimmed, upper-cased channel, or null when the value is not one of the three channels. */
    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String channel = value.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(channel) ? channel : null;
    }
}
