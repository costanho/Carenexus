package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Integer> {
    List<Appointment> findByPatientSystemId(String patientSystemId);
    List<Appointment> findByDoctorSystemId(String doctorSystemId);
    List<Appointment> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Appointment> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Appointment> findByPatientSystemIdAndStatusIn(String patientSystemId, Collection<String> statuses);
    List<Appointment> findByDoctorSystemIdAndStatusIn(String doctorSystemId, Collection<String> statuses);
    List<Appointment> findByScheduledAtBetween(LocalDateTime start, LocalDateTime end);
    List<Appointment> findByDoctorSystemIdAndScheduledAtBetween(String doctorSystemId, LocalDateTime start, LocalDateTime end);
    List<Appointment> findByDoctorSystemIdAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
            String doctorSystemId, Collection<String> statuses, LocalDateTime start, LocalDateTime end);
    Optional<Appointment> findByAppointmentIdAndPatientSystemId(Integer appointmentId, String patientSystemId);
    List<Appointment> findByStatus(String status);
    List<Appointment> findByPatientSystemIdIn(List<String> patientSystemIds);

    /**
     * Transaction-scoped advisory lock on one schedule (a doctor's or a patient's calendar). Held until the
     * surrounding transaction commits or rolls back, so overlap check + insert/update are serialized per key.
     * Always lock the doctor key before the patient key to keep the lock order consistent.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('carenexus.appointment-slot'), hashtext(:scheduleKey))",
            nativeQuery = true)
    Integer lockSchedule(@Param("scheduleKey") String scheduleKey);

    /**
     * Active (SCHEDULED/RESCHEDULED) appointments of the doctor whose [start, start + duration) overlaps
     * [start, end), ignoring {@code excludeAppointmentId} (pass -1 when creating).
     */
    @Query(value = "SELECT COUNT(*) FROM appointments " +
            "WHERE doctor_system_id = :doctorSystemId " +
            "AND status IN ('SCHEDULED','RESCHEDULED') " +
            "AND appointment_id <> :excludeAppointmentId " +
            "AND scheduled_at < :end " +
            "AND scheduled_at + (duration_minutes * INTERVAL '1 minute') > :start",
            nativeQuery = true)
    long countOverlappingForDoctor(@Param("doctorSystemId") String doctorSystemId,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end,
                                   @Param("excludeAppointmentId") Integer excludeAppointmentId);

    /**
     * Same as {@link #countOverlappingForDoctor} for the patient's own calendar.
     */
    @Query(value = "SELECT COUNT(*) FROM appointments " +
            "WHERE patient_system_id = :patientSystemId " +
            "AND status IN ('SCHEDULED','RESCHEDULED') " +
            "AND appointment_id <> :excludeAppointmentId " +
            "AND scheduled_at < :end " +
            "AND scheduled_at + (duration_minutes * INTERVAL '1 minute') > :start",
            nativeQuery = true)
    long countOverlappingForPatient(@Param("patientSystemId") String patientSystemId,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end,
                                    @Param("excludeAppointmentId") Integer excludeAppointmentId);

    @Query(value = "SELECT COUNT(*) FROM facilities WHERE facility_id = :facilityId", nativeQuery = true)
    long countFacilitiesById(@Param("facilityId") Integer facilityId);
}
