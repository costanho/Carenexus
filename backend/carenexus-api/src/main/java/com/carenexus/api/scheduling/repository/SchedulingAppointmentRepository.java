package com.carenexus.api.scheduling.repository;

import com.carenexus.api.core.model.Appointment;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Read-only native queries of the scheduling module on tables it does not own ({@code appointments},
 * {@code doctors}, {@code doctor_time_off} joined with {@code appointments}). Only start, duration and owner ids are
 * read: slot computation never needs who the patient is or why they come.
 *
 * Active appointments are those with status SCHEDULED or RESCHEDULED, the same set AppointmentService checks for
 * overlaps.
 */
public interface SchedulingAppointmentRepository extends org.springframework.data.repository.Repository<Appointment, Integer> {

    /**
     * {@code [scheduled_at, duration_minutes]} of the doctor's active appointments that overlap {@code [from, to)},
     * ascending by start, ignoring {@code excludeAppointmentId} (pass -1 to ignore none).
     */
    @Query(value = "SELECT scheduled_at, duration_minutes FROM appointments "
            + "WHERE doctor_system_id = :doctorSystemId "
            + "AND status IN ('SCHEDULED','RESCHEDULED') "
            + "AND appointment_id <> :excludeAppointmentId "
            + "AND scheduled_at < :to "
            + "AND scheduled_at + (duration_minutes * INTERVAL '1 minute') > :from "
            + "ORDER BY scheduled_at ASC, appointment_id ASC",
            nativeQuery = true)
    List<Object[]> findActiveIntervals(@Param("doctorSystemId") String doctorSystemId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("excludeAppointmentId") Integer excludeAppointmentId);

    /**
     * {@code [time_off_id, count]} for each of the given time-off rows that overlaps at least one active
     * appointment of its doctor (rows without a conflict are absent). One query for a whole page.
     */
    @Query(value = "SELECT t.time_off_id, COUNT(a.appointment_id) FROM doctor_time_off t "
            + "JOIN appointments a ON a.doctor_system_id = t.doctor_system_id "
            + "AND a.status IN ('SCHEDULED','RESCHEDULED') "
            + "AND a.scheduled_at < t.ends_at "
            + "AND a.scheduled_at + (a.duration_minutes * INTERVAL '1 minute') > t.starts_at "
            + "WHERE t.time_off_id IN (:timeOffIds) "
            + "GROUP BY t.time_off_id",
            nativeQuery = true)
    List<Object[]> countConflictingAppointments(@Param("timeOffIds") Collection<Integer> timeOffIds);

    /** {@code [doctor_system_id, patient_system_id]} of one appointment; empty when it does not exist. */
    @Query(value = "SELECT doctor_system_id, patient_system_id FROM appointments WHERE appointment_id = :appointmentId",
            nativeQuery = true)
    List<Object[]> findParticipants(@Param("appointmentId") Integer appointmentId);

    /** 1 when a doctor profile with this system id exists, else 0. */
    @Query(value = "SELECT COUNT(*) FROM doctors WHERE system_id = :doctorSystemId", nativeQuery = true)
    long countDoctors(@Param("doctorSystemId") String doctorSystemId);
}
