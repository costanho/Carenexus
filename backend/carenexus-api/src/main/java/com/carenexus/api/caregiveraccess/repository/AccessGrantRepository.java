package com.carenexus.api.caregiveraccess.repository;

import com.carenexus.api.core.model.DependentAccess;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * This module's read-only view of caregiver grants ({@code dependent_access}): the consent ledger snapshot, the
 * request rules and the expiry notice. Grants are only changed through CaregiverService.
 */
public interface AccessGrantRepository extends Repository<DependentAccess, Integer> {

    Optional<DependentAccess> findById(Integer accessId);

    /** The grant between this caregiver and patient (the pair is unique), in any state. */
    Optional<DependentAccess> findFirstByCaregiverSystemIdAndPatientSystemId(String caregiverSystemId,
                                                                             String patientSystemId);

    /**
     * Grants that are not revoked and whose expiry lies in (from, until], with an id above {@code afterAccessId},
     * in id order (keyset paging for the expiry notice job).
     */
    @Query("SELECT d FROM DependentAccess d WHERE d.isActive = true AND d.revokedAt IS NULL "
            + "AND d.expiresAt > :from AND d.expiresAt <= :until AND d.accessId > :afterAccessId "
            + "ORDER BY d.accessId")
    List<DependentAccess> findExpiringBetween(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
                                              @Param("afterAccessId") Integer afterAccessId, Pageable pageable);
}
