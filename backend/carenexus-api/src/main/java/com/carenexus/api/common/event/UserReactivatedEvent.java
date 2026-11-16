package com.carenexus.api.common.event;

/**
 * Published by the admin module after an account was re-activated.
 */
public record UserReactivatedEvent(
        String systemId, String role, String actorSystemId) {
}
