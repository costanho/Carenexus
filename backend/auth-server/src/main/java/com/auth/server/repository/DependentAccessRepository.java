package com.auth.server.repository;

import com.auth.server.model.DependentAccess;
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
    @Query("SELECT d FROM DependentAccess d WHERE d.dependentId = :dependentId AND d.caregiverId = :caregiverId AND d.isActive = true")
    Optional<DependentAccess> findActiveCaregiverAccessToDependents(@Param("caregiverId") Integer caregiverId, @Param("dependentId") Integer dependentId);

    /**
     * Get all dependents a caregiver has access to
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.caregiverId = :caregiverId AND d.isActive = true")
    List<DependentAccess> findAllDependentsForCaregiver(@Param("caregiverId") Integer caregiverId);

    /**
     * Get all caregivers with access to a dependent
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.dependentId = :dependentId AND d.isActive = true")
    List<DependentAccess> findAllCaregiversForDependent(@Param("dependentId") Integer dependentId);

    /**
     * Check if caregiver has specific permission type
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.dependentId = :dependentId AND d.caregiverId = :caregiverId AND d.permissionType = :permissionType AND d.isActive = true")
    Optional<DependentAccess> findAccessWithPermissionType(
            @Param("caregiverId") Integer caregiverId,
            @Param("dependentId") Integer dependentId,
            @Param("permissionType") String permissionType);

    /**
     * Find access by guardian authorization
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.dependentId = :dependentId AND d.guardianId = :guardianId AND d.isActive = true")
    List<DependentAccess> findAccessByGuardian(@Param("dependentId") Integer dependentId, @Param("guardianId") Integer guardianId);
}
