package com.carenexus.api.core.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateLabResultRequest;
import com.carenexus.api.core.dto.request.ReviewResultRequest;
import com.carenexus.api.core.dto.request.UpdateLabResultRequest;
import com.carenexus.api.core.dto.response.LabResultResponse;
import com.carenexus.api.core.model.LabResult;
import com.carenexus.api.core.repository.LabResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lab results (API contract be-lab-imaging, endpoints L1 to L10).
 *
 * Access, checked here for every method:
 * - create: the treating doctor ({@link ConsultationService#requireTreatingDoctor}); the author is always the caller;
 * - edit (PUT): the author doctor only; cancel (DELETE): the author doctor or an administrator;
 * - status change and review: a DOCTOR with a care relationship to the patient; the reviewer is always the caller;
 * - reads: {@link AuthorizationService#requirePatientAccess} with {@link PatientPermission#LAB_RESULTS}.
 *
 * Status rules: CANCELLED only through DELETE and terminal; a recorded result never goes back to PENDING; moving into
 * REQUIRES_REVIEW clears the review stamp. Criticality is normalised with {@link #normaliseCriticality}. Every
 * successful write publishes exactly one {@link ClinicalResultRecordedEvent} (kind LAB) inside the transaction and
 * writes one clinical audit row. Lists leave out CANCELLED rows unless the canonical list is asked for them.
 *
 * The package-private static helpers at the bottom are shared with {@link ImagingResultService}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LabResultService {

    static final String RESOURCE = "LAB_RESULT";
    private static final PatientPermission PERMISSION = PatientPermission.LAB_RESULTS;

    static final String WRITABLE_STATUS_MESSAGE = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL";
    static final String STATUS_FILTER_MESSAGE = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL, CANCELLED";
    static final String REVIEW_STATUS_MESSAGE = "must be COMPLETED or CRITICAL";
    static final String TEST_TYPE_MESSAGE = "must be one of BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, OTHER";
    static final String CANCELLED_CANNOT_CHANGE = "This result is cancelled and cannot be changed";
    static final String CANCELLED_CANNOT_REVIEW = "A cancelled result cannot be reviewed";
    static final String NO_BACK_TO_PENDING = "A recorded result cannot be moved back to pending";
    static final String DOCUMENT_NOT_FOUND = "documentId: document not found for this patient";

    /** Legacy arrays return at most this many rows. */
    static final int LEGACY_CAP = 200;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("testDate"), Sort.Order.desc("resultId"));
    private static final Pattern DOCTOR_TITLE = Pattern.compile("dr(\\.|\\s|$)", Pattern.CASE_INSENSITIVE);

    private final LabResultRepository labResultRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== WRITES ====================

    /**
     * L1. Records a result as the calling (treating) doctor. Status defaults to PENDING, the date to clinic today.
     */
    public LabResultResponse createLabResult(CreateLabResultRequest request) {
        rejectFutureDate(request.getTestDate(), LocalDate.now(clock), "testDate");
        String testType = requireTestType(request.getTestType());
        String requestedStatus = writableStatusOrNull(request.getStatus());

        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        Criticality state = normaliseCriticality(LabResult.STATUS_PENDING, false, requestedStatus, request.getIsCritical());
        String resultData = jsonObjectOrNull(objectMapper, request.getResultData());
        requireUsableDocument(labResultRepository, request.getDocumentId(), request.getPatientSystemId());

        LabResult labResult = LabResult.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .testName(request.getTestName())
                .testType(testType)
                .resultValue(blankToNull(request.getResultValue()))
                .unit(blankToNull(request.getUnit()))
                .referenceRange(blankToNull(request.getReferenceRange()))
                .resultData(resultData)
                .status(state.status())
                .isCritical(state.critical())
                .testDate(request.getTestDate() != null ? request.getTestDate() : LocalDate.now(clock))
                .documentId(request.getDocumentId())
                .createdAt(LocalDateTime.now(clock))
                .build();

        LabResult saved = labResultRepository.save(labResult);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getResultId(), saved.getPatientSystemId());
        publish(saved, false, true);
        return toResponse(saved);
    }

    /**
     * L5. Partial update by the author doctor. Absent fields are unchanged; blank optional strings clear the field.
     */
    public LabResultResponse updateLabResult(Integer resultId, UpdateLabResultRequest request) {
        rejectFutureDate(request.getTestDate(), LocalDate.now(clock), "testDate");
        String testType = request.getTestType() != null ? requireTestType(request.getTestType()) : null;
        String requestedStatus = request.getStatus() != null ? requireWritableStatus(request.getStatus()) : null;

        LabResult labResult = load(resultId);
        consultationService.requireAuthorDoctor(labResult.getDoctorSystemId(), labResult.getPatientSystemId(),
                RESOURCE, resultId, false);
        requireChangeable(labResult.getStatus(), requestedStatus);

        boolean criticalBefore = Boolean.TRUE.equals(labResult.getIsCritical());
        Criticality state = normaliseCriticality(labResult.getStatus(), criticalBefore, requestedStatus,
                request.getIsCritical());
        String resultData = request.getResultData() != null
                ? jsonObjectOrNull(objectMapper, request.getResultData()) : null;
        if (request.getDocumentId() != null) {
            requireUsableDocument(labResultRepository, request.getDocumentId(), labResult.getPatientSystemId());
        }

        if (request.getTestName() != null) {
            labResult.setTestName(request.getTestName());
        }
        if (testType != null) {
            labResult.setTestType(testType);
        }
        if (request.getResultValue() != null) {
            labResult.setResultValue(blankToNull(request.getResultValue()));
        }
        if (request.getUnit() != null) {
            labResult.setUnit(blankToNull(request.getUnit()));
        }
        if (request.getReferenceRange() != null) {
            labResult.setReferenceRange(blankToNull(request.getReferenceRange()));
        }
        if (request.getResultData() != null) {
            labResult.setResultData(resultData);
        }
        if (request.getTestDate() != null) {
            labResult.setTestDate(request.getTestDate());
        }
        if (request.getDocumentId() != null) {
            labResult.setDocumentId(request.getDocumentId());
        }
        applyState(labResult, state);

        LabResult saved = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * L6. Status change by a doctor of the patient. Requesting the current status changes nothing. Never sets the
     * review stamp; moving into REQUIRES_REVIEW clears it. The stored status can differ from the requested one
     * (a flagged result set to COMPLETED becomes CRITICAL).
     */
    public LabResultResponse updateLabResultStatus(Integer resultId, String status) {
        String requestedStatus = requireWritableStatus(status);

        LabResult labResult = load(resultId);
        consultationService.requireRelatedDoctor(labResult.getPatientSystemId(), PERMISSION, resultId);
        if (LabResult.STATUS_CANCELLED.equals(labResult.getStatus())) {
            throw new ConflictException(CANCELLED_CANNOT_CHANGE);
        }
        if (requestedStatus.equals(labResult.getStatus())) {
            return toResponse(labResult);
        }
        requireChangeable(labResult.getStatus(), requestedStatus);

        boolean criticalBefore = Boolean.TRUE.equals(labResult.getIsCritical());
        applyState(labResult, normaliseCriticality(labResult.getStatus(), criticalBefore, requestedStatus, null));

        LabResult saved = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * L7. Sign-off by a doctor of the patient: status COMPLETED or CRITICAL, the flag follows the status, and the
     * review stamp (caller, clinic now, notes) is always replaced.
     */
    public LabResultResponse reviewLabResult(Integer resultId, ReviewResultRequest request) {
        String target = requireReviewStatus(request.getStatus());

        LabResult labResult = load(resultId);
        consultationService.requireRelatedDoctor(labResult.getPatientSystemId(), PERMISSION, resultId);
        if (LabResult.STATUS_CANCELLED.equals(labResult.getStatus())) {
            throw new ConflictException(CANCELLED_CANNOT_REVIEW);
        }

        boolean criticalBefore = Boolean.TRUE.equals(labResult.getIsCritical());
        labResult.setStatus(target);
        labResult.setIsCritical(LabResult.STATUS_CRITICAL.equals(target));
        labResult.setReviewedBySystemId(authorizationService.currentSystemId());
        labResult.setReviewedAt(LocalDateTime.now(clock));
        labResult.setReviewNotes(blankToNull(request.getReviewNotes()));

        LabResult saved = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * L8. Cancels the result (no row is deleted): status CANCELLED, flag false; the review stamp and the attachment
     * reference are kept. Author doctor or administrator. Cancelling a cancelled result changes nothing.
     */
    public void deleteLabResult(Integer resultId) {
        LabResult labResult = load(resultId);
        consultationService.requireAuthorDoctor(labResult.getDoctorSystemId(), labResult.getPatientSystemId(),
                RESOURCE, resultId, true);
        if (LabResult.STATUS_CANCELLED.equals(labResult.getStatus())) {
            return;
        }
        boolean criticalBefore = Boolean.TRUE.equals(labResult.getIsCritical());
        labResult.setStatus(LabResult.STATUS_CANCELLED);
        labResult.setIsCritical(false);

        LabResult saved = labResultRepository.save(labResult);
        auditService.logClinicalAccess("DELETE", RESOURCE, resultId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
    }

    // ==================== READS ====================

    /**
     * L2. One result (also a cancelled one).
     */
    @Transactional(readOnly = true)
    public LabResultResponse getLabResultById(Integer resultId) {
        LabResult labResult = load(resultId);
        authorizationService.requirePatientAccess(labResult.getPatientSystemId(), PERMISSION, resultId);
        auditService.logClinicalAccess("READ", RESOURCE, resultId, labResult.getPatientSystemId());
        return toResponse(labResult);
    }

    /**
     * L3. The canonical paged list, newest first, scoped by role (see {@link #resolveListScope}).
     */
    @Transactional(readOnly = true)
    public PageResponse<LabResultResponse> listLabResults(String patientSystemId, String doctorSystemId, String status,
                                                          String type, Boolean critical, Integer consultationId,
                                                          int page, int size) {
        String statusFilter = statusFilterOrNull(status);
        String typeFilter = blankToNull(type) == null ? null : requireEnum(type, LabResult.TEST_TYPES, "type", TEST_TYPE_MESSAGE);
        ListScope scope = resolveListScope(authorizationService, patientSystemId, doctorSystemId, PERMISSION);

        Page<LabResult> rows = labResultRepository.search(scope.patientSystemId(), scope.doctorSystemId(),
                statusFilter, typeFilter, critical, consultationId, PageParams.of(page, size, NEWEST_FIRST));
        auditList(scope, rows.getContent());
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * L4. The calling doctor's review queue: unreviewed REQUIRES_REVIEW / CRITICAL results they authored or of
     * patients on their active care team.
     */
    @Transactional(readOnly = true)
    public PageResponse<LabResultResponse> getMyPendingReview(int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();
        Page<LabResult> rows = labResultRepository.findPendingReview(doctorSystemId,
                PageParams.of(page, size, Sort.unsorted()));
        auditService.logListRead(RESOURCE, rows.getContent(), LabResult::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * L9 (deprecated). The patient's results as an array: newest first, at most 200, without CANCELLED rows.
     */
    @Transactional(readOnly = true)
    public List<LabResultResponse> getPatientLabResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<LabResult> rows = labResultRepository.findTop200ByPatientSystemIdAndStatusNotOrderByTestDateDescResultIdDesc(
                patientSystemId, LabResult.STATUS_CANCELLED);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * L10 (deprecated). Results authored by the doctor as an array: newest first, at most 200, without CANCELLED
     * rows. The doctor themselves or an administrator.
     */
    @Transactional(readOnly = true)
    public List<LabResultResponse> getDoctorLabResults(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<LabResult> rows = labResultRepository.findTop200ByDoctorSystemIdAndStatusNotOrderByTestDateDescResultIdDesc(
                doctorSystemId, LabResult.STATUS_CANCELLED);
        auditService.logListRead(RESOURCE, rows, LabResult::getPatientSystemId);
        return toResponses(rows);
    }

    // ==================== CRITICALITY ====================

    /** Status and critical flag of a lab result after a change. */
    public record Criticality(String status, boolean critical) {
    }

    /**
     * The criticality rule of contract section 2.5.
     *
     * @param storedStatus     status before the change (PENDING for a new result)
     * @param storedCritical   flag before the change (false for a new result)
     * @param requestedStatus  status the request supplies, or null
     * @param requestedCritical flag the request supplies, or null
     * @throws BadRequestException (VALIDATION_FAILED) when the request asks for CRITICAL with the flag false
     */
    public static Criticality normaliseCriticality(String storedStatus, boolean storedCritical,
                                                   String requestedStatus, Boolean requestedCritical) {
        String status = requestedStatus != null ? requestedStatus : storedStatus;
        boolean critical;
        if (requestedCritical != null) {
            critical = requestedCritical;
        } else if (LabResult.STATUS_CRITICAL.equals(requestedStatus)) {
            critical = true;
        } else if (requestedStatus != null && LabResult.STATUS_CRITICAL.equals(storedStatus)) {
            critical = false;
        } else {
            critical = storedCritical;
        }

        if (Boolean.FALSE.equals(requestedCritical) && LabResult.STATUS_CRITICAL.equals(status)) {
            if (LabResult.STATUS_CRITICAL.equals(requestedStatus)) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED,
                        "isCritical: cannot be false when status is CRITICAL");
            }
            status = LabResult.STATUS_COMPLETED;
        }
        if (critical && LabResult.STATUS_COMPLETED.equals(status)) {
            status = LabResult.STATUS_CRITICAL;
        }
        if (LabResult.STATUS_CRITICAL.equals(status)) {
            critical = true;
        }
        if (LabResult.STATUS_CANCELLED.equals(status)) {
            critical = false;
        }
        return new Criticality(status, critical);
    }

    // ==================== INTERNALS ====================

    private LabResult load(Integer resultId) {
        return labResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("Lab result not found"));
    }

    private static void applyState(LabResult labResult, Criticality state) {
        labResult.setStatus(state.status());
        labResult.setIsCritical(state.critical());
        if (LabResult.STATUS_REQUIRES_REVIEW.equals(state.status())) {
            labResult.setReviewedBySystemId(null);
            labResult.setReviewedAt(null);
            labResult.setReviewNotes(null);
        }
    }

    private void publish(LabResult labResult, boolean criticalBefore, boolean created) {
        boolean critical = Boolean.TRUE.equals(labResult.getIsCritical());
        eventPublisher.publishEvent(new ClinicalResultRecordedEvent(
                ClinicalResultRecordedEvent.LAB, labResult.getResultId(), labResult.getPatientSystemId(),
                labResult.getDoctorSystemId(), authorizationService.currentSystemId(), labResult.getStatus(),
                critical, critical && !criticalBefore, created));
    }

    private void auditList(ListScope scope, List<LabResult> rows) {
        if (scope.patientSystemId() != null) {
            auditService.logClinicalAccess("READ", RESOURCE, null, scope.patientSystemId());
        } else {
            auditService.logListRead(RESOURCE, rows, LabResult::getPatientSystemId);
        }
    }

    private LabResultResponse toResponse(LabResult labResult) {
        return toResponses(List.of(labResult)).get(0);
    }

    /** Maps a page of rows with one query for the names and one for the attachments. */
    private List<LabResultResponse> toResponses(List<LabResult> rows) {
        Set<String> people = new LinkedHashSet<>();
        Set<Integer> documents = new LinkedHashSet<>();
        for (LabResult row : rows) {
            people.add(row.getPatientSystemId());
            people.add(row.getDoctorSystemId());
            people.add(row.getReviewedBySystemId());
            documents.add(row.getDocumentId());
        }
        Map<String, LabResultRepository.PersonName> names = loadNames(labResultRepository, people);
        Set<Integer> usableDocuments = loadUsableDocuments(labResultRepository, documents);

        return rows.stream().map(row -> LabResultResponse.builder()
                        .resultId(row.getResultId())
                        .patientSystemId(row.getPatientSystemId())
                        .patientName(personName(names.get(row.getPatientSystemId())))
                        .doctorSystemId(row.getDoctorSystemId())
                        .doctorName(doctorName(names.get(row.getDoctorSystemId())))
                        .consultationId(row.getConsultationId())
                        .testName(row.getTestName())
                        .testType(row.getTestType())
                        .resultValue(row.getResultValue())
                        .unit(row.getUnit())
                        .referenceRange(row.getReferenceRange())
                        .resultData(row.getResultData())
                        .status(row.getStatus())
                        .isCritical(Boolean.TRUE.equals(row.getIsCritical()))
                        .testDate(row.getTestDate())
                        .reviewedBy(row.getReviewedBySystemId())
                        .reviewedByName(row.getReviewedBySystemId() == null ? null
                                : doctorName(names.get(row.getReviewedBySystemId())))
                        .reviewedAt(row.getReviewedAt())
                        .reviewNotes(row.getReviewNotes())
                        .documentId(row.getDocumentId() != null && usableDocuments.contains(row.getDocumentId())
                                ? row.getDocumentId() : null)
                        .fileUrl(null)
                        .createdAt(row.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    private static String requireTestType(String testType) {
        return requireEnum(testType, LabResult.TEST_TYPES, "testType", TEST_TYPE_MESSAGE);
    }

    // ==================== SHARED WITH ImagingResultService ====================

    /** Who may list what (contract L3 / I3): the patient filter and author filter after the role rules. */
    record ListScope(String patientSystemId, String doctorSystemId) {
    }

    /**
     * Applies the role rules of the canonical lists:
     * PATIENT: patient defaults to the caller, another patient is denied; CAREGIVER: patient required, then the
     * caregiver grant (flag, expiry, revocation); DOCTOR with a patient: care relationship; DOCTOR without one: author
     * defaults to the caller, another doctor is denied; ADMIN: anything. The author filter is free otherwise.
     */
    static ListScope resolveListScope(AuthorizationService authorization, String patientSystemId,
                                      String doctorSystemId, PatientPermission permission) {
        String patient = blankToNull(patientSystemId);
        String doctor = blankToNull(doctorSystemId);
        String role = authorization.currentRole();
        String caller = authorization.currentSystemId();
        switch (role) {
            case AuthorizationService.PATIENT -> {
                if (patient == null) {
                    patient = caller;
                }
                authorization.requirePatientAccess(patient, permission);
            }
            case AuthorizationService.CAREGIVER -> {
                if (patient == null) {
                    throw new BadRequestException("patientSystemId is required");
                }
                authorization.requirePatientAccess(patient, permission);
            }
            case AuthorizationService.DOCTOR -> {
                if (patient != null) {
                    authorization.requirePatientAccess(patient, permission);
                } else if (doctor == null) {
                    doctor = caller;
                } else if (!doctor.equals(caller)) {
                    authorization.requireDoctorSelf(doctor);
                }
            }
            case AuthorizationService.ADMIN -> {
                // any combination of filters
            }
            default -> authorization.requireRole(AuthorizationService.PATIENT, AuthorizationService.DOCTOR,
                    AuthorizationService.CAREGIVER, AuthorizationService.ADMIN);
        }
        return new ListScope(patient, doctor);
    }

    /** Trimmed, upper-cased value that must be one of {@code allowed}; otherwise 400 {@code <field>: <message>}. */
    static String requireEnum(String value, Collection<String> allowed, String field, String message) {
        String normalised = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (normalised == null || !allowed.contains(normalised)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, field + ": " + message);
        }
        return normalised;
    }

    /** A writable status (PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL), upper-cased. */
    static String requireWritableStatus(String status) {
        return requireEnum(status, LabResult.WRITABLE_STATUSES, "status", WRITABLE_STATUS_MESSAGE);
    }

    /** Like {@link #requireWritableStatus} but null or blank means "not supplied". */
    static String writableStatusOrNull(String status) {
        return blankToNull(status) == null ? null : requireWritableStatus(status);
    }

    /** The status filter of the canonical lists (CANCELLED allowed), or null when not given. */
    static String statusFilterOrNull(String status) {
        return blankToNull(status) == null ? null
                : requireEnum(status, LabResult.STATUSES, "status", STATUS_FILTER_MESSAGE);
    }

    /** The target of a review: COMPLETED or CRITICAL. */
    static String requireReviewStatus(String status) {
        if (blankToNull(status) == null) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "status: is required");
        }
        return requireEnum(status, List.of(LabResult.STATUS_COMPLETED, LabResult.STATUS_CRITICAL), "status",
                REVIEW_STATUS_MESSAGE);
    }

    /**
     * Transition rules for PUT and PATCH: nothing changes on a CANCELLED result, and a recorded result never goes
     * back to PENDING.
     */
    static void requireChangeable(String storedStatus, String requestedStatus) {
        if (LabResult.STATUS_CANCELLED.equals(storedStatus)) {
            throw new ConflictException(CANCELLED_CANNOT_CHANGE);
        }
        if (LabResult.STATUS_PENDING.equals(requestedStatus) && !LabResult.STATUS_PENDING.equals(storedStatus)) {
            throw new ConflictException(NO_BACK_TO_PENDING);
        }
    }

    /** A result date must not be after clinic today. */
    static void rejectFutureDate(LocalDate date, LocalDate clinicToday, String field) {
        if (date != null && date.isAfter(clinicToday)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, field + ": must not be in the future");
        }
    }

    /** An optional attachment must be a usable document of the same patient. */
    static void requireUsableDocument(LabResultRepository repository, Integer documentId, String patientSystemId) {
        if (documentId != null && !repository.isUsableDocumentOfPatient(documentId, patientSystemId)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, DOCUMENT_NOT_FOUND);
        }
    }

    /** resultData: blank means none; anything else must be the JSON text of an object (stored as jsonb). */
    static String jsonObjectOrNull(ObjectMapper objectMapper, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        JsonNode node;
        try {
            node = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(trimmed);
        } catch (JsonProcessingException e) {
            node = null;
        }
        // PostgreSQL jsonb cannot store the NUL character.
        if (node == null || !node.isObject() || trimmed.contains("\\u0000")) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "resultData: must be a JSON object");
        }
        return trimmed;
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Name parts of the given accounts, keyed by system id (one query; nulls are ignored). */
    static Map<String, LabResultRepository.PersonName> loadNames(LabResultRepository repository,
                                                                 Collection<String> systemIds) {
        Set<String> ids = systemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, LabResultRepository.PersonName> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (LabResultRepository.PersonName name : repository.findPersonNames(ids)) {
                names.put(name.getSystemId(), name);
            }
        }
        return names;
    }

    /** The documents of {@code documentIds} that are still usable (one query; nulls are ignored). */
    static Set<Integer> loadUsableDocuments(LabResultRepository repository, Collection<Integer> documentIds) {
        Set<Integer> ids = documentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? Set.of() : Set.copyOf(repository.findUsableDocumentIds(ids));
    }

    /** "First Last", or null when the account has no name. */
    static String personName(LabResultRepository.PersonName name) {
        if (name == null) {
            return null;
        }
        String first = name.getFirstName() != null ? name.getFirstName().trim() : "";
        String last = name.getLastName() != null ? name.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }

    /** "Dr. First Last" (no doubled title when the stored name already carries it), or null without a name. */
    static String doctorName(LabResultRepository.PersonName name) {
        String full = personName(name);
        if (full == null) {
            return null;
        }
        return DOCTOR_TITLE.matcher(full).lookingAt() ? full : "Dr. " + full;
    }
}
