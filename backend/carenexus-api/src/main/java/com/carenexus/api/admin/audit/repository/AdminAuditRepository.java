package com.carenexus.api.admin.audit.repository;

import com.carenexus.api.common.model.AuditLog;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only audit log queries of the admin module. Filtered searches go through {@link JpaSpecificationExecutor}.
 */
public interface AdminAuditRepository extends Repository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findById(Long auditId);

    /** Names of the actors on a page of audit rows, in one query. */
    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName "
            + "FROM User u WHERE u.systemId IN :systemIds")
    List<ActorName> findActorNames(@Param("systemIds") Collection<String> systemIds);

    interface ActorName {
        String getSystemId();

        String getFirstName();

        String getLastName();
    }
}
