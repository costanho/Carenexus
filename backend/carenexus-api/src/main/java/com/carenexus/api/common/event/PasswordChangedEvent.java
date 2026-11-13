package com.carenexus.api.common.event;

/**
 * Published by the account module after a password was changed. {@code kind} is one of the constants below.
 */
public record PasswordChangedEvent(
        String systemId, String kind) {

    public static final String RESET = "RESET";
    public static final String CHANGE = "CHANGE";
    public static final String ADMIN_RESET = "ADMIN_RESET";
}
