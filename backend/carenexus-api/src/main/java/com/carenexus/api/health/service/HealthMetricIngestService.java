package com.carenexus.api.health.service;

import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.health.model.HealthMetric;
import com.carenexus.api.health.model.MetricType;
import com.carenexus.api.health.repository.HealthMetricRepository;
import com.carenexus.api.health.repository.HealthPersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one way readings enter {@code health_metrics}: manual entry ({@link HealthMetricService#record}) and future
 * device connectors ({@link com.carenexus.api.health.provider.WearableConnector}) both end here.
 *
 * Every reading of one call is validated (type, unit, plausible range, value format, not in the future), stored in
 * the caller's transaction with one new {@code readingGroupId}, classified by {@link VitalRangeEvaluator} (severity
 * fixed with the row), audited ({@code CREATE / HEALTH_METRIC}) and announced with one
 * {@link HealthMetricRecordedEvent} per row. Manual entry adds the session rules: no BMI, one reading per type,
 * blood pressure only as a consistent systolic/diastolic pair.
 *
 * This service does no caller authorisation: the HTTP path authorises in {@link HealthMetricService} before calling
 * it, and a connector runs as the system on behalf of a patient whose device link was set up by that patient.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class HealthMetricIngestService {

    public static final String RESOURCE = "HEALTH_METRIC";

    /** Device clocks drift: a reading up to this much ahead of the server clock is still accepted. */
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    /** Upper bound of one connector batch, so a single sync never holds a huge transaction. */
    static final int MAX_BATCH = 500;
    static final int MAX_NOTES = 500;
    static final int MAX_DEVICE_ID = 100;
    static final int MAX_INTEGER_DIGITS = 7;
    static final int MAX_DECIMALS = 3;

    private static final Set<String> SOURCES = Set.of(HealthMetric.SOURCE_MANUAL, HealthMetric.SOURCE_WEARABLE,
            HealthMetric.SOURCE_DEVICE, HealthMetric.SOURCE_INTEGRATION);

    private final HealthMetricRepository healthMetricRepository;
    private final HealthPersonRepository healthPersonRepository;
    private final VitalRangeEvaluator vitalRangeEvaluator;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Stores readings that arrived from a device or integration (no recorder). Applies the type, unit, plausible
     * range, value format and future rules of manual entry; BMI rows are accepted here (a device may measure it).
     *
     * @param patientSystemId an existing patient's system id
     * @param source          WEARABLE, DEVICE or INTEGRATION (MANUAL is reserved for manual entry)
     * @param deviceId        the platform's device identifier (max 100), may be null
     * @param readings        1 to 500 readings
     * @return the stored rows, in the order of {@code readings}
     * @throws BadRequestException (code VALIDATION_FAILED) when a reading breaks a rule; nothing is stored then
     */
    public List<HealthMetric> ingest(String patientSystemId, String source, String deviceId, List<MetricReading> readings) {
        String normalizedSource = normalizeSource(source);
        if (HealthMetric.SOURCE_MANUAL.equals(normalizedSource)) {
            throw new IllegalArgumentException("MANUAL readings are recorded through HealthMetricService");
        }
        if (patientSystemId == null || patientSystemId.isBlank()
                || healthPersonRepository.findNames(List.of(patientSystemId.trim())).isEmpty()) {
            throw new IllegalArgumentException("Unknown patient for ingested readings");
        }
        String device = trimToNull(deviceId);
        if (device != null && device.length() > MAX_DEVICE_ID) {
            throw new IllegalArgumentException("Device id is longer than " + MAX_DEVICE_ID + " characters");
        }
        return store(patientSystemId.trim(), normalizedSource, device, null, readings);
    }

    /**
     * Stores one manually entered measurement session for an already authorised caller: {@code source = MANUAL},
     * no device, {@code recordedBySystemId} = the caller.
     */
    List<HealthMetric> recordManual(String patientSystemId, String recordedBySystemId, List<MetricReading> readings) {
        return store(patientSystemId, HealthMetric.SOURCE_MANUAL, null, recordedBySystemId, readings);
    }

    // ==================== STORE ====================

    private List<HealthMetric> store(String patientSystemId, String source, String deviceId, String recordedBy,
                                     List<MetricReading> readings) {
        boolean manual = HealthMetric.SOURCE_MANUAL.equals(source);
        if (readings == null || readings.isEmpty()) {
            throw invalid("At least one reading is required");
        }
        if (readings.size() > (manual ? 10 : MAX_BATCH)) {
            throw invalid(manual ? "A request can hold at most 10 readings"
                    : "A batch can hold at most " + MAX_BATCH + " readings");
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        List<MetricType> types = validateReadings(readings, manual);
        if (manual) {
            validateBloodPressurePair(readings, types);
        }
        LocalDateTime latestAllowed = now.plus(FUTURE_TOLERANCE);
        for (MetricReading reading : readings) {
            if (reading.recordedAt() != null && reading.recordedAt().isAfter(latestAllowed)) {
                throw invalid("recordedAt cannot be in the future");
            }
        }

        String readingGroupId = UUID.randomUUID().toString();
        List<HealthMetric> rows = new ArrayList<>(readings.size());
        for (int i = 0; i < readings.size(); i++) {
            MetricReading reading = readings.get(i);
            MetricType type = types.get(i);
            String severity = vitalRangeEvaluator.evaluate(type, reading.value());
            rows.add(HealthMetric.builder()
                    .patientSystemId(patientSystemId)
                    .recordedBySystemId(recordedBy)
                    .readingGroupId(readingGroupId)
                    .type(type.name())
                    .value(reading.value())
                    .unit(type.unit())
                    .source(source)
                    .deviceId(deviceId)
                    .severity(severity)
                    .isAbnormal(VitalRangeEvaluator.isAbnormal(severity))
                    .notes(trimToNull(reading.notes()))
                    .recordedAt(reading.recordedAt() == null ? now : reading.recordedAt().truncatedTo(ChronoUnit.SECONDS))
                    .build());
        }
        List<HealthMetric> saved = healthMetricRepository.saveAll(rows);

        for (HealthMetric metric : saved) {
            auditService.logClinicalAccess("CREATE", RESOURCE, metric.getMetricId(), patientSystemId);
            eventPublisher.publishEvent(new HealthMetricRecordedEvent(metric.getMetricId(), patientSystemId,
                    recordedBy, metric.getType(), Boolean.TRUE.equals(metric.getIsAbnormal()), metric.getSeverity(),
                    source));
        }
        log.info("Stored {} health reading(s) for {} (source {})", saved.size(), patientSystemId, source);
        return saved;
    }

    // ==================== RULES ====================

    /** Per-reading rules; returns the parsed type of each reading, in order. */
    private List<MetricType> validateReadings(List<MetricReading> readings, boolean manual) {
        List<MetricType> types = new ArrayList<>(readings.size());
        Set<MetricType> seen = EnumSet.noneOf(MetricType.class);
        for (MetricReading reading : readings) {
            if (reading == null) {
                throw invalid("Unknown health metric type");
            }
            MetricType type = MetricType.parse(reading.type())
                    .orElseThrow(() -> invalid("Unknown health metric type"));
            if (manual && !type.manualEntry()) {
                throw invalid("BMI is calculated from height and weight and cannot be entered");
            }
            if (manual && !seen.add(type)) {
                throw invalid("Only one reading per type is allowed in a request");
            }
            String unit = trimToNull(reading.unit());
            if (unit != null && !unit.equals(type.unit())) {
                throw invalid("Unit for " + type.name() + " must be " + type.unit());
            }
            BigDecimal value = reading.value();
            if (value == null) {
                throw invalid(type.label() + " needs a value");
            }
            if (!fitsColumn(value)) {
                throw invalid(type.label() + " must be a number with at most 3 decimals");
            }
            if (value.compareTo(type.plausibleMin()) < 0 || value.compareTo(type.plausibleMax()) > 0) {
                throw invalid(type.label() + " must be between " + whole(type.plausibleMin()) + " and "
                        + whole(type.plausibleMax()) + " " + type.unit());
            }
            String notes = trimToNull(reading.notes());
            if (notes != null && notes.length() > MAX_NOTES) {
                throw invalid("Notes must be at most " + MAX_NOTES + " characters");
            }
            types.add(type);
        }
        return types;
    }

    /** Manual entry: BP_SYSTOLIC and BP_DIASTOLIC only together, systolic higher, same time. */
    private void validateBloodPressurePair(List<MetricReading> readings, List<MetricType> types) {
        Map<MetricType, MetricReading> byType = new EnumMap<>(MetricType.class);
        for (int i = 0; i < readings.size(); i++) {
            byType.put(types.get(i), readings.get(i));
        }
        MetricReading systolic = byType.get(MetricType.BP_SYSTOLIC);
        MetricReading diastolic = byType.get(MetricType.BP_DIASTOLIC);
        if (systolic == null && diastolic == null) {
            return;
        }
        if (systolic == null || diastolic == null) {
            throw invalid("Blood pressure needs both systolic and diastolic readings");
        }
        if (systolic.value().compareTo(diastolic.value()) <= 0) {
            throw invalid("Systolic pressure must be higher than diastolic pressure");
        }
        if (!Objects.equals(seconds(systolic.recordedAt()), seconds(diastolic.recordedAt()))) {
            throw invalid("Systolic and diastolic readings must have the same time");
        }
    }

    // ==================== HELPERS ====================

    private static String normalizeSource(String source) {
        String value = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(value)) {
            throw new IllegalArgumentException("Unknown health metric source");
        }
        return value;
    }

    /** NUMERIC(10,3): at most 7 integer digits and 3 decimals. */
    static boolean fitsColumn(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        int integerDigits = stripped.precision() - stripped.scale();
        return scale <= MAX_DECIMALS && integerDigits <= MAX_INTEGER_DIGITS;
    }

    private static String whole(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static LocalDateTime seconds(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException("VALIDATION_FAILED", message);
    }
}
