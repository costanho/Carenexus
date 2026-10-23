package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.DependentAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DependentAccessRepository extends JpaRepository<DependentAccess, Integer> {

    /**
     * Find if caregiver has active access to dependent
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND d.caregiverSystemId = :caregiverSystemId AND d.isActive = true")
    Optional<DependentAccess> findActiveCaregiverAccessToDependents(@Param("caregiverSystemId") String caregiverSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all dependents a caregiver has access to
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverSystemId = :caregiverSystemId AND d.isActive = true")
    List<DependentAccess> findAllDependentsForCaregiver(@Param("caregiverSystemId") String caregiverSystemId);

    /**
     * Get all caregivers with access to a dependent
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.patientSystemId = :patientSystemId AND d.isActive = true")
    List<DependentAccess> findAllCaregiversForDependent(@Param("patientSystemId") String patientSystemId);

    /**
     * Find caregiver access with specific access level
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverSystemId = :caregiverSystemId AND d.patientSystemId = :patientSystemId AND d.accessLevel = :accessLevel AND d.isActive = true")
    Optional<DependentAccess> findAccessWithPermissionType(@Param("caregiverSystemId") String caregiverSystemId, @Param("patientSystemId") String patientSystemId, @Param("accessLevel") String accessLevel);
}
