package com.carenexus.api.auth.repository;

import com.carenexus.api.auth.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Row-locks the token so two concurrent refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findBySystemIdAndIsRevokedFalse(String systemId);

    /** The active token(s) of one session of one user (a session has at most one token that is not revoked). */
    List<RefreshToken> findBySystemIdAndSessionIdAndIsRevokedFalse(String systemId, UUID sessionId);

    /**
     * True when the session holds a token issued after {@code tokenId}, which means that token was rotated.
     * Presenting a rotated token again is reuse; a revoked token that is still the newest of its session simply
     * belongs to a session that was ended.
     */
    boolean existsBySessionIdAndTokenIdGreaterThan(UUID sessionId, Integer tokenId);
}
