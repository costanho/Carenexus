package com.carenexus.api.account.password.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.spi.SessionFlagContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Session flag {@code mustChangePassword}: always present as a JSON boolean, equal to
 * {@code users.must_change_password}. The web sends a user with the flag set to {@code /auth/change-password}
 * before any dashboard screen; a successful reset or change clears it.
 */
@Component
public class MustChangePasswordFlagContributor implements SessionFlagContributor {

    public static final String FLAG = "mustChangePassword";

    @Override
    public void contribute(User user, Map<String, Object> flags) {
        flags.put(FLAG, user != null && Boolean.TRUE.equals(user.getMustChangePassword()));
    }
}
