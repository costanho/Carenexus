package com.carenexus.api.account.mfa.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.spi.SessionFlagContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adds {@code mfaEnabled} (Boolean) to the session flags of login, refresh and {@code GET /api/users/me}: true when
 * the account's MFA is on. Left out when it cannot be computed (contributors never throw).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MfaSessionFlagContributor implements SessionFlagContributor {

    public static final String FLAG = "mfaEnabled";

    private final MfaAccountService accountService;

    @Override
    public void contribute(User user, Map<String, Object> flags) {
        if (user == null || user.getSystemId() == null) {
            return;
        }
        try {
            flags.put(FLAG, accountService.isEnabled(user.getSystemId()));
        } catch (RuntimeException e) {
            log.warn("Could not compute the {} flag: {}", FLAG, e.getClass().getSimpleName());
        }
    }
}
