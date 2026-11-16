package com.carenexus.api.health.repository;

import com.carenexus.api.health.model.HealthMetric;
import com.carenexus.api.health.model.SymptomLog;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Filters of the two paged histories. Every filter is optional (null or empty = no restriction); all of them are
 * applied in the database.
 */
public final class HealthMetricSpecifications {

    private HealthMetricSpecifications() {
    }

    /** Readings of one patient, optionally limited to some types and to [fromInclusive, toExclusive). */
    public static Specification<HealthMetric> metrics(String patientSystemId, Collection<String> types,
                                                      LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return (root, query, cb) -> {
            var predicate = cb.equal(root.get("patientSystemId"), patientSystemId);
            if (types != null && !types.isEmpty()) {
                predicate = cb.and(predicate, root.get("type").in(types));
            }
            if (fromInclusive != null) {
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("recordedAt"), fromInclusive));
            }
            if (toExclusive != null) {
                predicate = cb.and(predicate, cb.lessThan(root.get("recordedAt"), toExclusive));
            }
            return predicate;
        };
    }

    /** Symptom logs of one patient that are not deleted, optionally by severity and [fromInclusive, toExclusive). */
    public static Specification<SymptomLog> symptomLogs(String patientSystemId, String severity,
                                                        LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return (root, query, cb) -> {
            var predicate = cb.and(
                    cb.equal(root.get("patientSystemId"), patientSystemId),
                    cb.isFalse(root.get("isDeleted")));
            if (severity != null) {
                predicate = cb.and(predicate, cb.equal(root.get("severity"), severity));
            }
            if (fromInclusive != null) {
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("loggedAt"), fromInclusive));
            }
            if (toExclusive != null) {
                predicate = cb.and(predicate, cb.lessThan(root.get("loggedAt"), toExclusive));
            }
            return predicate;
        };
    }
}
