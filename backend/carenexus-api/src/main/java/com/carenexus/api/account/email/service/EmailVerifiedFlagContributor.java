package com.carenexus.api.account.email.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.spi.SessionFlagContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Session flag {@code emailVerified} (login, refresh, {@code GET /api/users/me}): true when the account's email was
 * confirmed. Informational only; it never blocks a session.
 */
@Component
public class EmailVerifiedFlagContributor implements SessionFlagContributor {

    public static final String FLAG = "emailVerified";

    @Override
    public void contribute(User user, Map<String, Object> flags) {
        flags.put(FLAG, user != null && user.getEmailVerifiedAt() != null);
    }
}
