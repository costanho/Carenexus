package com.carenexus.api.account.sessions.service;

import com.carenexus.api.account.sessions.dto.response.RevokeOtherSessionsResponse;
import com.carenexus.api.account.sessions.dto.response.SessionResponse;
import com.carenexus.api.account.sessions.repository.SessionSummary;
import com.carenexus.api.account.sessions.repository.SessionTokenRepository;
import com.carenexus.api.auth.model.RefreshToken;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The caller's own sign-in sessions: list them, end one, end all others.
 *
 * Every method acts on the authenticated caller only (identity from the access token); any role may call it and an
 * administrator has no extra reach here. A session is identified by its session id ({@code tokens.sessionId}),
 * never by a token row id. Ending a session revokes its refresh token through {@link AuthService}; the access token
 * that device already holds keeps working until it expires.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SessionService {

    /** Audit resource type and action of an ended session. */
    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_SESSION_REVOKE = "SESSION_REVOKE";

    /** Hard cap of the session list: the current session plus the 49 most recently used. */
    public static final int MAX_SESSIONS = 50;

    static final String SESSION_NOT_FOUND = "Session not found";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Never a real session id (random UUIDs are version 4): orders nothing first when no current id is known. */
    private static final UUID NO_SESSION = new UUID(0L, 0L);

    private final SessionTokenRepository sessionTokenRepository;
    private final AuthService authService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * The caller's active sessions, current first, then most recently used (cap {@link #MAX_SESSIONS}).
     *
     * @param current the caller's own session id; trimmed and compared case-insensitively. A value that matches
     *                nothing (malformed, ended, another user's, blank) marks nothing and is not an error.
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> listMySessions(String current) {
        String caller = authorizationService.currentSystemId();
        UUID currentId = parse(current).orElse(null);

        List<SessionSummary> sessions = sessionTokenRepository.findActiveSessions(caller, now(),
                currentId != null ? currentId : NO_SESSION, PageRequest.of(0, MAX_SESSIONS));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Integer, RefreshToken> newest = sessionTokenRepository
                .findByTokenIds(sessions.stream().map(SessionSummary::newestTokenId).toList())
                .stream()
                .collect(Collectors.toMap(RefreshToken::getTokenId, Function.identity()));

        return sessions.stream()
                .map(session -> {
                    RefreshToken token = newest.get(session.newestTokenId());
                    return new SessionResponse(
                            session.sessionId().toString(),
                            token != null ? token.getDeviceType() : null,
                            token != null ? token.getDeviceInfo() : null,
                            toInstant(session.firstTokenAt()),
                            toInstant(session.lastTokenAt()),
                            session.sessionId().equals(currentId));
                })
                .toList();
    }

    /**
     * End one of the caller's active sessions. Publishes no event; writes one {@code SESSION_REVOKE} audit row.
     *
     * @throws ResourceNotFoundException "Session not found" when the id is not a UUID or not an active session of
     *                                   the caller (unknown, another user's, ended, expired)
     */
    public void revokeMySession(String sessionId) {
        String caller = authorizationService.currentSystemId();
        UUID id = parse(sessionId).orElseThrow(() -> new ResourceNotFoundException(SESSION_NOT_FOUND));
        if (!sessionTokenRepository.isActiveSession(caller, id, now())) {
            throw new ResourceNotFoundException(SESSION_NOT_FOUND);
        }
        if (!authService.revokeSession(caller, id.toString())) {
            // Ended meanwhile by another request
            throw new ResourceNotFoundException(SESSION_NOT_FOUND);
        }
        auditService.logAccountEvent(caller, ACTION_SESSION_REVOKE, RESOURCE, caller, "SUCCESS");
    }

    /**
     * End every active session of the caller except {@code keepSessionId}, which must itself be an active session
     * of the caller (otherwise nothing is revoked). Writes one {@code SESSION_REVOKE} audit row when at least one
     * session ended. Publishes no event.
     *
     * @param keepSessionId a UUID (already validated by the request DTO; surrounding white space is ignored)
     * @throws ResourceNotFoundException "Session not found" when the keep id is not an active session of the caller
     */
    public RevokeOtherSessionsResponse revokeMyOtherSessions(String keepSessionId) {
        String caller = authorizationService.currentSystemId();
        UUID keep = parse(keepSessionId).orElseThrow(() -> new ResourceNotFoundException(SESSION_NOT_FOUND));
        if (!sessionTokenRepository.isActiveSession(caller, keep, now())) {
            throw new ResourceNotFoundException(SESSION_NOT_FOUND);
        }
        int revoked = authService.revokeOtherSessions(caller, keep.toString());
        if (revoked > 0) {
            auditService.logAccountEvent(caller, ACTION_SESSION_REVOKE, RESOURCE, caller, "SUCCESS");
        }
        return new RevokeOtherSessionsResponse(revoked);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** refresh_tokens stores clinic wall-clock times; the JVM default zone is the clinic zone. */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    static Optional<UUID> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        if (!UUID_PATTERN.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(trimmed));
    }
}
