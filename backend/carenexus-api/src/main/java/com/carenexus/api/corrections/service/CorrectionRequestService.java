package com.carenexus.api.corrections.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.corrections.dto.request.AssignCorrectionRequest;
import com.carenexus.api.corrections.dto.request.CreateCorrectionRequest;
import com.carenexus.api.corrections.dto.request.ResolveCorrectionRequest;
import com.carenexus.api.corrections.dto.response.AssignableDoctorResponse;
import com.carenexus.api.corrections.dto.response.CorrectionRequestResponse;
import com.carenexus.api.corrections.model.CorrectionRequest;
import com.carenexus.api.corrections.repository.CorrectionLookupRepository;
import com.carenexus.api.corrections.repository.CorrectionLookupRepository.DoctorRow;
import com.carenexus.api.corrections.repository.CorrectionLookupRepository.PersonRow;
import com.carenexus.api.corrections.repository.CorrectionRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Patient requests for correction of clinical records.
 *
 * <ul>
 *   <li>Filing: the patient (own records) or a caregiver with {@link PatientPermission#MANAGE_CARE_PLAN}. The record
 *       must belong to the patient; its authoring doctor becomes the assignee when that doctor is an active
 *       account, otherwise the request is unassigned and every active administrator is notified. One OPEN request
 *       per record.</li>
 *   <li>Reading: the patient, a caregiver with {@link PatientPermission#RECORDS}, a related doctor or an
 *       administrator ({@code requirePatientAccess}); the assigned doctor may always read the request.</li>
 *   <li>Resolving: the assigned doctor or an administrator; ACCEPTED and REJECTED are terminal (409 afterwards).
 *       Resolving changes no clinical row: the doctor corrects the record through its own authoring endpoints.</li>
 *   <li>Assigning: administrators only, to an active doctor related to the patient.</li>
 * </ul>
 * Every success is audited ({@code CORRECTION_REQUEST}); denials are audited by {@link AuthorizationService} (and
 * here for the non-assignee doctor on resolve).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CorrectionRequestService {

    public static final String RESOURCE = "CORRECTION_REQUEST";
    public static final String RATE_LIMIT_BUCKET = "correction-request";
    static final int MAX_ASSIGNABLE_DOCTORS = 200;

    static final String OPEN_UNIQUE_INDEX = "ux_correction_requests_open_resource";
    static final String NOT_FOUND = "Correction request not found";
    static final String RECORD_NOT_FOUND = "The record to correct was not found";
    static final String DUPLICATE_OPEN = "An open correction request already exists for this record";
    static final String ALREADY_RESOLVED = "This correction request has already been resolved";
    static final String NOT_ASSIGNEE = "Only the assigned doctor or an administrator can resolve this request";
    static final String DOCTOR_NOT_ASSIGNABLE =
            "The doctor must be an active doctor with a care relationship with this patient";
    static final String PATIENT_REQUIRED = "patientSystemId: is required when acting for a dependent";
    static final String INVALID_STATUS = "status: must be one of OPEN, ACCEPTED, REJECTED";

    /** Newest first, ties broken by id so pages never overlap. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "requestId"));

    private final CorrectionRequestRepository correctionRequestRepository;
    private final CorrectionLookupRepository lookupRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final RateLimitGuard rateLimitGuard;
    private final CorrectionNotifier notifier;
    private final Clock clock;

    // ==================== FILE ====================

    /**
     * Files a correction request (PATIENT for themselves, CAREGIVER with MANAGE_CARE_PLAN for a dependent).
     */
    public CorrectionRequestResponse create(CreateCorrectionRequest body) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();

        String patientSystemId = trimToNull(body.getPatientSystemId());
        if (patientSystemId == null) {
            if (AuthorizationService.CAREGIVER.equals(role)) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, PATIENT_REQUIRED);
            }
            patientSystemId = caller;
        }
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_CARE_PLAN);

        rateLimitGuard.check(RATE_LIMIT_BUCKET, caller);

        String resourceType = body.getResourceType().trim();
        Integer resourceId = body.getResourceId();
        String author = findRecordAuthor(resourceType, resourceId, patientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException(RECORD_NOT_FOUND));
        if (correctionRequestRepository.existsByResourceTypeAndResourceIdAndStatus(
                resourceType, resourceId, CorrectionRequest.OPEN)) {
            throw new ConflictException(DUPLICATE_OPEN);
        }
        String assignee = !author.isBlank() && lookupRepository.isActiveDoctor(author) ? author : null;

        Instant now = clock.instant();
        CorrectionRequest request = CorrectionRequest.builder()
                .patientSystemId(patientSystemId)
                .requestedBySystemId(caller)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .requestedText(body.getRequestedText().trim())
                .status(CorrectionRequest.OPEN)
                .assignedDoctorSystemId(assignee)
                .createdAt(now)
                .updatedAt(now)
                .build();
        CorrectionRequest saved;
        try {
            saved = correctionRequestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, OPEN_UNIQUE_INDEX)) {
                // Two requests for the same record raced past the existence check.
                throw new ConflictException(DUPLICATE_OPEN);
            }
            throw e;
        }

        Map<String, PersonRow> people = people(List.of(saved));
        String requesterName = nameOf(people, caller);
        String patientName = nameOf(people, patientSystemId);
        if (assignee != null) {
            notifier.created(saved, requesterName, patientName, caller);
        } else {
            notifier.unassigned(saved, lookupRepository.findActiveAdminIds(), requesterName, patientName, caller);
        }
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getRequestId(), patientSystemId);
        log.info("Correction request {} filed for {} {} (assigned: {})", saved.getRequestId(), resourceType,
                resourceId, assignee != null);
        return toResponse(saved, people);
    }

    // ==================== READ ====================

    /**
     * Every request about one patient (patient, caregiver with RECORDS, related doctor, admin), newest first.
     */
    @Transactional(readOnly = true)
    public PageResponse<CorrectionRequestResponse> listForPatient(String patientSystemId, String status,
                                                                  int page, int size) {
        String statusFilter = parseStatus(status);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.RECORDS);
        Page<CorrectionRequest> result = correctionRequestRepository.findForPatient(
                patientSystemId, statusFilter, pageable(page, size));
        return toPage(result);
    }

    /**
     * The calling doctor's review queue: requests assigned to them, newest first.
     */
    @Transactional(readOnly = true)
    public PageResponse<CorrectionRequestResponse> listAssigned(String status, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String statusFilter = parseStatus(status);
        String doctorSystemId = authorizationService.currentSystemId();
        Page<CorrectionRequest> result = correctionRequestRepository.findAssignedTo(
                doctorSystemId, statusFilter, pageable(page, size));
        return toPage(result);
    }

    /**
     * One request: its assigned doctor, or anyone with RECORDS access to the patient.
     */
    @Transactional(readOnly = true)
    public CorrectionRequestResponse get(Integer requestId) {
        CorrectionRequest request = correctionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!isAssignee(request)) {
            authorizationService.requirePatientAccess(request.getPatientSystemId(), PatientPermission.RECORDS,
                    request.getRequestId());
        }
        auditService.logClinicalAccess("READ", RESOURCE, request.getRequestId(), request.getPatientSystemId());
        return toResponse(request, people(List.of(request)));
    }

    // ==================== RESOLVE ====================

    /**
     * Accepts or rejects an OPEN request (the assigned doctor, or an administrator) and notifies the requester.
     */
    public CorrectionRequestResponse resolve(Integer requestId, ResolveCorrectionRequest body) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        String caller = authorizationService.currentSystemId();
        CorrectionRequest request = correctionRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!authorizationService.isAdmin() && !caller.equals(request.getAssignedDoctorSystemId())) {
            denyResolve(caller, request);
        }
        if (!request.isOpen()) {
            throw new ConflictException(ALREADY_RESOLVED);
        }

        Instant now = clock.instant();
        request.setStatus(body.getDecision().trim());
        request.setResolverSystemId(caller);
        request.setResolutionNote(body.getNote().trim());
        request.setResolvedAt(now);
        request.setUpdatedAt(now);
        CorrectionRequest saved = correctionRequestRepository.saveAndFlush(request);

        Map<String, PersonRow> people = people(List.of(saved));
        notifier.resolved(saved, nameOf(people, saved.getPatientSystemId()), caller);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getRequestId(), saved.getPatientSystemId());
        log.info("Correction request {} resolved as {}", saved.getRequestId(), saved.getStatus());
        return toResponse(saved, people);
    }

    // ==================== ADMIN ====================

    /**
     * Every request (ADMIN), newest first; {@code unassignedOnly} keeps only requests without a doctor.
     */
    @Transactional(readOnly = true)
    public PageResponse<CorrectionRequestResponse> listAll(String status, boolean unassignedOnly, int page, int size) {
        authorizationService.requireAdmin();
        String statusFilter = parseStatus(status);
        Page<CorrectionRequest> result = correctionRequestRepository.findAllFiltered(
                statusFilter, unassignedOnly, pageable(page, size));
        return toPage(result);
    }

    /**
     * Assigns or reassigns an OPEN request (ADMIN) to an active doctor related to the patient. Assigning the
     * current assignee again changes nothing and notifies nobody.
     */
    public CorrectionRequestResponse assign(Integer requestId, AssignCorrectionRequest body) {
        authorizationService.requireAdmin();
        String caller = authorizationService.currentSystemId();
        CorrectionRequest request = correctionRequestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (!request.isOpen()) {
            throw new ConflictException(ALREADY_RESOLVED);
        }
        String doctorSystemId = body.getDoctorSystemId().trim();
        if (!authorizationService.relatedDoctors(request.getPatientSystemId()).contains(doctorSystemId)) {
            throw new BadRequestException(DOCTOR_NOT_ASSIGNABLE);
        }
        if (doctorSystemId.equals(request.getAssignedDoctorSystemId())) {
            return toResponse(request, people(List.of(request)));
        }

        request.setAssignedDoctorSystemId(doctorSystemId);
        request.setUpdatedAt(clock.instant());
        CorrectionRequest saved = correctionRequestRepository.saveAndFlush(request);

        Map<String, PersonRow> people = people(List.of(saved));
        notifier.assigned(saved, nameOf(people, saved.getPatientSystemId()), caller);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getRequestId(), saved.getPatientSystemId());
        log.info("Correction request {} assigned", saved.getRequestId());
        return toResponse(saved, people);
    }

    /**
     * The doctors an administrator may assign this request to: the patient's active related doctors, sorted by name
     * then system id (hard cap {@value #MAX_ASSIGNABLE_DOCTORS}).
     */
    @Transactional(readOnly = true)
    public List<AssignableDoctorResponse> assignableDoctors(Integer requestId) {
        authorizationService.requireAdmin();
        CorrectionRequest request = correctionRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        List<String> related = authorizationService.relatedDoctors(request.getPatientSystemId());
        List<AssignableDoctorResponse> doctors = related.isEmpty() ? List.of()
                : lookupRepository.findActiveDoctors(related).stream()
                .map(row -> AssignableDoctorResponse.builder()
                        .doctorSystemId(row.getSystemId())
                        .doctorName(fullName(row.getFirstName(), row.getLastName(), row.getSystemId()))
                        .specialization(trimToNull(row.getSpecialization()))
                        .currentAssignee(row.getSystemId().equals(request.getAssignedDoctorSystemId()))
                        .build())
                .sorted(Comparator.comparing(AssignableDoctorResponse::getDoctorName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AssignableDoctorResponse::getDoctorSystemId))
                .limit(MAX_ASSIGNABLE_DOCTORS)
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, request.getRequestId(), request.getPatientSystemId());
        return doctors;
    }

    // ==================== HELPERS ====================

    /**
     * The authoring doctor of the patient's record ({@code ""} when the record has none), or empty when the patient
     * has no such record (missing, soft-deleted or another patient's).
     */
    private Optional<String> findRecordAuthor(String resourceType, Integer resourceId, String patientSystemId) {
        List<String> rows = switch (resourceType) {
            case CorrectionRequest.MEDICAL_RECORD -> lookupRepository.medicalRecordAuthor(resourceId, patientSystemId);
            case CorrectionRequest.LAB_RESULT -> lookupRepository.labResultAuthor(resourceId, patientSystemId);
            case CorrectionRequest.IMAGING_RESULT -> lookupRepository.imagingResultAuthor(resourceId, patientSystemId);
            case CorrectionRequest.PRESCRIPTION -> lookupRepository.prescriptionAuthor(resourceId, patientSystemId);
            case CorrectionRequest.CONSULTATION -> lookupRepository.consultationAuthor(resourceId, patientSystemId);
            default -> List.of();
        };
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0) == null ? "" : rows.get(0).trim());
    }

    private boolean isAssignee(CorrectionRequest request) {
        return AuthorizationService.DOCTOR.equals(authorizationService.currentRole())
                && authorizationService.currentSystemId().equals(request.getAssignedDoctorSystemId());
    }

    /** A doctor who is not the assignee tried to resolve: audit the denial and refuse. */
    private void denyResolve(String caller, CorrectionRequest request) {
        try {
            auditService.logAccessDenied(caller, RESOURCE, request.getRequestId(), request.getPatientSystemId(), null,
                    "Doctor is not the assignee of the correction request");
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException(NOT_ASSIGNEE, RESOURCE, request.getRequestId());
    }

    /** Status filter: trimmed and upper-cased; blank means no filter. */
    static String parseStatus(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String status = value.toUpperCase(Locale.ROOT);
        if (!CorrectionRequest.STATUSES.contains(status)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, INVALID_STATUS);
        }
        return status;
    }

    private static Pageable pageable(int page, int size) {
        return PageParams.of(page, size, NEWEST_FIRST);
    }

    private PageResponse<CorrectionRequestResponse> toPage(Page<CorrectionRequest> result) {
        List<CorrectionRequest> rows = result.getContent();
        auditService.logListRead(RESOURCE, rows, CorrectionRequest::getPatientSystemId);
        Map<String, PersonRow> people = people(rows);
        return PageResponse.of(result, rows.stream().map(row -> toResponse(row, people)).toList());
    }

    /** Everyone named by these requests, in one query. */
    private Map<String, PersonRow> people(Collection<CorrectionRequest> requests) {
        Set<String> ids = new LinkedHashSet<>();
        for (CorrectionRequest request : requests) {
            ids.add(request.getPatientSystemId());
            ids.add(request.getRequestedBySystemId());
            ids.add(request.getAssignedDoctorSystemId());
            ids.add(request.getResolverSystemId());
        }
        ids.remove(null);
        Map<String, PersonRow> people = new HashMap<>();
        if (!ids.isEmpty()) {
            lookupRepository.findPeople(ids).forEach(row -> people.put(row.getSystemId(), row));
        }
        return people;
    }

    private static CorrectionRequestResponse toResponse(CorrectionRequest request, Map<String, PersonRow> people) {
        String requester = request.getRequestedBySystemId();
        return CorrectionRequestResponse.builder()
                .requestId(request.getRequestId())
                .patientSystemId(request.getPatientSystemId())
                .patientName(nameOf(people, request.getPatientSystemId()))
                .requestedBySystemId(requester)
                .requestedByName(nameOf(people, requester))
                .requestedByRole(Objects.equals(requester, request.getPatientSystemId())
                        ? AuthorizationService.PATIENT : AuthorizationService.CAREGIVER)
                .resourceType(request.getResourceType())
                .resourceId(request.getResourceId())
                .requestedText(request.getRequestedText())
                .status(request.getStatus())
                .assignedDoctorSystemId(request.getAssignedDoctorSystemId())
                .assignedDoctorName(request.getAssignedDoctorSystemId() == null ? null
                        : nameOf(people, request.getAssignedDoctorSystemId()))
                .resolverSystemId(request.getResolverSystemId())
                .resolverName(request.getResolverSystemId() == null ? null
                        : nameOf(people, request.getResolverSystemId()))
                .resolutionNote(request.getResolutionNote())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .resolvedAt(request.getResolvedAt())
                .build();
    }

    private static String nameOf(Map<String, PersonRow> people, String systemId) {
        PersonRow row = people.get(systemId);
        return row == null ? systemId : fullName(row.getFirstName(), row.getLastName(), systemId);
    }

    private static String fullName(String first, String last, String fallback) {
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? fallback : name;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** True when the failure (or one of its causes) names the given constraint or index. */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
