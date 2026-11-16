package com.carenexus.api.medication.repository;

import com.carenexus.api.medication.model.MedicationSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Dose reads and status changes through JPA. Dose creation, removal and the sweeper's bulk work are single native
 * statements in {@link MedicationScheduleJdbc}.
 */
public interface MedicationScheduleRepository extends JpaRepository<MedicationSchedule, Integer> {

    /** One patient's doses of one clinic day, by time then id; hard cap 200 rows. */
    List<MedicationSchedule> findTop200ByPatientSystemIdAndScheduleDateOrderByScheduledTimeAscScheduleIdAsc(
            String patientSystemId, LocalDate scheduleDate);

    /** The dose, row-locked until the end of the transaction (the sweeper skips locked rows). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MedicationSchedule s WHERE s.scheduleId = :id")
    Optional<MedicationSchedule> findForUpdate(@Param("id") Integer id);
}
