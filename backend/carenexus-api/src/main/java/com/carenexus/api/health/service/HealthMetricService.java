package com.carenexus.api.health.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.health.dto.request.HealthMetricReadingRequest;
import com.carenexus.api.health.dto.request.RecordHealthMetricsRequest;
import com.carenexus.api.health.dto.response.HealthMetricResponse;
import com.carenexus.api.health.dto.response.HealthMetricTrendResponse;
import com.carenexus.api.health.dto.response.LatestHealthMetricsResponse;
import com.carenexus.api.health.dto.response.RecordHealthMetricsResponse;
import com.carenexus.api.health.model.HealthMetric;
import com.carenexus.api.health.model.MetricType;
import com.carenexus.api.health.repository.HealthMetricRepository;
import com.carenexus.api.health.repository.HealthMetricSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Health readings over HTTP: record a measurement session, the paged history, the latest reading per type with the
 * derived BMI, the daily trend, and removal of a mistyped manual reading.
 *
 * Access (CONTRACT be-health-tracking 1.1): reads need {@code HEALTH_METRICS} (patient self, related doctor,
 * caregiver with can_view_records or FULL_ACCESS, admin); writes need {@code MANAGE_HEALTH_METRICS} and are refused
 * to administrators. Every successful read and write is audited ({@code HEALTH_METRIC}).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class HealthMetricService {

    public static final String RESOURCE = HealthMetricIngestService.RESOURCE;
    public static final int MAX_TREND_DAYS = 366;

    static final String NOT_FOUND = "Health metric not found";
    static final String NOT_OWNER = "Only the patient or the person who recorded a reading can delete it";
    static final String NOT_MANUAL = "Only manually entered readings can be deleted";
    static final String UNKNOWN_TYPE = "Unknown health metric type";

    private static final Sort HISTORY_ORDER = Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("metricId"));

    private final HealthMetricRepository healthMetricRepository;
    private final HealthMetricIngestService ingestService;
    private final VitalRangeEvaluator vitalRangeEvaluator;
    private final HealthSupport support;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== WRITE ====================

    /**
     * Stores one manual measurement session for the patient (self for a PATIENT caller): source MANUAL, recorder =
     * caller, one reading group. Publishes one HealthMetricRecordedEvent and one CREATE audit row per reading.
     */
    public RecordHealthMetricsResponse record(RecordHealthMetricsRequest request) {
        HealthSupport.Caller caller = support.caller();
        String patient = support.requireWritablePatient(caller, request.getPatientSystemId());
        List<MetricReading> readings = request.getReadings().stream()
                .map(HealthMetricService::toReading)
                .toList();
        List<HealthMetric> saved = ingestService.recordManual(patient, caller.systemId(), readings);
        return RecordHealthMetricsResponse.builder()
                .patientSystemId(patient)
                .readingGroupId(saved.get(0).getReadingGroupId())
                .metrics(toResponses(saved, caller))
                .build();
    }

    /**
     * Removes a MANUAL reading (and its blood-pressure partner). Allowed for the patient, and for the doctor or
     * caregiver who recorded it while they still hold write access. Checks: 404, 403, 409.
     */
    public void delete(Integer metricId) {
        HealthSupport.Caller caller = support.caller();
        HealthMetric metric = healthMetricRepository.findById(metricId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        String patient = metric.getPatientSystemId();
        boolean isPatient = caller.isPatient() && caller.systemId().equals(patient);
        boolean isRecorder = caller.actsForPatient() && caller.systemId().equals(metric.getRecordedBySystemId());
        if (!isPatient && !isRecorder) {
            throw support.denyNotOwner(caller, RESOURCE, metricId, patient, NOT_OWNER);
        }
        if (isRecorder) {
            support.requireWriteAccess(patient, metricId);
        }
        if (!HealthMetric.SOURCE_MANUAL.equals(metric.getSource())) {
            throw new ConflictException(NOT_MANUAL);
        }

        List<HealthMetric> removed = new ArrayList<>();
        removed.add(metric);
        MetricType.parse(metric.getType())
                .flatMap(MetricType::bloodPressurePartner)
                .filter(partner -> metric.getReadingGroupId() != null)
                .flatMap(partner -> healthMetricRepository.findFirstByPatientSystemIdAndReadingGroupIdAndType(
                        patient, metric.getReadingGroupId(), partner.name()))
                .filter(partner -> HealthMetric.SOURCE_MANUAL.equals(partner.getSource()))
                .ifPresent(removed::add);
        healthMetricRepository.deleteAll(removed);
        for (HealthMetric row : removed) {
            auditService.logClinicalAccess("DELETE", RESOURCE, row.getMetricId(), patient);
        }
    }

    // ==================== READ ====================

    /** Paged history, newest first (recordedAt DESC, metricId DESC), with optional type and clinic-day filters. */
    @Transactional(readOnly = true)
    public PageResponse<HealthMetricResponse> history(String patientSystemId, String typeFilter, LocalDate from,
                                                      LocalDate to, int page, int size) {
        HealthSupport.Caller caller = support.caller();
        support.requireReadAccess(patientSystemId, null);
        Set<String> types = parseTypes(typeFilter);
        LocalDateTime[] range = HealthSupport.dayRange(from, to);

        Page<HealthMetric> rows = healthMetricRepository.findAll(
                HealthMetricSpecifications.metrics(patientSystemId, types, range[0], range[1]),
                PageParams.of(page, size, HISTORY_ORDER));
        auditService.logListRead(RESOURCE, rows.getContent(), HealthMetric::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent(), caller));
    }

    /** The latest reading of each type in canonical order, plus the BMI derived from the latest weight and height. */
    @Transactional(readOnly = true)
    public LatestHealthMetricsResponse latest(String patientSystemId) {
        HealthSupport.Caller caller = support.caller();
        support.requireReadAccess(patientSystemId, null);

        List<HealthMetric> rows = new ArrayList<>(healthMetricRepository.findLatestPerType(patientSystemId));
        rows.sort(Comparator.comparingInt(row -> MetricType.parse(row.getType()).map(Enum::ordinal).orElse(Integer.MAX_VALUE)));
        auditService.logListRead(RESOURCE, rows, HealthMetric::getPatientSystemId);

        HealthMetric weight = latestOf(rows, MetricType.WEIGHT);
        HealthMetric height = latestOf(rows, MetricType.HEIGHT);
        return LatestHealthMetricsResponse.builder()
                .patientSystemId(patientSystemId)
                .metrics(toResponses(rows, caller))
                .bmi(bmi(weight, height))
                .build();
    }

    /**
     * Daily aggregation of one type over the last {@code days} clinic days (today included). Days without readings
     * are omitted, so there are at most {@code days} points.
     */
    @Transactional(readOnly = true)
    public HealthMetricTrendResponse trend(String patientSystemId, String typeParam, int days) {
        support.requireReadAccess(patientSystemId, null);
        MetricType type = MetricType.parse(typeParam)
                .orElseThrow(() -> new BadRequestException("VALIDATION_FAILED", UNKNOWN_TYPE));
        if (days < 1) {
            throw new BadRequestException("VALIDATION_FAILED", "days: must be greater than or equal to 1");
        }
        if (days > MAX_TREND_DAYS) {
            throw new BadRequestException("VALIDATION_FAILED", "days: must be less than or equal to " + MAX_TREND_DAYS);
        }
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);

        List<HealthMetricTrendResponse.Point> points = healthMetricRepository
                .findDailyAggregates(patientSystemId, type.name(), from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .map(day -> HealthMetricTrendResponse.Point.builder()
                        .date(day.getDay())
                        .avg(HealthSupport.plain(day.getAvgValue().setScale(2, RoundingMode.HALF_UP)))
                        .min(HealthSupport.plain(day.getMinValue()))
                        .max(HealthSupport.plain(day.getMaxValue()))
                        .count(day.getReadings().intValue())
                        .abnormal(Boolean.TRUE.equals(day.getAnyAbnormal()))
                        .build())
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return HealthMetricTrendResponse.builder()
                .patientSystemId(patientSystemId)
                .type(type.name())
                .unit(type.unit())
                .days(days)
                .from(from)
                .to(to)
                .points(points)
                .build();
    }

    // ==================== MAPPING ====================

    private List<HealthMetricResponse> toResponses(List<HealthMetric> rows, HealthSupport.Caller caller) {
        Map<String, String> names = support.names(rows.stream().map(HealthMetric::getRecordedBySystemId).toList());
        return rows.stream().map(row -> toResponse(row, names, caller)).toList();
    }

    private static HealthMetricResponse toResponse(HealthMetric row, Map<String, String> names, HealthSupport.Caller caller) {
        return HealthMetricResponse.builder()
                .metricId(row.getMetricId())
                .patientSystemId(row.getPatientSystemId())
                .type(row.getType())
                .value(HealthSupport.plain(row.getValue()))
                .unit(row.getUnit())
                .source(row.getSource())
                .deviceId(row.getDeviceId())
                .readingGroupId(row.getReadingGroupId())
                .isAbnormal(Boolean.TRUE.equals(row.getIsAbnormal()))
                .severity(row.getSeverity())
                .notes(row.getNotes())
                .recordedAt(row.getRecordedAt())
                .recordedBySystemId(row.getRecordedBySystemId())
                .recordedByName(row.getRecordedBySystemId() == null ? null : names.get(row.getRecordedBySystemId()))
                .canDelete(canDelete(row, caller))
                .build();
    }

    /** MANUAL, and the caller is the patient or the doctor/caregiver who recorded it (re-checked on DELETE). */
    static boolean canDelete(HealthMetric row, HealthSupport.Caller caller) {
        if (!HealthMetric.SOURCE_MANUAL.equals(row.getSource())) {
            return false;
        }
        if (caller.isPatient()) {
            return caller.systemId().equals(row.getPatientSystemId());
        }
        return caller.actsForPatient() && caller.systemId().equals(row.getRecordedBySystemId());
    }

    private LatestHealthMetricsResponse.Bmi bmi(HealthMetric weight, HealthMetric height) {
        if (weight == null || height == null) {
            return null;
        }
        BigDecimal value = vitalRangeEvaluator.bmi(weight.getValue(), height.getValue());
        if (value == null) {
            return null;
        }
        String severity = vitalRangeEvaluator.evaluate(MetricType.BMI, value);
        LocalDateTime recordedAt = weight.getRecordedAt().isAfter(height.getRecordedAt())
                ? weight.getRecordedAt() : height.getRecordedAt();
        return LatestHealthMetricsResponse.Bmi.builder()
                .value(value)
                .unit(MetricType.BMI.unit())
                .severity(severity)
                .isAbnormal(VitalRangeEvaluator.isAbnormal(severity))
                .weightMetricId(weight.getMetricId())
                .heightMetricId(height.getMetricId())
                .recordedAt(recordedAt)
                .build();
    }

    private static HealthMetric latestOf(List<HealthMetric> rows, MetricType type) {
        return rows.stream().filter(row -> type.name().equals(row.getType())).findFirst().orElse(null);
    }

    /** "BP_SYSTOLIC,bp_diastolic" -> {BP_SYSTOLIC, BP_DIASTOLIC}; blank -> no filter; any unknown entry -> 400. */
    static Set<String> parseTypes(String typeFilter) {
        Set<String> types = new LinkedHashSet<>();
        if (typeFilter == null || typeFilter.isBlank()) {
            return types;
        }
        for (String part : typeFilter.split(",")) {
            Optional<MetricType> type = MetricType.parse(part);
            if (type.isEmpty()) {
                throw new BadRequestException("VALIDATION_FAILED", UNKNOWN_TYPE);
            }
            types.add(type.get().name());
        }
        return types;
    }

    private static MetricReading toReading(HealthMetricReadingRequest reading) {
        Objects.requireNonNull(reading, "reading");
        return new MetricReading(reading.getType(), reading.getValue(), reading.getUnit(), reading.getRecordedAt(),
                reading.getNotes());
    }
}
