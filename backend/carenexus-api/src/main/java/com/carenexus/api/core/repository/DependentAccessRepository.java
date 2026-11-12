package com.carenexus.api.core.repository;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.DependentAccess;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every "active" query below means: is_active = true AND revoked_at IS NULL AND
 * (expires_at IS NULL OR expires_at > now). Expired or revoked grants never authorize anything.
 */
@Repository
public interface DependentAccessRepository extends JpaRepository<DependentAccess, Integer> {

    String ACTIVE = " d.isActive = true AND d.revokedAt IS NULL AND (d.expiresAt IS NULL OR d.expiresAt > CURRENT_TIMESTAMP) ";

    /**
     * Find if caregiver has active (not revoked, not expired) access to dependent
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND d.caregiverSystemId = :caregiverSystemId AND" + ACTIVE)
    Optional<DependentAccess> findActiveCaregiverAccessToDependents(@Param("caregiverSystemId") String caregiverSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all dependents a caregiver currently has access to
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverSystemId = :caregiverSystemId AND" + ACTIVE)
    List<DependentAccess> findAllDependentsForCaregiver(@Param("caregiverSystemId") String caregiverSystemId);

    /**
     * Get all caregivers that currently have access to a dependent
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND" + ACTIVE)
    List<DependentAccess> findAllCaregiversForDependent(@Param("patientSystemId") String patientSystemId);

    /** The active grants of several patients in one query (batch jobs; same rule as the single-patient query). */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId IN :patientSystemIds AND" + ACTIVE
            + "ORDER BY d.patientSystemId, d.accessId")
    List<DependentAccess> findAllCaregiversForDependents(
            @Param("patientSystemIds") Collection<String> patientSystemIds);

    /**
     * Find active caregiver access with a specific access level
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverSystemId = :caregiverSystemId AND d.patientSystemId = :patientSystemId AND d.accessLevel = :accessLevel AND" + ACTIVE)
    Optional<DependentAccess> findAccessWithPermissionType(@Param("caregiverSystemId") String caregiverSystemId, @Param("patientSystemId") String patientSystemId, @Param("accessLevel") String accessLevel);

    /**
     * The row for a caregiver/patient pair regardless of state (UNIQUE(caregiver_system_id, patient_system_id)).
     * Use it to re-activate a revoked grant instead of inserting a duplicate.
     */
    Optional<DependentAccess> findByCaregiverSystemIdAndPatientSystemId(String caregiverSystemId, String patientSystemId);

    /**
     * Every grant of a patient that has not been revoked (active and expired), newest first.
     * Used by the patient's "My caregivers" screen (GET /api/patients/me/caregiver-access).
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND d.revokedAt IS NULL ORDER BY d.authorizedDate DESC, d.accessId DESC")
    List<DependentAccess> findUnrevokedForPatient(@Param("patientSystemId") String patientSystemId);

    /** Unrevoked grants of a patient, newest first, one page (the patient's list is capped). */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND d.revokedAt IS NULL ORDER BY d.authorizedDate DESC, d.accessId DESC")
    List<DependentAccess> findUnrevokedForPatient(@Param("patientSystemId") String patientSystemId, Pageable page);

    /** The caregiver's ACTIVE grants, oldest grant first (stable order), one page (the list is capped). */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverSystemId = :caregiverSystemId AND" + ACTIVE + "ORDER BY d.accessId ASC")
    List<DependentAccess> findActiveForCaregiver(@Param("caregiverSystemId") String caregiverSystemId, Pageable page);

    // ==================== BATCH LOOKUPS AND THE CAREGIVER ACTIVITY FEED ====================
    // Reads of tables this module does not own (users, appointments, consultations) for the caregiver views: one IN
    // query per list, filtered and ordered in the database.

    /** Users by system id in one query (names, email, phone and sign-in state of the people in a grant list). */
    @Query("SELECT u FROM User u WHERE u.systemId IN :systemIds")
    List<User> findUsersBySystemIdIn(@Param("systemIds") Collection<String> systemIds);

    /**
     * Appointments of the given patients scheduled in [from, toExclusive), newest first (scheduled_at, then id).
     */
    @Query(value = "SELECT a FROM Appointment a WHERE a.patientSystemId IN :patientSystemIds"
            + " AND a.scheduledAt >= :from AND a.scheduledAt < :toExclusive"
            + " ORDER BY a.scheduledAt DESC, a.appointmentId DESC",
            countQuery = "SELECT COUNT(a) FROM Appointment a WHERE a.patientSystemId IN :patientSystemIds"
                    + " AND a.scheduledAt >= :from AND a.scheduledAt < :toExclusive")
    Page<Appointment> findDependentAppointments(@Param("patientSystemIds") Collection<String> patientSystemIds,
                                                @Param("from") LocalDateTime from,
                                                @Param("toExclusive") LocalDateTime toExclusive,
                                                Pageable page);

    /**
     * Consultations of the given patients created in [from, toExclusive) with one of the given statuses, newest
     * first (created_at, then id). Caregivers only ever get finished notes (COMPLETED, CANCELLED).
     */
    @Query(value = "SELECT c FROM Consultation c WHERE c.patientSystemId IN :patientSystemIds AND c.status IN :statuses"
            + " AND c.createdAt >= :from AND c.createdAt < :toExclusive"
            + " ORDER BY c.createdAt DESC, c.consultationId DESC",
            countQuery = "SELECT COUNT(c) FROM Consultation c WHERE c.patientSystemId IN :patientSystemIds"
                    + " AND c.status IN :statuses AND c.createdAt >= :from AND c.createdAt < :toExclusive")
    Page<Consultation> findDependentConsultations(@Param("patientSystemIds") Collection<String> patientSystemIds,
                                                  @Param("statuses") Collection<String> statuses,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("toExclusive") LocalDateTime toExclusive,
                                                  Pageable page);

    /** Appointment types by id (the appointmentType of each consultation row), one query. */
    @Query("SELECT a FROM Appointment a WHERE a.appointmentId IN :appointmentIds")
    List<Appointment> findAppointmentsByIdIn(@Param("appointmentIds") Collection<Integer> appointmentIds);

    /**
     * Grants that lapsed (not revoked, past expires_at) and were not yet handled by the expiry sweeper for their
     * current expiry: never processed, or processed before the expiry was moved later and passed again.
     */
    String EXPIRED_UNPROCESSED = " d.isActive = true AND d.revokedAt IS NULL AND d.expiresAt IS NOT NULL AND d.expiresAt <= :now"
            + " AND (d.expiryProcessedAt IS NULL OR d.expiryProcessedAt < d.expiresAt) ";

    @Query("SELECT d.accessId FROM DependentAccess d WHERE" + EXPIRED_UNPROCESSED + "ORDER BY d.expiresAt, d.accessId")
    List<Integer> findExpiredUnprocessedIds(@Param("now") LocalDateTime now, Pageable page);

    /** Row lock for the expiry sweeper (re-checked after locking). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DependentAccess d WHERE d.accessId = :accessId")
    Optional<DependentAccess> findForUpdateById(@Param("accessId") Integer accessId);
}
