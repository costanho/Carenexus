package com.carenexus.api.lifecycle.repository;

import com.carenexus.api.common.model.AuditLog;
import org.springframework.data.repository.Repository;

/**
 * Writes the {@code ACCOUNT_DEACTIVATION_EFFECTS} audit row. AuditService.logAccountEvent cannot carry the counts,
 * so the module saves the row itself.
 */
public interface LifecycleAuditRepository extends Repository<AuditLog, Long> {

    AuditLog save(AuditLog entry);
}
