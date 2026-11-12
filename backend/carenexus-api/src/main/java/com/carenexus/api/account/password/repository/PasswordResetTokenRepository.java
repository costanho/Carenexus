package com.carenexus.api.account.password.repository;

import com.carenexus.api.account.password.model.PasswordResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Integer> {

    /**
     * The token with this hash, locked for the rest of the transaction so two concurrent submissions of one link
     * cannot both succeed (the second waits and then sees {@code used_at} set).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PasswordResetToken t WHERE t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Stamps {@code used_at} on every token of the user that has not been used yet (supersedes them).
     *
     * @return the number of tokens invalidated
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE PasswordResetToken t SET t.usedAt = :now WHERE t.systemId = :systemId AND t.usedAt IS NULL")
    int invalidateUnused(@Param("systemId") String systemId, @Param("now") LocalDateTime now);
}
