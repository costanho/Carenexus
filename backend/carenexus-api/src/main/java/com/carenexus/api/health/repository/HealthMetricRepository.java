package com.carenexus.api.health.repository;

import com.carenexus.api.health.model.HealthMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Health readings. The paged history is built with {@link HealthMetricSpecifications} (optional type and date
 * filters, ordered by recorded_at DESC, metric_id DESC); the latest-per-type and the daily trend are computed in the
 * database.
 */
public interface HealthMetricRepository extends JpaRepository<HealthMetric, Integer>, JpaSpecificationExecutor<HealthMetric> {

    /**
     * The most recent reading of each type for the patient (greatest recorded_at, ties broken by the greatest
     * metric_id). At most one row per type, in no particular order.
     */
    @Query(value = "SELECT DISTINCT ON (hm.type) hm.* FROM health_metrics hm "
            + "WHERE hm.patient_system_id = :patientSystemId "
            + "ORDER BY hm.type, hm.recorded_at DESC, hm.metric_id DESC", nativeQuery = true)
    List<HealthMetric> findLatestPerType(@Param("patientSystemId") String patientSystemId);

    /**
     * One row per clinic day (recorded_at is clinic wall-clock) with at least one reading of the type in
     * [from, toExclusive), ascending by day.
     */
    @Query(value = "SELECT CAST(hm.recorded_at AS DATE) AS \"day\", AVG(hm.value) AS \"avgValue\", "
            + "MIN(hm.value) AS \"minValue\", MAX(hm.value) AS \"maxValue\", COUNT(*) AS \"readings\", "
            + "BOOL_OR(hm.is_abnormal) AS \"anyAbnormal\" "
            + "FROM health_metrics hm "
            + "WHERE hm.patient_system_id = :patientSystemId AND hm.type = :type "
            + "AND hm.recorded_at >= :fromInclusive AND hm.recorded_at < :toExclusive "
            + "GROUP BY CAST(hm.recorded_at AS DATE) "
            + "ORDER BY CAST(hm.recorded_at AS DATE)", nativeQuery = true)
    List<DailyAggregate> findDailyAggregates(@Param("patientSystemId") String patientSystemId,
                                             @Param("type") String type,
                                             @Param("fromInclusive") LocalDateTime fromInclusive,
                                             @Param("toExclusive") LocalDateTime toExclusive);

    /** The other half of a blood-pressure pair: same patient and reading group, the partner type. */
    Optional<HealthMetric> findFirstByPatientSystemIdAndReadingGroupIdAndType(String patientSystemId,
                                                                            String readingGroupId, String type);

    /** One day of the trend. */
    interface DailyAggregate {

        LocalDate getDay();

        BigDecimal getAvgValue();

        BigDecimal getMinValue();

        BigDecimal getMaxValue();

        Long getReadings();

        Boolean getAnyAbnormal();
    }
}
