package com.carenexus.api.common.event;

/**
 * Published by AuthService.completeLogin after a session was issued (login alerts, session list).
 */
public record UserLoggedInEvent(
        String systemId, String role, String ip, String deviceType, String deviceInfo) {
}
