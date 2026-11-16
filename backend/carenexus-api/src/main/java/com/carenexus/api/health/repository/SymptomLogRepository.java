package com.carenexus.api.health.repository;

import com.carenexus.api.health.model.SymptomLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/**
 * Symptom logs. Soft-deleted logs are invisible: reads go through {@link #findBySymptomIdAndIsDeletedFalse} or the
 * {@link HealthMetricSpecifications#symptomLogs} filter, which both exclude them.
 */
public interface SymptomLogRepository extends JpaRepository<SymptomLog, Integer>, JpaSpecificationExecutor<SymptomLog> {

    Optional<SymptomLog> findBySymptomIdAndIsDeletedFalse(Integer symptomId);
}
