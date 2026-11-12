package com.carenexus.api.common.event;

/**
 * Published by the admin module when an administrator starts a password reset for a user; the account module
 * handles it (issues the reset link and revokes the user's sessions).
 */
public record AdminPasswordResetRequestedEvent(
        String targetSystemId, String adminSystemId) {
}
