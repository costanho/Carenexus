package com.carenexus.api.account.datarights.repository;

import com.carenexus.api.account.datarights.model.AccountRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRequestRepository extends JpaRepository<AccountRequest, Integer> {

    /** A user's requests of one type, newest first (callers pass a page of the hard cap). */
    @Query("SELECT r FROM AccountRequest r WHERE r.systemId = :systemId AND r.type = :type "
            + "ORDER BY r.requestedAt DESC, r.requestId DESC")
    List<AccountRequest> findOwn(@Param("systemId") String systemId, @Param("type") String type, Pageable pageable);

    boolean existsBySystemIdAndTypeAndStatusIn(String systemId, String type, Collection<String> statuses);

    /** The user's earlier exports that are still downloadable (status READY), oldest first. */
    @Query("SELECT r FROM AccountRequest r WHERE r.systemId = :systemId AND r.type = 'EXPORT' "
            + "AND r.status = 'READY' AND r.requestId <> :keepId ORDER BY r.requestId")
    List<AccountRequest> findReadyExportsExcept(@Param("systemId") String systemId, @Param("keepId") Integer keepId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AccountRequest r WHERE r.requestId = :requestId")
    Optional<AccountRequest> findByIdForUpdate(@Param("requestId") Integer requestId);

    /** Admin list: optional type and status filters, newest first (the order is fixed). */
    @Query(value = "SELECT r FROM AccountRequest r "
            + "WHERE (:type IS NULL OR r.type = :type) AND (:status IS NULL OR r.status = :status) "
            + "ORDER BY r.requestedAt DESC, r.requestId DESC",
            countQuery = "SELECT COUNT(r) FROM AccountRequest r "
                    + "WHERE (:type IS NULL OR r.type = :type) AND (:status IS NULL OR r.status = :status)")
    Page<AccountRequest> search(@Param("type") String type, @Param("status") String status, Pageable pageable);

    /**
     * Ids of READY exports whose download window ended, claimed for the expiry sweep (rows another instance is
     * processing are skipped).
     */
    @Query(value = """
            SELECT request_id FROM account_requests
            WHERE type = 'EXPORT' AND status = 'READY' AND expires_at <= :now
            ORDER BY expires_at, request_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Integer> claimExpiredExports(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
