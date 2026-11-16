package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.detection.dto.request.ReviewDetectionRequest;
import com.carenexus.api.ai.detection.dto.response.DetectedConditionResponse;
import com.carenexus.api.ai.detection.dto.response.DetectionRunResponse;
import com.carenexus.api.ai.detection.dto.response.DiseaseDetectionResponse;
import com.carenexus.api.ai.detection.model.DetectionReviewStatus;
import com.carenexus.api.ai.detection.model.DetectionRunOutcome;
import com.carenexus.api.ai.detection.model.DiseaseDetection;
import com.carenexus.api.ai.detection.repository.DetectionSourceDataRepository;
import com.carenexus.api.ai.detection.repository.DiseaseDetectionRepository;
import com.carenexus.api.ai.detection.service.DetectionPipeline.ScreeningResult;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.service.ConsultationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Disease detections over HTTP (api/be-ai-detection.md section 4): one patient's detections, one detection, a
 * doctor's review and a doctor's manual screening run. Every method starts with its authorization check; filtering,
 * ordering and counting happen in the database.
 * <p>
 * Visibility (1.7): a related DOCTOR and an ADMIN see every detection; the PATIENT and caregivers whose grant includes
 * records ({@link PatientPermission#AI_ALERTS}) see CONFIRMED detections only, without recommendations or review
 * notes. Review (2.2): PENDING_REVIEW to CONFIRMED or REJECTED by a related doctor; both are terminal (409).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiseaseDetectionService {

    public static final String RESOURCE = "DISEASE_DETECTION";

    static final Pattern PATIENT_SYSTEM_ID = Pattern.compile("^PAT-\\d{5,}$");
    static final String PATIENT_ID_MESSAGE = "patientSystemId: must be a patient system id like PAT-00001";
    static final String REVIEW_STATUS_MESSAGE = "reviewStatus: must be one of PENDING_REVIEW, CONFIRMED, REJECTED";
    static final String NOT_FOUND = "Detection not found";
    static final String PATIENT_NOT_FOUND = "Patient not found";
    static final String ALREADY_REVIEWED = "This detection has already been reviewed";
    static final String PROVIDER_OFF = "Automated screening is switched off";
    static final String PROVIDER_FAILED = "Automated screening could not be completed. Try again later.";

    private final DiseaseDetectionRepository detectionRepository;
    private final DetectionSourceDataRepository sourceData;
    private final DetectionPipeline pipeline;
    private final AuthorizationService authorizationService;
    private final ConsultationService consultationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ==================== 4.1 one patient's detections ====================

    /**
     * One patient's detections, newest first. Check order: format, authorization, existence (an ADMIN asking for an
     * unknown patient gets 404). {@code reviewStatus} is validated for every caller and honoured for DOCTOR and
     * ADMIN only. A non-empty page is audited.
     */
    public PageResponse<DiseaseDetectionResponse> listForPatient(String patientSystemId, String reviewStatus,
                                                                  int page, int size) {
        String patient = requirePatientSystemId(patientSystemId);
        DetectionReviewStatus statusFilter = parseReviewStatus(reviewStatus);
        authorizationService.requirePatientAccess(patient, PatientPermission.AI_ALERTS);
        String role = authorizationService.currentRole();
        if (AuthorizationService.ADMIN.equals(role) && !sourceData.patientExists(patient)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND);
        }
        boolean patientFacing = isPatientFacing(role);
        String effectiveFilter = patientFacing ? DetectionReviewStatus.CONFIRMED.name()
                : statusFilter != null ? statusFilter.name() : null;

        Pageable pageable = PageParams.of(page, size, Sort.unsorted());
        Page<DiseaseDetection> rows = detectionRepository.findForPatient(patient, effectiveFilter, pageable);
        List<DiseaseDetectionResponse> content = toResponses(rows.getContent(), role);
        auditService.logListRead(RESOURCE, content, DiseaseDetectionResponse::getPatientSystemId);
        return PageResponse.of(rows, content);
    }

    // ==================== 4.2 one detection ====================

    /**
     * One detection. Check order: existence, authorization, visibility (a pending or rejected detection answers 404
     * to the patient and caregivers, without an audit row).
     */
    public DiseaseDetectionResponse get(Integer detectionId) {
        DiseaseDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        authorizationService.requirePatientAccess(detection.getPatientSystemId(), PatientPermission.AI_ALERTS,
                detectionId);
        String role = authorizationService.currentRole();
        if (isPatientFacing(role) && !DetectionReviewStatus.CONFIRMED.name().equals(detection.getReviewStatus())) {
            throw new ResourceNotFoundException(NOT_FOUND);
        }
        auditService.logClinicalAccess("READ", RESOURCE, detectionId, detection.getPatientSystemId());
        return toResponses(List.of(detection), role).get(0);
    }

    // ==================== 4.3 review ====================

    /**
     * A related DOCTOR confirms or rejects a PENDING_REVIEW detection. Check order (after body validation): role,
     * existence, relationship, state. The update is conditional on the pending state, so of two concurrent reviews
     * exactly one applies and the other answers 409. No notification and no event.
     */
    @Transactional
    public DiseaseDetectionResponse review(Integer detectionId, ReviewDetectionRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        DiseaseDetection detection = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        consultationService.requireRelatedDoctor(detection.getPatientSystemId(), PatientPermission.AI_ALERTS,
                detectionId);
        if (!DetectionReviewStatus.PENDING_REVIEW.name().equals(detection.getReviewStatus())) {
            throw new ConflictException(ALREADY_REVIEWED);
        }

        String decision = request.getDecision() == null ? "" : request.getDecision().trim().toUpperCase(Locale.ROOT);
        boolean confirm = ReviewDetectionRequest.CONFIRM.equals(decision);
        if (!confirm && !ReviewDetectionRequest.REJECT.equals(decision)) {
            // Bean Validation already refused this; kept so a direct caller cannot store an undefined decision.
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "decision: must be CONFIRM or REJECT");
        }
        DetectionReviewStatus status = confirm ? DetectionReviewStatus.CONFIRMED : DetectionReviewStatus.REJECTED;
        String notes = request.getNotes() == null || request.getNotes().isBlank() ? null : request.getNotes().trim();
        String reviewer = authorizationService.currentSystemId();

        int changed = detectionRepository.review(detectionId, status.name(), notes, reviewer, confirm,
                LocalDateTime.now(clock));
        if (changed == 0) {
            throw new ConflictException(ALREADY_REVIEWED);
        }
        auditService.logClinicalAccess("UPDATE", RESOURCE, detectionId, detection.getPatientSystemId());
        log.info("Detection {} reviewed: {}", detectionId, status);

        DiseaseDetection reviewed = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        return toResponses(List.of(reviewed), AuthorizationService.DOCTOR).get(0);
    }

    // ==================== 4.4 manual run ====================

    /** A manual run's response and whether it created a detection (201) or not (200). */
    public record RunResult(DetectionRunResponse response, boolean created) {
    }

    /**
     * A related DOCTOR screens one patient now, with the pipeline of the automatic triggers. Check order: format,
     * role, relationship, provider. A switched-off, failing or slow provider answers 503 PROVIDER_UNAVAILABLE and
     * writes nothing.
     */
    @Transactional
    public RunResult run(String patientSystemId) {
        String patient = requirePatientSystemId(patientSystemId);
        consultationService.requireRelatedDoctor(patient, PatientPermission.AI_ALERTS, null);
        String doctor = authorizationService.currentSystemId();

        ScreeningResult result;
        try {
            result = pipeline.screen(patient, ScreeningTrigger.manual(doctor));
        } catch (RiskAssessmentException e) {
            log.warn("Manual risk screening of {} by {} not completed: {}", patient, doctor, e.getReason());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                    e.getReason() == RiskAssessmentException.Reason.DISABLED ? PROVIDER_OFF : PROVIDER_FAILED);
        }

        DiseaseDetection detection = result.detection();
        switch (result.outcome()) {
            case DETECTION_CREATED -> auditService.logClinicalAccess("CREATE", RESOURCE, detection.getDetectionId(), patient);
            case DETECTION_EXISTS -> auditService.logClinicalAccess("READ", RESOURCE, detection.getDetectionId(), patient);
            default -> auditService.logClinicalAccess("READ", RESOURCE, null, patient);
        }
        DetectionRunResponse response = DetectionRunResponse.builder()
                .outcome(result.outcome().name())
                .message(result.outcome().message())
                .detection(detection == null ? null : toResponses(List.of(detection), AuthorizationService.DOCTOR).get(0))
                .modelVersion(result.modelVersion())
                .assessedAt(result.assessedAt())
                .disclaimer(DiseaseDetectionResponse.DISCLAIMER)
                .build();
        return new RunResult(response, result.outcome() == DetectionRunOutcome.DETECTION_CREATED);
    }

    // ==================== mapping ====================

    /**
     * Maps rows with one batch name lookup. {@code role} is the caller's role: PATIENT and CAREGIVER get the
     * patient-facing copy; a DOCTOR (already known to be related to every row's patient) may review pending rows.
     */
    private List<DiseaseDetectionResponse> toResponses(List<DiseaseDetection> rows, String role) {
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean patientFacing = isPatientFacing(role);
        boolean doctor = AuthorizationService.DOCTOR.equals(role);

        Map<String, String> names = new HashMap<>();
        List<String> patientIds = rows.stream().map(DiseaseDetection::getPatientSystemId).distinct().toList();
        sourceData.findNames(patientIds).forEach(row -> names.put(row.getSystemId(),
                fullName(row.getFirstName(), row.getLastName())));

        List<DiseaseDetectionResponse> responses = new ArrayList<>(rows.size());
        for (DiseaseDetection row : rows) {
            String name = names.get(row.getPatientSystemId());
            boolean pending = DetectionReviewStatus.PENDING_REVIEW.name().equals(row.getReviewStatus());
            responses.add(DiseaseDetectionResponse.builder()
                    .detectionId(row.getDetectionId())
                    .patientSystemId(row.getPatientSystemId())
                    .patientName(name == null || name.isEmpty() ? row.getPatientSystemId() : name)
                    .sourceType(row.getSourceType())
                    .sourceId(row.getSourceId())
                    .requestedBySystemId(row.getDoctorSystemId())
                    .modelVersion(row.getModelVersion())
                    .riskLevel(row.getRiskLevel())
                    .confidenceScore(plain(row.getConfidenceScore()))
                    .detectedConditions(conditions(row.getDetectedConditions()))
                    .recommendations(patientFacing ? List.of() : strings(row.getRecommendations()))
                    .reviewStatus(row.getReviewStatus())
                    .reviewNotes(patientFacing ? null : row.getReviewNotes())
                    .reviewedBySystemId(row.getReviewedBySystemId())
                    .reviewedAt(toInstant(row.getReviewedAt()))
                    .createdAt(toInstant(row.getCreatedAt()))
                    .canReview(doctor && pending)
                    .disclaimer(DiseaseDetectionResponse.DISCLAIMER)
                    .build());
        }
        return responses;
    }

    /** Stored conditions; a row whose JSON has another shape (legacy data) yields only its well-formed entries. */
    private List<DetectedConditionResponse> conditions(String json) {
        JsonNode array = readArray(json);
        List<DetectedConditionResponse> conditions = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isObject() || !item.path("code").isTextual() || !item.path("name").isTextual()) {
                continue;
            }
            List<String> evidence = new ArrayList<>();
            item.path("evidence").forEach(entry -> {
                if (entry.isTextual()) {
                    evidence.add(entry.asText());
                }
            });
            JsonNode confidence = item.path("confidence");
            conditions.add(DetectedConditionResponse.builder()
                    .code(item.path("code").asText())
                    .name(item.path("name").asText())
                    .riskLevel(item.path("riskLevel").isTextual() ? item.path("riskLevel").asText() : null)
                    .confidence(confidence.isNumber() ? plain(confidence.decimalValue()) : null)
                    .evidence(evidence)
                    .build());
        }
        return conditions;
    }

    private List<String> strings(String json) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : readArray(json)) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private JsonNode readArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            log.debug("Detection JSON could not be read ({})", e.getClass().getSimpleName());
            return objectMapper.createArrayNode();
        }
    }

    /** 0.750 becomes 0.75 (the column keeps three decimals). */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Columns hold clinic wall-clock time, written with the clinic clock. */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }

    private static String fullName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    private static boolean isPatientFacing(String role) {
        return AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role);
    }

    // ==================== parameter parsing ====================

    private static String requirePatientSystemId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!PATIENT_SYSTEM_ID.matcher(trimmed).matches()) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, PATIENT_ID_MESSAGE);
        }
        return trimmed;
    }

    private static DetectionReviewStatus parseReviewStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return DetectionReviewStatus.parse(value)
                .orElseThrow(() -> new BadRequestException(ErrorResponse.VALIDATION_FAILED, REVIEW_STATUS_MESSAGE));
    }
}
