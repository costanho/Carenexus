package com.carenexus.api.account.email.repository;

import com.carenexus.api.account.email.model.EmailVerificationToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Integer> {

    /** The token with this hash, locked so two requests cannot both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM EmailVerificationToken t WHERE t.tokenHash = :tokenHash")
    Optional<EmailVerificationToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** Marks every outstanding token of the account as used (invalidated). */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmailVerificationToken t SET t.usedAt = :now WHERE t.systemId = :systemId AND t.usedAt IS NULL")
    int invalidateOutstanding(@Param("systemId") String systemId, @Param("now") Instant now);
}
