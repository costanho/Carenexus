package com.carenexus.api.account.email.repository;

import com.carenexus.api.account.email.model.EmailChangeRequest;
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
public interface EmailChangeRequestRepository extends JpaRepository<EmailChangeRequest, Integer> {

    /** The request with this token hash, locked so two confirmations cannot both apply it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM EmailChangeRequest r WHERE r.tokenHash = :tokenHash")
    Optional<EmailChangeRequest> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** The account's open request (at most one exists, enforced by a partial unique index); it may have expired. */
    Optional<EmailChangeRequest> findFirstBySystemIdAndUsedAtIsNullAndCancelledAtIsNullOrderByRequestIdDesc(
            String systemId);

    /** Cancels the account's open request(s), expired ones included. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmailChangeRequest r SET r.cancelledAt = :now "
            + "WHERE r.systemId = :systemId AND r.usedAt IS NULL AND r.cancelledAt IS NULL")
    int cancelOpen(@Param("systemId") String systemId, @Param("now") Instant now);

    /** Cancels the account's open request(s) that have not expired yet; returns how many were pending. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE EmailChangeRequest r SET r.cancelledAt = :now "
            + "WHERE r.systemId = :systemId AND r.usedAt IS NULL AND r.cancelledAt IS NULL AND r.expiresAt > :now")
    int cancelPending(@Param("systemId") String systemId, @Param("now") Instant now);
}
