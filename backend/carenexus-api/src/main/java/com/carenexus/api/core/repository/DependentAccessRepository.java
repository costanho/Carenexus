package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.DependentAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
