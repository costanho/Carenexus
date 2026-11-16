package com.carenexus.api.account.mfa.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.common.spi.LoginGate;
import com.carenexus.api.common.spi.LoginStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Login gate of the MFA module, order {@value #ORDER} (after the email-verification gate, order 10).
 * MFA on -> challenge {@code MFA}; role in {@code carenexus.auth.mfa-required-roles} but not enrolled -> challenge
 * {@code MFA_SETUP}; otherwise the login proceeds. A challenge means no tokens: the session is issued by the
 * {@code /api/auth/mfa/**} endpoint that completes it.
 */
@Component
@RequiredArgsConstructor
public class MfaLoginGate implements LoginGate {

    public static final int ORDER = 50;

    private final MfaChallengeService challengeService;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public LoginStep afterPasswordVerified(User user, LoginContext ctx) {
        return challengeService.challengeFor(user, ctx);
    }
}
