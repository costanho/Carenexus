package com.carenexus.api.account.sessions.repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One active session from {@link SessionTokenRepository#findActiveSessions}.
 *
 * @param sessionId     the session id ({@code refresh_tokens.session_id})
 * @param firstTokenAt  creation time of the session's first stored token (the sign-in), clinic wall-clock
 * @param lastTokenAt   creation time of its newest token (the sign-in or the latest refresh), clinic wall-clock
 * @param newestTokenId id of the newest token, which carries the device fields shown in the list
 */
public record SessionSummary(UUID sessionId, LocalDateTime firstTokenAt, LocalDateTime lastTokenAt,
                             Integer newestTokenId) {
}
