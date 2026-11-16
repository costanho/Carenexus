package com.carenexus.api.account.sessions.repository;

import com.carenexus.api.auth.model.RefreshToken;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Read access to {@code refresh_tokens} for the session features (session list, session look-ups, login alert).
 * The table and its writes belong to the auth module; this repository never changes a row.
 *
 * A session is the chain of refresh tokens that share one {@code session_id}. It is <em>active</em> while it holds a
 * token that is not revoked and has not expired.
 */
public interface SessionTokenRepository extends Repository<RefreshToken, Integer> {

    /**
     * The user's active sessions, one row per session: first and last token creation time and the id of the newest
     * token. Ordered with {@code current} first, then most recently used, ties by session id.
     *
     * @param current a session id to put first; pass a value that matches nothing when there is none
     * @param pageable the cap (first page only)
     */
    @Query("""
            SELECT new com.carenexus.api.account.sessions.repository.SessionSummary(
                       rt.sessionId, MIN(rt.createdAt), MAX(rt.createdAt), MAX(rt.tokenId))
              FROM RefreshToken rt
             WHERE rt.systemId = :systemId
             GROUP BY rt.sessionId
            HAVING SUM(CASE WHEN rt.isRevoked = false AND rt.expiresAt > :now THEN 1 ELSE 0 END) > 0
             ORDER BY CASE WHEN rt.sessionId = :current THEN 0 ELSE 1 END,
                      MAX(rt.createdAt) DESC,
                      rt.sessionId ASC
            """)
    List<SessionSummary> findActiveSessions(@Param("systemId") String systemId, @Param("now") LocalDateTime now,
                                            @Param("current") UUID current, Pageable pageable);

    /** The token rows with the given ids (device fields of the newest token of each listed session). */
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenId IN :tokenIds")
    List<RefreshToken> findByTokenIds(@Param("tokenIds") Collection<Integer> tokenIds);

    /** True when {@code sessionId} is an active session of {@code systemId}. */
    @Query("""
            SELECT COUNT(rt) > 0
              FROM RefreshToken rt
             WHERE rt.systemId = :systemId
               AND rt.sessionId = :sessionId
               AND rt.isRevoked = false
               AND rt.expiresAt > :now
            """)
    boolean isActiveSession(@Param("systemId") String systemId, @Param("sessionId") UUID sessionId,
                            @Param("now") LocalDateTime now);

    /**
     * The first token of every stored session of the user (active or ended), newest session first. The device
     * fields are copied on every rotation, so the first token describes the device of the whole session.
     */
    @Query("""
            SELECT new com.carenexus.api.account.sessions.repository.SessionDevice(
                       rt.sessionId, rt.tokenId, rt.deviceType, rt.deviceInfo)
              FROM RefreshToken rt
             WHERE rt.systemId = :systemId
               AND rt.tokenId = (SELECT MIN(f.tokenId) FROM RefreshToken f WHERE f.sessionId = rt.sessionId)
             ORDER BY rt.tokenId DESC
            """)
    List<SessionDevice> findSessionDevices(@Param("systemId") String systemId, Pageable pageable);
}
