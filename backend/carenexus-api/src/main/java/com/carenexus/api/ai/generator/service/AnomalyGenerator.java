package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc.Baseline;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc.MetricRow;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.health.model.MetricType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Row 3 of api/be-ai-generators.md 5.1, rule 5.3: a reading inside the reference range that differs markedly from
 * the patient's own recent readings raises one ANOMALY_DETECTED alert (priority MEDIUM) for every related doctor.
 * <ul>
 *   <li>types considered: {@link #TYPES} (never height, steps or BMI);</li>
 *   <li>baseline: median of the same patient's readings of the same type recorded before this one and not more
 *       than {@value #WINDOW_DAYS} days before it; fewer than {@value #MIN_READINGS} such readings, or a median of
 *       0, raises nothing;</li>
 *   <li>raised when {@code |value - median| / median * 100} is strictly greater than
 *       {@code carenexus.ai.anomaly-deviation-percent} (default 25).</li>
 * </ul>
 * An abnormal reading goes to {@link VitalSignAlertGenerator} only, never here. Dedupe key
 * {@code ANOMALY_DETECTED:<patientSystemId>:<type>:<yyyy-MM-dd of recorded_at>}: at most one per patient, type and
 * clinic day.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnomalyGenerator {

    public static final Set<MetricType> TYPES = EnumSet.of(MetricType.BP_SYSTOLIC, MetricType.BP_DIASTOLIC,
            MetricType.HEART_RATE, MetricType.GLUCOSE, MetricType.SPO2, MetricType.TEMPERATURE,
            MetricType.RESPIRATORY_RATE, MetricType.WEIGHT);
    static final int WINDOW_DAYS = 30;
    static final int MIN_READINGS = 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AiAlertService aiAlertService;
    private final AiGeneratorJdbc generatorJdbc;
    private final AiGeneratorProperties properties;

    /** @return the new alert id; empty when the rule does not apply or the day's alert exists */
    public Optional<Integer> generate(HealthMetricRecordedEvent event) {
        if (event == null || event.abnormal() || event.metricId() == null) {
            return Optional.empty();
        }
        Optional<MetricType> type = MetricType.parse(event.type());
        if (type.isEmpty() || !TYPES.contains(type.get())) {
            return Optional.empty();
        }
        // The reading may already be gone (health metrics are hard-deleted); then there is nothing to compare.
        Optional<MetricRow> stored = generatorJdbc.findMetric(event.metricId());
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        MetricRow reading = stored.get();
        if (!HealthMetricRecordedEvent.NORMAL.equals(reading.severity())
                || !type.get().name().equals(reading.type()) || reading.value() == null) {
            return Optional.empty();
        }
        LocalDateTime at = reading.recordedAt();
        Baseline baseline = generatorJdbc.baseline(reading.patientSystemId(), reading.type(),
                at.minusDays(WINDOW_DAYS), at);
        if (!isAnomalous(reading.value(), baseline, properties.getAnomalyDeviationPercent())) {
            return Optional.empty();
        }
        log.debug("Anomalous reading {} for patient {}", reading.metricId(), reading.patientSystemId());
        return aiAlertService.raise(new AiAlertCommand(
                reading.patientSystemId(),
                null,
                AiAlertType.ANOMALY_DETECTED.name(),
                AiAlertPriority.MEDIUM.name(),
                AlertTexts.ANOMALY_TITLE,
                AlertTexts.anomalyMessage(reading.type()),
                false,
                AlertTexts.REVIEW_READINGS_ACTION,
                AiAlertSourceType.HEALTH_METRIC.name(),
                reading.metricId(),
                null,
                dedupeKey(reading.patientSystemId(), reading.type(), at)));
    }

    /**
     * Rule 5.3: at least {@value #MIN_READINGS} baseline readings, a non-zero median, and a deviation strictly
     * greater than {@code thresholdPercent}.
     */
    static boolean isAnomalous(BigDecimal value, Baseline baseline, BigDecimal thresholdPercent) {
        if (baseline == null || baseline.readings() < MIN_READINGS || baseline.median() == null
                || baseline.median().signum() == 0) {
            return false;
        }
        BigDecimal median = baseline.median();
        BigDecimal deviationPercent = value.subtract(median).abs()
                .multiply(HUNDRED)
                .divide(median.abs(), MathContext.DECIMAL64);
        return deviationPercent.compareTo(thresholdPercent) > 0;
    }

    public static String dedupeKey(String patientSystemId, String type, LocalDateTime recordedAt) {
        return AiAlertType.ANOMALY_DETECTED.name() + ":" + patientSystemId + ":" + type + ":"
                + recordedAt.toLocalDate();
    }
}
