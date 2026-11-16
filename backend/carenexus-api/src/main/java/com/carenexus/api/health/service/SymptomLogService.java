package com.carenexus.api.health.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.health.dto.request.CreateSymptomLogRequest;
import com.carenexus.api.health.dto.response.SymptomLogResponse;
import com.carenexus.api.health.model.SymptomLog;
import com.carenexus.api.health.repository.HealthMetricSpecifications;
import com.carenexus.api.health.repository.SymptomLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Symptom logs over HTTP: create, paged history, one log, and soft delete by the author within 24 hours.
 *
 * Access is the health-metric access of CONTRACT be-health-tracking 1.1 (reads {@code HEALTH_METRICS}, writes
 * {@code MANAGE_HEALTH_METRICS}, administrators read only). A PATIENT or CAREGIVER author stores
 * {@code PATIENT_REPORTED}, a DOCTOR {@code DOCTOR_ENTERED}. Each log publishes a {@link SymptomLoggedEvent}; a
 * SEVERE or CRITICAL log also notifies the patient's related doctors (except the author). Every successful read and
 * write is audited ({@code SYMPTOM_LOG}).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SymptomLogService {

    public static final String RESOURCE = "SYMPTOM_LOG";

    static final Duration DELETE_WINDOW = Duration.ofHours(24);
    static final String NOT_FOUND = "Symptom log not found";
    static final String NOT_AUTHOR = "Only the person who logged these symptoms can delete them";
    static final String TOO_LATE = "Symptom logs can only be deleted within 24 hours";
    static final String UNKNOWN_SEVERITY = "Unknown symptom severity";

    private static final Set<String> SEVERITIES = Set.of(SymptomLog.SEVERITY_MILD, SymptomLog.SEVERITY_MODERATE,
            SymptomLog.SEVERITY_SEVERE, SymptomLog.SEVERITY_CRITICAL);
    private static final Sort HISTORY_ORDER = Sort.by(Sort.Order.desc("loggedAt"), Sort.Order.desc("symptomId"));

    private final SymptomLogRepository symptomLogRepository;
    private final HealthSupport support;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationPublisher notificationPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ==================== WRITE ====================

    public SymptomLogResponse create(CreateSymptomLogRequest request) {
        HealthSupport.Caller caller = support.caller();
        String patient = support.requireWritablePatient(caller, request.getPatientSystemId());

        String severity = normalizeSeverity(request.getSeverity());
        if (severity == null) {
            throw new BadRequestException("VALIDATION_FAILED", "severity: must be one of MILD, MODERATE, SEVERE, CRITICAL");
        }
        List<String> symptoms = request.getSymptoms().stream().map(String::trim).toList();
        SymptomLog saved = symptomLogRepository.save(SymptomLog.builder()
                .patientSystemId(patient)
                .loggedBySystemId(caller.systemId())
                .symptoms(writeSymptoms(symptoms))
                .severity(severity)
                .durationDays(request.getDurationDays())
                .notes(trimToNull(request.getNotes()))
                .source(caller.isDoctor()
                        ? SymptomLog.SOURCE_DOCTOR_ENTERED : SymptomLog.SOURCE_PATIENT_REPORTED)
                .loggedAt(now())
                .isDeleted(false)
                .build());

        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getSymptomId(), patient);
        eventPublisher.publishEvent(new SymptomLoggedEvent(saved.getSymptomId(), patient, caller.systemId(), severity));
        if (SymptomLog.SEVERITY_SEVERE.equals(severity) || SymptomLog.SEVERITY_CRITICAL.equals(severity)) {
            notifyCareTeam(saved, caller.systemId());
        }
        return toResponse(saved, support.names(List.of(caller.systemId())), caller);
    }

    /**
     * Soft delete by the author (a doctor or caregiver author must still hold write access), within 24 hours of
     * loggedAt. Checks: 404, 403, 409. A notification already sent is not withdrawn.
     */
    public void delete(Integer symptomId) {
        HealthSupport.Caller caller = support.caller();
        SymptomLog entry = symptomLogRepository.findBySymptomIdAndIsDeletedFalse(symptomId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        String patient = entry.getPatientSystemId();
        if (!caller.systemId().equals(entry.getLoggedBySystemId())) {
            throw support.denyNotOwner(caller, RESOURCE, symptomId, patient, NOT_AUTHOR);
        }
        // The author still needs write access: a patient always has it, a doctor or caregiver may have lost it.
        support.requireWriteAccess(patient, symptomId);
        LocalDateTime now = now();
        if (!withinDeleteWindow(entry, now)) {
            throw new ConflictException(TOO_LATE);
        }
        entry.setIsDeleted(true);
        entry.setDeletedAt(now);
        symptomLogRepository.save(entry);
        auditService.logClinicalAccess("DELETE", RESOURCE, symptomId, patient);
    }

    // ==================== READ ====================

    @Transactional(readOnly = true)
    public PageResponse<SymptomLogResponse> history(String patientSystemId, String severityFilter, LocalDate from,
                                                    LocalDate to, int page, int size) {
        HealthSupport.Caller caller = support.caller();
        support.requireReadAccess(patientSystemId, null);
        String severity = null;
        if (severityFilter != null && !severityFilter.isBlank()) {
            severity = normalizeSeverity(severityFilter);
            if (severity == null) {
                throw new BadRequestException("VALIDATION_FAILED", UNKNOWN_SEVERITY);
            }
        }
        LocalDateTime[] range = HealthSupport.dayRange(from, to);

        Page<SymptomLog> rows = symptomLogRepository.findAll(
                HealthMetricSpecifications.symptomLogs(patientSystemId, severity, range[0], range[1]),
                PageParams.of(page, size, HISTORY_ORDER));
        auditService.logListRead(RESOURCE, rows.getContent(), SymptomLog::getPatientSystemId);
        Map<String, String> names = support.names(rows.getContent().stream().map(SymptomLog::getLoggedBySystemId).toList());
        return PageResponse.of(rows, rows.getContent().stream().map(row -> toResponse(row, names, caller)).toList());
    }

    @Transactional(readOnly = true)
    public SymptomLogResponse get(Integer symptomId) {
        HealthSupport.Caller caller = support.caller();
        SymptomLog entry = symptomLogRepository.findBySymptomIdAndIsDeletedFalse(symptomId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        support.requireReadAccess(entry.getPatientSystemId(), symptomId);
        auditService.logClinicalAccess("READ", RESOURCE, symptomId, entry.getPatientSystemId());
        return toResponse(entry, support.names(Collections.singletonList(entry.getLoggedBySystemId())), caller);
    }

    // ==================== NOTIFICATION ====================

    /**
     * SEVERE / CRITICAL: every related doctor except the author gets an in-app notification (category CRITICAL,
     * priority HIGH or CRITICAL, target HEALTH_TRACKING). The text names the patient, never the symptoms.
     */
    private void notifyCareTeam(SymptomLog entry, String authorSystemId) {
        boolean critical = SymptomLog.SEVERITY_CRITICAL.equals(entry.getSeverity());
        String patient = entry.getPatientSystemId();
        String patientName = support.names(List.of(patient)).get(patient);
        String who = patientName == null ? patient : patientName;
        String word = critical ? "Critical" : "Severe";
        notificationPublisher.publishToCareTeam(patient, NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.CRITICAL)
                .priority(critical ? NotificationPriority.CRITICAL : NotificationPriority.HIGH)
                .title(word + " symptoms logged")
                .message(word + " symptoms were logged for " + who + ". Please review the symptom log.")
                .target(NotificationTarget.HEALTH_TRACKING)
                .resourceType(RESOURCE)
                .resourceId(entry.getSymptomId())
                .patientSystemId(patient)
                .actorSystemId(authorSystemId)
                .dedupeKey("SYMPTOM_LOGGED:" + entry.getSymptomId())
                .build());
    }

    // ==================== MAPPING ====================

    private SymptomLogResponse toResponse(SymptomLog row, Map<String, String> names, HealthSupport.Caller caller) {
        String author = row.getLoggedBySystemId();
        return SymptomLogResponse.builder()
                .symptomId(row.getSymptomId())
                .patientSystemId(row.getPatientSystemId())
                .symptoms(readSymptoms(row.getSymptoms()))
                .severity(row.getSeverity())
                .durationDays(row.getDurationDays())
                .notes(row.getNotes())
                .source(row.getSource())
                .loggedAt(row.getLoggedAt())
                .loggedBySystemId(author)
                .loggedByName(author == null ? null : names.get(author))
                .canDelete(author != null && author.equals(caller.systemId()) && withinDeleteWindow(row, now()))
                .build();
    }

    private static boolean withinDeleteWindow(SymptomLog entry, LocalDateTime now) {
        return entry.getLoggedAt() != null && now.isBefore(entry.getLoggedAt().plus(DELETE_WINDOW));
    }

    private String writeSymptoms(List<String> symptoms) {
        try {
            return objectMapper.writeValueAsString(symptoms);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the symptom list", e);
        }
    }

    /** The stored JSON as a list of strings; anything that is not an array of strings becomes an empty list. */
    List<String> readSymptoms(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> symptoms = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    return List.of();
                }
                symptoms.add(item.asText());
            }
            return symptoms;
        } catch (JsonProcessingException e) {
            log.warn("Symptom log with unreadable symptoms JSON ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    static String normalizeSeverity(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return SEVERITIES.contains(normalized) ? normalized : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
