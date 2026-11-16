package com.carenexus.api.common.event;

/**
 * Published after an account was deactivated. {@code cause} is one of the constants below; {@code actorSystemId} is
 * the administrator, or the user for a self-requested closure.
 */
public record UserDeactivatedEvent(
        String systemId, String role, String actorSystemId, String cause) {

    public static final String ADMIN = "ADMIN";
    public static final String CLOSURE = "CLOSURE";
    public static final String SUSPENSION = "SUSPENSION";
}
