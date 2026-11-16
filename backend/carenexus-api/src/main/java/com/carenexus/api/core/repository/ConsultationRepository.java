package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Consultation;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Consultations. Lists are filtered, ordered and capped in the database: pass {@link #NEWEST_FIRST} (or a
 * {@code Pageable} built with it) so every list has the same deterministic order.
 *
 * The un-paged finders at the top are kept for existing callers outside ConsultationService.
 */
@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, Integer>, JpaSpecificationExecutor<Consultation> {

    /** The one list order: newest first, ties broken by the primary key. */
    Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("consultationId"));

    // ==================== KEPT FOR EXISTING CALLERS ====================

    List<Consultation> findByPatientSystemId(String patientSystemId);
    List<Consultation> findByDoctorSystemId(String doctorSystemId);
    List<Consultation> findByStatus(String status);
    List<Consultation> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Consultation> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Consultation> findByAppointmentId(Integer appointmentId);
    Optional<Consultation> findByAppointmentIdAndPatientSystemId(Integer appointmentId, String patientSystemId);
    List<Consultation> findByPatientSystemIdIn(List<String> patientSystemIds);
    List<Consultation> findByDoctorSystemIdAndPatientSystemId(String doctorSystemId, String patientSystemId);

    // ==================== CAPPED LISTS (legacy array routes) ====================

    List<Consultation> findByPatientSystemId(String patientSystemId, Pageable pageable);
    List<Consultation> findByPatientSystemIdAndStatusIn(String patientSystemId, Collection<String> statuses, Pageable pageable);
    List<Consultation> findByDoctorSystemId(String doctorSystemId, Pageable pageable);
    List<Consultation> findByDoctorSystemIdAndStatus(String doctorSystemId, String status, Pageable pageable);
    List<Consultation> findByDoctorSystemIdAndStatusIn(String doctorSystemId, Collection<String> statuses, Pageable pageable);
    List<Consultation> findByDoctorSystemIdAndPatientSystemId(String doctorSystemId, String patientSystemId, Pageable pageable);
    List<Consultation> findByDoctorSystemIdAndPatientSystemIdAndStatusIn(String doctorSystemId, String patientSystemId,
                                                                        Collection<String> statuses, Pageable pageable);
    List<Consultation> findByPatientSystemIdAndStatus(String patientSystemId, String status, Pageable pageable);
    List<Consultation> findByAppointmentId(Integer appointmentId, Pageable pageable);
    List<Consultation> findByAppointmentIdAndStatusIn(Integer appointmentId, Collection<String> statuses, Pageable pageable);
    List<Consultation> findByPatientSystemIdInAndStatusIn(Collection<String> patientSystemIds, Collection<String> statuses,
                                                          Pageable pageable);

    // ==================== WRITES: CHECKS AND LOCKS ====================

    /** True when the appointment already has a consultation in one of {@code statuses}. */
    boolean existsByAppointmentIdAndStatusIn(Integer appointmentId, Collection<String> statuses);

    /**
     * Locks the appointment row (SELECT ... FOR UPDATE) for the rest of the transaction. Every consultation write
     * takes this lock first, then the consultation row, the same order as an appointment change whose listener
     * cancels the consultation, so concurrent creates for one appointment are serialised and a completion cannot
     * deadlock with a cancellation. Empty when the appointment does not exist.
     */
    @Query(value = "SELECT appointment_id FROM appointments WHERE appointment_id = :appointmentId FOR UPDATE",
            nativeQuery = true)
    Optional<Integer> lockAppointment(@Param("appointmentId") Integer appointmentId);

    /** The appointment of a consultation, read without loading the entity (so a later locking read is fresh). */
    @Query(value = "SELECT appointment_id FROM consultations WHERE consultation_id = :consultationId", nativeQuery = true)
    Optional<Integer> findAppointmentIdOf(@Param("consultationId") Integer consultationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Consultation c WHERE c.consultationId = :consultationId")
    Optional<Consultation> findByIdForUpdate(@Param("consultationId") Integer consultationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Consultation c WHERE c.appointmentId = :appointmentId AND c.status IN :statuses "
            + "ORDER BY c.consultationId")
    List<Consultation> findByAppointmentIdAndStatusInForUpdate(@Param("appointmentId") Integer appointmentId,
                                                               @Param("statuses") Collection<String> statuses);

    // ==================== BATCH LOOKUPS FOR RESPONSES (native: they see changes made by listeners) ====================

    /** Rows of {@code [appointment_id, type, status]} for the given appointments. */
    @Query(value = "SELECT appointment_id, type, status FROM appointments WHERE appointment_id IN (:appointmentIds)",
            nativeQuery = true)
    List<Object[]> findAppointmentTypeAndStatus(@Param("appointmentIds") Collection<Integer> appointmentIds);

    /**
     * Rows of {@code [consultation_id, record_id]}: the lowest id of the non-deleted CONSULT medical record of each
     * consultation that has one.
     */
    @Query(value = "SELECT consultation_id, MIN(record_id) FROM medical_records "
            + "WHERE consultation_id IN (:consultationIds) AND record_type = 'CONSULT' AND is_deleted = FALSE "
            + "GROUP BY consultation_id", nativeQuery = true)
    List<Object[]> findConsultRecordIds(@Param("consultationIds") Collection<Integer> consultationIds);

    // ==================== STATS ====================

    /** Rows of {@code [status, count]} for the consultations the doctor authored. */
    @Query("SELECT c.status, COUNT(c) FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId GROUP BY c.status")
    List<Object[]> countByStatusForDoctor(@Param("doctorSystemId") String doctorSystemId);

    // ==================== FILTERS FOR THE CANONICAL PAGED LIST ====================

    /** Matches everything for a null or blank id. */
    static Specification<Consultation> patient(String patientSystemId) {
        return (root, query, cb) -> patientSystemId == null || patientSystemId.isBlank() ? cb.conjunction()
                : cb.equal(root.get("patientSystemId"), patientSystemId);
    }

    /** Matches everything for a null or blank id. */
    static Specification<Consultation> doctor(String doctorSystemId) {
        return (root, query, cb) -> doctorSystemId == null || doctorSystemId.isBlank() ? cb.conjunction()
                : cb.equal(root.get("doctorSystemId"), doctorSystemId);
    }

    /** Matches everything for a null collection. */
    static Specification<Consultation> statusIn(Collection<String> statuses) {
        return (root, query, cb) -> statuses == null ? cb.conjunction() : root.get("status").in(statuses);
    }
}
