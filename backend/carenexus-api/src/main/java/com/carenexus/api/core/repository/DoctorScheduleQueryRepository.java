package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Read model of the doctor schedule and doctor appointment views (be-doctor-schedule). Every list is filtered, sorted
 * and paged in the database; counts are COUNT queries. {@link AppointmentRepository} and
 * {@link ConsultationRepository} stay untouched.
 *
 * {@code appointments.manual_reminder_sent_at} (V53) is not mapped on {@link Appointment}; it is read and written only
 * here, with native SQL, so an entity save never overwrites it.
 */
public interface DoctorScheduleQueryRepository extends Repository<Appointment, Integer>,
        JpaSpecificationExecutor<Appointment> {

    // ==================== APPOINTMENT ROWS ====================

    /** One appointment (no lock). */
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :appointmentId")
    java.util.Optional<Appointment> findAppointment(@Param("appointmentId") Integer appointmentId);

    /** Appointments with these ids, in no particular order. */
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId IN :ids")
    List<Appointment> findAppointmentsByIds(@Param("ids") Collection<Integer> ids);

    /** The doctor's appointments in the order and slice of {@code pageable}, without a count query. */
    @Query("SELECT a FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId")
    List<Appointment> findForDoctor(@Param("doctorSystemId") String doctorSystemId, Pageable pageable);

    /** The doctor's appointments that start in [from, to), in the order and slice of {@code pageable}, no count. */
    @Query("SELECT a FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId "
            + "AND a.scheduledAt >= :from AND a.scheduledAt < :to")
    List<Appointment> findForDoctorBetween(@Param("doctorSystemId") String doctorSystemId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           Pageable pageable);

    /** The doctor's appointments in these statuses that start after {@code after}, in the slice of {@code pageable}. */
    @Query("SELECT a FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId "
            + "AND a.status IN :statuses AND a.scheduledAt > :after")
    List<Appointment> findForDoctorStartingAfter(@Param("doctorSystemId") String doctorSystemId,
                                                 @Param("statuses") Collection<String> statuses,
                                                 @Param("after") LocalDateTime after,
                                                 Pageable pageable);

    /**
     * Ids of the doctor's appointments that need an outcome: status SCHEDULED or RESCHEDULED and
     * {@code scheduled_at + duration_minutes + afterMinutes} before {@code now}. Pass an empty
     * {@code patientSystemId} for no patient filter. Most recently started first.
     */
    @Query(value = "SELECT a.appointment_id FROM appointments a "
            + "WHERE a.doctor_system_id = :doctorSystemId "
            + "AND a.status IN ('SCHEDULED', 'RESCHEDULED') "
            + "AND a.scheduled_at + ((a.duration_minutes + :afterMinutes) * INTERVAL '1 minute') < :now "
            + "AND (:patientSystemId = '' OR a.patient_system_id = :patientSystemId) "
            + "ORDER BY a.scheduled_at DESC, a.appointment_id DESC",
            countQuery = "SELECT COUNT(*) FROM appointments a "
                    + "WHERE a.doctor_system_id = :doctorSystemId "
                    + "AND a.status IN ('SCHEDULED', 'RESCHEDULED') "
                    + "AND a.scheduled_at + ((a.duration_minutes + :afterMinutes) * INTERVAL '1 minute') < :now "
                    + "AND (:patientSystemId = '' OR a.patient_system_id = :patientSystemId)",
            nativeQuery = true)
    Page<Integer> findNeedsOutcomeIds(@Param("doctorSystemId") String doctorSystemId,
                                      @Param("patientSystemId") String patientSystemId,
                                      @Param("afterMinutes") int afterMinutes,
                                      @Param("now") LocalDateTime now,
                                      Pageable pageable);

    // ==================== COUNTS ====================

    /** Number of appointments of the doctor per status. */
    @Query("SELECT a.status AS status, COUNT(a) AS total FROM Appointment a "
            + "WHERE a.doctorSystemId = :doctorSystemId GROUP BY a.status")
    List<StatusCount> countAppointmentsByStatus(@Param("doctorSystemId") String doctorSystemId);

    /** Appointments of the doctor in these statuses that start after {@code after}. */
    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId "
            + "AND a.status IN :statuses AND a.scheduledAt > :after")
    long countForDoctorStartingAfter(@Param("doctorSystemId") String doctorSystemId,
                                     @Param("statuses") Collection<String> statuses,
                                     @Param("after") LocalDateTime after);

    /** Same condition as {@link #findNeedsOutcomeIds}, without a patient filter. */
    @Query(value = "SELECT COUNT(*) FROM appointments a "
            + "WHERE a.doctor_system_id = :doctorSystemId "
            + "AND a.status IN ('SCHEDULED', 'RESCHEDULED') "
            + "AND a.scheduled_at + ((a.duration_minutes + :afterMinutes) * INTERVAL '1 minute') < :now",
            nativeQuery = true)
    long countNeedsOutcome(@Param("doctorSystemId") String doctorSystemId,
                           @Param("afterMinutes") int afterMinutes,
                           @Param("now") LocalDateTime now);

    /** Number of consultations authored by the doctor per status. */
    @Query("SELECT c.status AS status, COUNT(c) AS total FROM Consultation c "
            + "WHERE c.doctorSystemId = :doctorSystemId GROUP BY c.status")
    List<StatusCount> countConsultationsByStatus(@Param("doctorSystemId") String doctorSystemId);

    // ==================== CONSULTATIONS OF APPOINTMENTS ====================

    /**
     * Status summary of every consultation (any status) of these appointments written by the doctor. The caller
     * picks the linked one (newest non-cancelled).
     */
    @Query("SELECT c.consultationId AS consultationId, c.appointmentId AS appointmentId, c.status AS status, "
            + "c.createdAt AS createdAt FROM Consultation c "
            + "WHERE c.doctorSystemId = :doctorSystemId AND c.appointmentId IN :appointmentIds")
    List<ConsultationLink> findConsultationLinks(@Param("doctorSystemId") String doctorSystemId,
                                                @Param("appointmentIds") Collection<Integer> appointmentIds);

    /** Full consultations (any status) of these appointments written by the doctor, oldest id first. */
    @Query("SELECT c FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId "
            + "AND c.appointmentId IN :appointmentIds ORDER BY c.consultationId ASC")
    List<Consultation> findConsultationsOfAppointments(@Param("doctorSystemId") String doctorSystemId,
                                                       @Param("appointmentIds") Collection<Integer> appointmentIds);

    // ==================== MANUAL REMINDER ====================

    /**
     * Rows of {@code [appointment_id, manual_reminder_sent_at]} for these appointments (the time may be null).
     */
    @Query(value = "SELECT a.appointment_id, a.manual_reminder_sent_at FROM appointments a "
            + "WHERE a.appointment_id IN (:ids)", nativeQuery = true)
    List<Object[]> findManualReminderTimes(@Param("ids") Collection<Integer> ids);

    /**
     * Claims the manual reminder of the current clock hour: sets {@code manual_reminder_sent_at} to {@code now} only
     * when the appointment is still active ({@code SCHEDULED} or {@code RESCHEDULED}), has not started at {@code now},
     * and the time is null or earlier than {@code hourStart}. Returns 1 when this call won the claim, 0 otherwise.
     * Concurrent calls (and a concurrent cancel, outcome or reschedule) are serialised by the row lock of the UPDATE,
     * and the loser re-evaluates the whole condition on the committed row, so at most one reminder goes out per hour
     * and never for an appointment that stopped being active or started meanwhile.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE appointments SET manual_reminder_sent_at = :now "
            + "WHERE appointment_id = :appointmentId "
            + "AND status IN ('SCHEDULED', 'RESCHEDULED') AND scheduled_at > :now "
            + "AND (manual_reminder_sent_at IS NULL OR manual_reminder_sent_at < :hourStart)",
            nativeQuery = true)
    int claimManualReminder(@Param("appointmentId") Integer appointmentId,
                            @Param("now") LocalDateTime now,
                            @Param("hourStart") LocalDateTime hourStart);

    /**
     * {@code [status, scheduled_at]} of the appointment as committed, read past the persistence context (used after a
     * lost claim to tell "already sent" from "no longer active or started").
     */
    @Query(value = "SELECT a.status, a.scheduled_at FROM appointments a WHERE a.appointment_id = :appointmentId",
            nativeQuery = true)
    List<Object[]> findCurrentState(@Param("appointmentId") Integer appointmentId);

    // ==================== PROJECTIONS ====================

    /** One row of a COUNT ... GROUP BY status. */
    interface StatusCount {
        String getStatus();

        Long getTotal();
    }

    /** Identity, status and creation time of a consultation, for the linked consultation of an appointment. */
    interface ConsultationLink {
        Integer getConsultationId();

        Integer getAppointmentId();

        String getStatus();

        LocalDateTime getCreatedAt();
    }
}
