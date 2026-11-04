package com.carenexus.api.common.event;

/**
 * Published by AuthService when a user logs out (one session or everywhere). Consumers end the user's open
 * call participation.
 */
public record UserLoggedOutEvent(String systemId) {
}
