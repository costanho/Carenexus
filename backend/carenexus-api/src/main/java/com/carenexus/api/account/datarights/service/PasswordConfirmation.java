package com.carenexus.api.account.datarights.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Re-authentication of the three password-confirming requests (data export, closure, deletion request), in the order
 * of the API contract: the shared {@code password-change} bucket counts every call, then the password is compared
 * with the stored hash. A wrong password (or an account without one) is 400 {@code PASSWORD_INCORRECT}, never 401,
 * and writes one FAILURE audit row with the endpoint's own action.
 */
@Component
@RequiredArgsConstructor
public class PasswordConfirmation {

    private final RateLimitGuard rateLimitGuard;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    /** Counts one password confirmation for the caller; 429 RATE_LIMITED once the bucket is used up. */
    public void countAttempt(String callerSystemId) {
        rateLimitGuard.check(DataRightsConstants.BUCKET_PASSWORD, callerSystemId);
    }

    /**
     * @param action       audit action written with status FAILURE when the password is wrong
     * @param resourceType audit resource type of that row
     */
    public void verify(User user, String rawPassword, String action, String resourceType) {
        boolean matches = user.getPasswordHash() != null && rawPassword != null
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (!matches) {
            auditService.logAccountEvent(user.getSystemId(), action, resourceType, user.getSystemId(), "FAILURE");
            throw new BadRequestException(DataRightsConstants.CODE_PASSWORD_INCORRECT,
                    DataRightsConstants.MSG_PASSWORD_INCORRECT);
        }
    }
}
