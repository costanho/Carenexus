package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Appointment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Integer>, JpaSpecificationExecutor<Appointment> {
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
     * The appointment row, locked FOR UPDATE until the surrounding transaction ends, so concurrent status changes
     * (cancel, outcome, consultation completion) are applied one after the other on the latest state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId = :appointmentId")
    Optional<Appointment> findByIdForUpdate(@Param("appointmentId") Integer appointmentId);

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

    /** Any facility row with this id (active or not). Kept for existing callers; booking uses {@link #countActiveFacilitiesById}. */
    @Query(value = "SELECT COUNT(*) FROM facilities WHERE facility_id = :facilityId", nativeQuery = true)
    long countFacilitiesById(@Param("facilityId") Integer facilityId);

    /** 1 when the facility exists and is active (facilities.is_active), else 0. */
    @Query(value = "SELECT COUNT(*) FROM facilities WHERE facility_id = :facilityId AND is_active = TRUE", nativeQuery = true)
    long countActiveFacilitiesById(@Param("facilityId") Integer facilityId);

    // ==================== BATCH LOOKUPS FOR RESPONSES ====================

    /** Name and address of facilities, active or not (a deactivated facility still shows on old appointments). */
    @Query(value = "SELECT facility_id AS facilityId, name AS name, address AS address FROM facilities "
            + "WHERE facility_id IN (:facilityIds)", nativeQuery = true)
    List<FacilitySummary> findFacilitySummaries(@Param("facilityIds") Collection<Integer> facilityIds);

    /** First and last name of the accounts with these system ids (one query for a whole page). */
    @Query(value = "SELECT system_id AS systemId, first_name AS firstName, last_name AS lastName FROM users "
            + "WHERE system_id IN (:systemIds)", nativeQuery = true)
    List<PersonName> findPersonNames(@Param("systemIds") Collection<String> systemIds);

    // ==================== RULE LOOKUPS ====================

    /**
     * The patient's number on file: the account phone, else the patient profile's contact phone (V34); null when
     * neither is set. Blank values count as missing.
     */
    @Query(value = "SELECT COALESCE(NULLIF(TRIM(u.phone), ''), NULLIF(TRIM(p.contact_phone), '')) "
            + "FROM users u LEFT JOIN patients p ON p.system_id = u.system_id "
            + "WHERE u.system_id = :patientSystemId", nativeQuery = true)
    Optional<String> findPhoneOnFile(@Param("patientSystemId") String patientSystemId);

    /** A COMPLETED consultation exists for the appointment (consultations is owned by be-consultations; read natively). */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM consultations WHERE appointment_id = :appointmentId "
            + "AND status = 'COMPLETED')", nativeQuery = true)
    boolean existsCompletedConsultation(@Param("appointmentId") Integer appointmentId);

    /**
     * The first {@code limit} rows matching {@code specification} in {@code sort} order, without a count query
     * (capped legacy lists).
     */
    default List<Appointment> findFirst(Specification<Appointment> specification, Sort sort, int limit) {
        return findBy(specification, query -> query.sortBy(sort).limit(limit).all());
    }

    /** Projection of {@link #findFacilitySummaries}. */
    interface FacilitySummary {
        Integer getFacilityId();

        String getName();

        String getAddress();
    }

    /** Projection of {@link #findPersonNames}. */
    interface PersonName {
        String getSystemId();

        String getFirstName();

        String getLastName();
    }
}
