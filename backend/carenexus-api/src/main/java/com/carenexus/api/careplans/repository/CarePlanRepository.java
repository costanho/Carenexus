package com.carenexus.api.careplans.repository;

import com.carenexus.api.careplans.model.CarePlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Care plans. List queries take a {@link Pageable} whose sort the service fixes to
 * {@code createdAt DESC, planId DESC}; a null filter means "no filter".
 */
public interface CarePlanRepository extends JpaRepository<CarePlan, Integer> {

    /** The plan with a row lock, for every write (PUT, status, review, goal progress). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM CarePlan p WHERE p.planId = :planId")
    Optional<CarePlan> findByIdForUpdate(@Param("planId") Integer planId);

    /** Every plan of one patient, whoever authored it, optionally of one status. */
    @Query("SELECT p FROM CarePlan p WHERE p.patientSystemId = :patientSystemId "
            + "AND (:status IS NULL OR p.status = :status)")
    Page<CarePlan> findForPatient(@Param("patientSystemId") String patientSystemId,
                                  @Param("status") String status,
                                  Pageable pageable);

    /** Plans authored by one doctor, optionally of one status and / or one patient. */
    @Query("SELECT p FROM CarePlan p WHERE p.doctorSystemId = :doctorSystemId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:patientSystemId IS NULL OR p.patientSystemId = :patientSystemId)")
    Page<CarePlan> findForDoctor(@Param("doctorSystemId") String doctorSystemId,
                                 @Param("status") String status,
                                 @Param("patientSystemId") String patientSystemId,
                                 Pageable pageable);
}
