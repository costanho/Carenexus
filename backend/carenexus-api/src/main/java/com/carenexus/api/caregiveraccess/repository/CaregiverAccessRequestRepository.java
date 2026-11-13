package com.carenexus.api.caregiveraccess.repository;

import com.carenexus.api.caregiveraccess.model.CaregiverAccessRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** Access requests. Every list is filtered and ordered in the database (newest first, then id descending). */
public interface CaregiverAccessRequestRepository extends JpaRepository<CaregiverAccessRequest, Integer> {

    /** The request, row-locked for a status change (approve, decline, cancel never race each other). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM CaregiverAccessRequest r WHERE r.requestId = :requestId")
    Optional<CaregiverAccessRequest> findForUpdate(@Param("requestId") Integer requestId);

    /** The caregiver's pending request for this patient, if any (at most one: partial unique index). */
    @Query("SELECT r FROM CaregiverAccessRequest r WHERE r.caregiverSystemId = :caregiverSystemId "
            + "AND r.patientSystemId = :patientSystemId AND r.status = 'PENDING'")
    Optional<CaregiverAccessRequest> findPendingForPatient(@Param("caregiverSystemId") String caregiverSystemId,
                                                           @Param("patientSystemId") String patientSystemId);

    /** The caregiver's pending request for this email, if any (at most one: partial unique index). */
    @Query("SELECT r FROM CaregiverAccessRequest r WHERE r.caregiverSystemId = :caregiverSystemId "
            + "AND r.patientEmail = :patientEmail AND r.status = 'PENDING'")
    Optional<CaregiverAccessRequest> findPendingForEmail(@Param("caregiverSystemId") String caregiverSystemId,
                                                         @Param("patientEmail") String patientEmail);

    /** The caregiver's own requests; {@code status} null = every status. */
    @Query(value = "SELECT r FROM CaregiverAccessRequest r WHERE r.caregiverSystemId = :caregiverSystemId "
            + "AND (:status IS NULL OR r.status = :status) ORDER BY r.createdAt DESC, r.requestId DESC",
            countQuery = "SELECT COUNT(r) FROM CaregiverAccessRequest r WHERE r.caregiverSystemId = :caregiverSystemId "
                    + "AND (:status IS NULL OR r.status = :status)")
    Page<CaregiverAccessRequest> findForCaregiver(@Param("caregiverSystemId") String caregiverSystemId,
                                                  @Param("status") String status, Pageable pageable);

    /** The requests addressed to the patient; {@code status} null = every status. */
    @Query(value = "SELECT r FROM CaregiverAccessRequest r WHERE r.patientSystemId = :patientSystemId "
            + "AND (:status IS NULL OR r.status = :status) ORDER BY r.createdAt DESC, r.requestId DESC",
            countQuery = "SELECT COUNT(r) FROM CaregiverAccessRequest r WHERE r.patientSystemId = :patientSystemId "
                    + "AND (:status IS NULL OR r.status = :status)")
    Page<CaregiverAccessRequest> findForPatient(@Param("patientSystemId") String patientSystemId,
                                                @Param("status") String status, Pageable pageable);

    /**
     * Marks at most {@code batch} PENDING requests created at or before {@code cutoff} EXPIRED (oldest first); rows
     * another transaction holds are skipped and picked up by the next batch or run.
     *
     * @return number of requests expired
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE caregiver_access_requests SET status = 'EXPIRED', responded_at = :now "
            + "WHERE request_id IN (SELECT request_id FROM caregiver_access_requests "
            + "WHERE status = 'PENDING' AND created_at <= :cutoff ORDER BY created_at, request_id "
            + "LIMIT :batch FOR UPDATE SKIP LOCKED)", nativeQuery = true)
    int expirePendingBatch(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now,
                           @Param("batch") int batch);
}
