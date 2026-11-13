package com.carenexus.api.common.spi;

import com.carenexus.api.auth.model.User;

import java.util.Map;

/**
 * Adds entries to the {@code flags} map returned with a session ({@code AuthResponse.flags}, also on
 * {@code GET /api/users/me}). Each module contributes the flags it owns: {@code mustChangePassword},
 * {@code emailVerified}, {@code legalActionRequired}, {@code mfaEnabled}. Values must be JSON-serialisable
 * (Boolean, String, Number). Implementations must not throw: a flag that cannot be computed is left out.
 */
public interface SessionFlagContributor {

    void contribute(User user, Map<String, Object> flags);
}
