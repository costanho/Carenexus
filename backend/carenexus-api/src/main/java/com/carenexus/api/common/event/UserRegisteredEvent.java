package com.carenexus.api.common.event;

/**
 * Published by AuthService.register and createActiveUserAccount inside the registering transaction. A plain
 * {@code @EventListener} runs in that transaction and may throw to abort the registration (legal acceptance,
 * verification email). {@code createdBySystemId} is null for self-registration and the caregiver or admin who
 * created the account otherwise; the accepted versions, {@code ip} and {@code userAgent} may be null then.
 */
public record UserRegisteredEvent(
        String systemId, String role, String email, String acceptedTermsVersion, String acceptedPrivacyVersion,
        String ip, String userAgent, String createdBySystemId) {

    /** The email and client address are personal data: keep them out of logs. */
    @Override
    public String toString() {
        return "UserRegisteredEvent[systemId=" + systemId + ", role=" + role + "]";
    }
}
