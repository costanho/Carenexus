package com.carenexus.api.account.legal.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.spi.SessionFlagContributor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adds {@code legalActionRequired} to the session flags (login, refresh, {@code GET /api/users/me}): true while the
 * account has not accepted the current version of the terms or of the privacy policy. Recomputed every time, for
 * every role. The flag is advisory: the web sends the user to the acceptance screen; the API refuses nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LegalSessionFlagContributor implements SessionFlagContributor {

    public static final String FLAG = "legalActionRequired";

    private final LegalService legalService;

    @Override
    public void contribute(User user, Map<String, Object> flags) {
        if (user == null || user.getSystemId() == null) {
            return;
        }
        try {
            flags.put(FLAG, legalService.isActionRequired(user.getSystemId()));
        } catch (RuntimeException e) {
            // Never fail a sign-in because of this flag: leave it out.
            log.warn("Could not compute {} for {}: {}", FLAG, user.getSystemId(), e.getClass().getSimpleName());
        }
    }
}
