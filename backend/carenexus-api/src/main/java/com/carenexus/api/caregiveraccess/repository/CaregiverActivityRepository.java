package com.carenexus.api.caregiveraccess.repository;

import com.carenexus.api.common.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * The patient's view of what one caregiver did with their data: successful audit_log rows whose actor is the
 * caregiver and whose patient is the caller. Only the action, resource type and time leave this repository (no IP
 * address, path, user agent or resource id).
 */
public interface CaregiverActivityRepository extends Repository<AuditLog, Long> {

    @Query(value = "SELECT a.auditId AS auditId, a.action AS action, a.resourceType AS resourceType, "
            + "a.timestamp AS occurredAt FROM AuditLog a WHERE a.systemId = :caregiverSystemId "
            + "AND a.patientSystemId = :patientSystemId AND a.status = 'SUCCESS' "
            + "ORDER BY a.timestamp DESC, a.auditId DESC",
            countQuery = "SELECT COUNT(a) FROM AuditLog a WHERE a.systemId = :caregiverSystemId "
                    + "AND a.patientSystemId = :patientSystemId AND a.status = 'SUCCESS'")
    Page<ActivityRow> findActivity(@Param("caregiverSystemId") String caregiverSystemId,
                                   @Param("patientSystemId") String patientSystemId, Pageable pageable);

    interface ActivityRow {

        Long getAuditId();

        String getAction();

        String getResourceType();

        LocalDateTime getOccurredAt();
    }
}
