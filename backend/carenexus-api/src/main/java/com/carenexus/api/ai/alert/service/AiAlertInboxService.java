package com.carenexus.api.ai.alert.service;

import com.carenexus.api.ai.alert.dto.response.AiAlertResponse;
import com.carenexus.api.ai.alert.dto.response.UnacknowledgedAlertCountResponse;
import com.carenexus.api.ai.alert.model.AiAlert;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.alert.repository.AiAlertRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The AI alert inbox (api/be-ai-core.md section 4): the clinician list and badge count, acknowledgement, and one
 * patient's alerts with the patient-facing filter for patients and caregivers. Every method starts with its
 * authorization check; filtering and ordering happen in the database.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiAlertInboxService {

    public static final String RESOURCE = "AI_ALERT";

    static final Pattern PATIENT_SYSTEM_ID = Pattern.compile("^PAT-\\d{5,}$");
    static final String PATIENT_ID_MESSAGE = "patientSystemId: must be a patient system id like PAT-00001";
    static final String PRIORITY_MESSAGE = "priority: must be one of LOW, MEDIUM, HIGH, CRITICAL";
    static final String TYPE_MESSAGE = "type: must be a known alert type";
    static final String ACKNOWLEDGED_MESSAGE = "Invalid value for parameter 'acknowledged'";

    /** Fixed order of both lists: newest first, ties broken by id (the queries carry the ORDER BY). */
    private static final Sort UNSORTED = Sort.unsorted();

    private final AiAlertRepository aiAlertRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== 4.1 inbox ====================

    /**
     * The clinician inbox: a DOCTOR sees the alerts addressed to them or about a patient they are related to, an
     * ADMIN sees every alert. PATIENT and CAREGIVER get 403. A non-empty page is audited per patient.
     *
     * @param acknowledged    "true", "false" or null/blank for both
     * @param priority        one priority (any case) or null/blank for all
     * @param type            one alert type (any case) or null/blank for all
     * @param patientSystemId narrows the caller's visible set to one patient; never widens it
     */
    public PageResponse<AiAlertResponse> list(String acknowledged, String priority, String type,
                                              String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        Boolean acknowledgedFilter = parseAcknowledged(acknowledged);
        String priorityFilter = parsePriority(priority);
        String typeFilter = parseType(type);
        String patientFilter = blankToNull(patientSystemId);
        if (patientFilter != null) {
            patientFilter = requirePatientSystemId(patientFilter);
        }

        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        String viewer = AuthorizationService.ADMIN.equals(role) ? null : caller;

        Pageable pageable = PageParams.of(page, size, UNSORTED);
        Page<AiAlert> rows = aiAlertRepository.findInbox(viewer, acknowledgedFilter, priorityFilter, typeFilter,
                patientFilter, pageable);
        List<AiAlertResponse> content = toResponses(rows.getContent(), AuthorizationService.DOCTOR.equals(role), false);
        auditService.logListRead(RESOURCE, content, AiAlertResponse::getPatientSystemId);
        return PageResponse.of(rows, content);
    }

    // ==================== 4.2 badge count ====================

    /** Unacknowledged alerts visible to the DOCTOR (or every one for an ADMIN). Not audited: it is a poll. */
    public UnacknowledgedAlertCountResponse unacknowledgedCount() {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        String viewer = authorizationService.isAdmin() ? null : authorizationService.currentSystemId();
        return UnacknowledgedAlertCountResponse.of(aiAlertRepository.countInbox(viewer, Boolean.FALSE, null, null, null));
    }

    // ==================== 4.3 acknowledge ====================

    /**
     * A DOCTOR acknowledges an alert visible to them (addressed to them, or about a related patient). Idempotent:
     * a repeat keeps the first acknowledger and time and writes no audit row. Check order: role, existence,
     * visibility.
     */
    @Transactional
    public void acknowledge(Integer alertId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        AiAlert alert = aiAlertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found"));
        if (!caller.equals(alert.getDoctorSystemId())) {
            // Not addressed to the caller: they must be related to the patient (denial is audited, 403).
            authorizationService.requirePatientAccess(alert.getPatientSystemId(), PatientPermission.AI_ALERTS, alertId);
        }
        int changed = aiAlertRepository.acknowledge(alertId, caller, LocalDateTime.now(clock));
        if (changed > 0) {
            auditService.logClinicalAccess("UPDATE", RESOURCE, alertId, alert.getPatientSystemId());
        }
    }

    // ==================== 4.4 one patient ====================

    /**
     * One patient's alerts. PATIENT (own id) and CAREGIVER (grant with AI_ALERTS) get only the non-diagnostic types
     * and alerts with a CONFIRMED detection, without suggestedAction; a related DOCTOR and an ADMIN get every
     * alert. Check order: format, authorization, existence (an ADMIN asking for an unknown patient gets 404).
     */
    public PageResponse<AiAlertResponse> listForPatient(String patientSystemId, int page, int size) {
        String patient = requirePatientSystemId(patientSystemId);
        authorizationService.requirePatientAccess(patient, PatientPermission.AI_ALERTS);
        String role = authorizationService.currentRole();
        if (AuthorizationService.ADMIN.equals(role) && !aiAlertRepository.patientExists(patient)) {
            throw new ResourceNotFoundException("Patient not found");
        }
        boolean patientFacing = AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role);

        Pageable pageable = PageParams.of(page, size, UNSORTED);
        Page<AiAlert> rows = aiAlertRepository.findForPatient(patient, patientFacing, pageable);
        List<AiAlertResponse> content = toResponses(rows.getContent(), AuthorizationService.DOCTOR.equals(role),
                patientFacing);
        auditService.logListRead(RESOURCE, content, AiAlertResponse::getPatientSystemId);
        return PageResponse.of(rows, content);
    }

    // ==================== mapping ====================

    /**
     * Maps a page of rows with two batch lookups (patient names, detection review states).
     *
     * @param doctorCaller  the caller is a DOCTOR to whom every row is visible: unacknowledged rows can be acknowledged
     * @param patientFacing the caller is the patient or a caregiver: suggestedAction is left out
     */
    private List<AiAlertResponse> toResponses(List<AiAlert> rows, boolean doctorCaller, boolean patientFacing) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        List<String> patientIds = rows.stream().map(AiAlert::getPatientSystemId).filter(Objects::nonNull).distinct().toList();
        aiAlertRepository.findNames(patientIds).forEach(row -> names.put(row.getSystemId(),
                fullName(row.getFirstName(), row.getLastName())));

        Map<Integer, String> reviewStatuses = new HashMap<>();
        List<Integer> detectionIds = rows.stream().map(AiAlert::getDetectionId).filter(Objects::nonNull).distinct().toList();
        if (!detectionIds.isEmpty()) {
            aiAlertRepository.findDetectionReviewStatuses(detectionIds)
                    .forEach(row -> reviewStatuses.put(row.getDetectionId(), row.getReviewStatus()));
        }

        return rows.stream().map(alert -> {
            boolean acknowledged = Boolean.TRUE.equals(alert.getIsAcknowledged());
            String name = names.get(alert.getPatientSystemId());
            return AiAlertResponse.builder()
                    .alertId(alert.getAlertId())
                    .patientSystemId(alert.getPatientSystemId())
                    .patientName(name == null || name.isEmpty() ? alert.getPatientSystemId() : name)
                    .doctorSystemId(alert.getDoctorSystemId())
                    .detectionId(alert.getDetectionId())
                    .detectionReviewStatus(alert.getDetectionId() != null ? reviewStatuses.get(alert.getDetectionId()) : null)
                    .alertType(alert.getAlertType())
                    .priority(alert.getPriority())
                    .title(alert.getTitle() == null || alert.getTitle().isBlank()
                            ? AiAlertType.defaultTitleOf(alert.getAlertType()) : alert.getTitle())
                    .message(alert.getMessage())
                    .actionRequired(Boolean.TRUE.equals(alert.getActionRequired()))
                    .suggestedAction(patientFacing ? null : alert.getSuggestedAction())
                    .sourceType(alert.getSourceType())
                    .sourceId(alert.getSourceId())
                    .acknowledged(acknowledged)
                    .acknowledgedBySystemId(alert.getAcknowledgedBySystemId())
                    .acknowledgedAt(toInstant(alert.getAcknowledgedAt()))
                    .createdAt(toInstant(alert.getCreatedAt()))
                    .canAcknowledge(doctorCaller && !acknowledged)
                    .disclaimer(AiAlertResponse.DISCLAIMER)
                    .build();
        }).toList();
    }

    private static String fullName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    /** The column holds clinic wall-clock time; the JVM default zone is the clinic zone. */
    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    // ==================== parameter parsing ====================

    private static Boolean parseAcknowledged(String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        throw new BadRequestException(ACKNOWLEDGED_MESSAGE);
    }

    private static String parsePriority(String value) {
        if (blankToNull(value) == null) {
            return null;
        }
        return AiAlertPriority.parse(value)
                .map(Enum::name)
                .orElseThrow(() -> new BadRequestException(ErrorResponse.VALIDATION_FAILED, PRIORITY_MESSAGE));
    }

    private static String parseType(String value) {
        if (blankToNull(value) == null) {
            return null;
        }
        return AiAlertType.parse(value)
                .map(Enum::name)
                .orElseThrow(() -> new BadRequestException(ErrorResponse.VALIDATION_FAILED, TYPE_MESSAGE));
    }

    private static String requirePatientSystemId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!PATIENT_SYSTEM_ID.matcher(trimmed).matches()) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, PATIENT_ID_MESSAGE);
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
