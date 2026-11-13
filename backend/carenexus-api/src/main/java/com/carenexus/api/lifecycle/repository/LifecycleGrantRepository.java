package com.carenexus.api.lifecycle.repository;

import com.carenexus.api.core.model.DependentAccess;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Caregiver grants that are not revoked yet ({@code revoked_at IS NULL OR is_active}: active and expired grants),
 * read as access ids in keyset pages ordered by {@code access_id}. The first page uses {@code afterId = 0}.
 */
public interface LifecycleGrantRepository extends Repository<DependentAccess, Integer> {

    @Query("SELECT d.accessId FROM DependentAccess d WHERE d.caregiverSystemId = :systemId "
            + "AND (d.revokedAt IS NULL OR d.isActive = TRUE) AND d.accessId > :afterId ORDER BY d.accessId ASC")
    List<Integer> findUnrevokedAsCaregiverAfter(@Param("systemId") String systemId,
                                                @Param("afterId") Integer afterId,
                                                Pageable pageable);

    @Query("SELECT d.accessId FROM DependentAccess d WHERE d.patientSystemId = :systemId "
            + "AND (d.revokedAt IS NULL OR d.isActive = TRUE) AND d.accessId > :afterId ORDER BY d.accessId ASC")
    List<Integer> findUnrevokedAsPatientAfter(@Param("systemId") String systemId,
                                              @Param("afterId") Integer afterId,
                                              Pageable pageable);
}
