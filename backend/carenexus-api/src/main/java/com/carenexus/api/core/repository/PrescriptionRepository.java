package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Prescription;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Prescriptions. The filtered, paged lists are built with {@link JpaSpecificationExecutor} in PrescriptionService
 * (every list is ordered {@code prescribed_date DESC, prescription_id DESC}).
 */
@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Integer>, JpaSpecificationExecutor<Prescription> {

    // Unbounded finders kept from the baseline for existing callers (DoctorProfileService counts with the first).
    // New list endpoints use the bounded variants below or a Specification with a Pageable.
    List<Prescription> findByDoctorSystemId(String doctorSystemId);
    List<Prescription> findByPatientSystemId(String patientSystemId);
    List<Prescription> findByStatus(String status);
    List<Prescription> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Prescription> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Prescription> findByConsultationId(Integer consultationId);
    List<Prescription> findByDoctorSystemIdAndPatientSystemId(String doctorSystemId, String patientSystemId);
    List<Prescription> findByExpiryDateBeforeAndStatus(LocalDate date, String status);

    /** One patient's prescriptions, bounded and ordered by {@code pageable} (legacy array endpoint). */
    List<Prescription> findByPatientSystemId(String patientSystemId, Pageable pageable);

    /** One prescriber's prescriptions, bounded and ordered by {@code pageable} (legacy array endpoint). */
    List<Prescription> findByDoctorSystemId(String doctorSystemId, Pageable pageable);

    Optional<Prescription> findByPrescriptionIdAndPatientSystemId(Integer prescriptionId, String patientSystemId);

    /** Loads one row with a write lock (status changes and edits), so they never race the expiry sweeper. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Prescription p WHERE p.prescriptionId = :id")
    Optional<Prescription> findByIdForUpdate(@Param("id") Integer id);

    /**
     * Expiry sweeper batch: ACTIVE or ON_HOLD rows whose last valid day is before {@code today}, locked so that a
     * concurrent run skips them. Uses the V37 index on (status, expiry_date).
     */
    @Query(value = "SELECT * FROM prescriptions "
            + "WHERE status IN ('ACTIVE', 'ON_HOLD') AND expiry_date IS NOT NULL AND expiry_date < :today "
            + "ORDER BY expiry_date, prescription_id "
            + "LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Prescription> lockExpiredBatch(@Param("today") LocalDate today, @Param("limit") int limit);

    /**
     * Names of the given accounts, one {@code [system_id, first_name, last_name]} row per account found
     * (prescriber display names, resolved once per list).
     */
    @Query(value = "SELECT system_id, first_name, last_name FROM users WHERE system_id IN (:systemIds)", nativeQuery = true)
    List<Object[]> findAccountNames(@Param("systemIds") Collection<String> systemIds);
}
