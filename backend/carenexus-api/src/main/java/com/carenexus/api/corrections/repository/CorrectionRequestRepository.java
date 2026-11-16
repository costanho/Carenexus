package com.carenexus.api.corrections.repository;

import com.carenexus.api.corrections.model.CorrectionRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Correction requests. Every list takes a {@link Pageable} whose sort is {@code createdAt DESC, requestId DESC}
 * (built by the service), so pages are deterministic. A null {@code status} means "any status".
 */
public interface CorrectionRequestRepository extends JpaRepository<CorrectionRequest, Integer> {

    /** The request, locked for update (resolve and assign serialise on it). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CorrectionRequest c WHERE c.requestId = :requestId")
    Optional<CorrectionRequest> findByIdForUpdate(@Param("requestId") Integer requestId);

    /** True when an OPEN request already exists for this record. */
    boolean existsByResourceTypeAndResourceIdAndStatus(String resourceType, Integer resourceId, String status);

    /** All requests about one patient. */
    @Query(value = "SELECT c FROM CorrectionRequest c WHERE c.patientSystemId = :patientSystemId "
            + "AND (:status IS NULL OR c.status = :status)",
            countQuery = "SELECT COUNT(c) FROM CorrectionRequest c WHERE c.patientSystemId = :patientSystemId "
                    + "AND (:status IS NULL OR c.status = :status)")
    Page<CorrectionRequest> findForPatient(@Param("patientSystemId") String patientSystemId,
                                           @Param("status") String status, Pageable pageable);

    /** A doctor's review queue. */
    @Query(value = "SELECT c FROM CorrectionRequest c WHERE c.assignedDoctorSystemId = :doctorSystemId "
            + "AND (:status IS NULL OR c.status = :status)",
            countQuery = "SELECT COUNT(c) FROM CorrectionRequest c WHERE c.assignedDoctorSystemId = :doctorSystemId "
                    + "AND (:status IS NULL OR c.status = :status)")
    Page<CorrectionRequest> findAssignedTo(@Param("doctorSystemId") String doctorSystemId,
                                           @Param("status") String status, Pageable pageable);

    /** Every request (admin), optionally only the unassigned ones. */
    @Query(value = "SELECT c FROM CorrectionRequest c WHERE (:status IS NULL OR c.status = :status) "
            + "AND (:unassignedOnly = false OR c.assignedDoctorSystemId IS NULL)",
            countQuery = "SELECT COUNT(c) FROM CorrectionRequest c WHERE (:status IS NULL OR c.status = :status) "
                    + "AND (:unassignedOnly = false OR c.assignedDoctorSystemId IS NULL)")
    Page<CorrectionRequest> findAllFiltered(@Param("status") String status,
                                            @Param("unassignedOnly") boolean unassignedOnly, Pageable pageable);
}
