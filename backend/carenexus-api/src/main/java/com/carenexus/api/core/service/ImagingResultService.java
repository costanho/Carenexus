package com.carenexus.api.core.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateImagingResultRequest;
import com.carenexus.api.core.dto.request.ReviewResultRequest;
import com.carenexus.api.core.dto.request.UpdateImagingResultRequest;
import com.carenexus.api.core.dto.response.ImagingResultResponse;
import com.carenexus.api.core.model.ImagingResult;
import com.carenexus.api.core.repository.ImagingResultRepository;
import com.carenexus.api.core.repository.LabResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.carenexus.api.core.service.LabResultService.blankToNull;

/**
 * Imaging results (API contract be-lab-imaging, endpoints I1 to I9). Same access, status and event rules as
 * {@link LabResultService}, except that imaging has no stored critical flag: a result is critical exactly when its
 * status is CRITICAL ({@code isCritical} in the response is derived), so there is nothing to normalise.
 *
 * Access, checked here for every method:
 * - create: the treating doctor; the author is always the caller;
 * - edit (PUT): the author doctor only; cancel (DELETE): the author doctor or an administrator;
 * - status change and review: a DOCTOR with a care relationship to the patient; the reviewer is always the caller;
 * - reads: {@link AuthorizationService#requirePatientAccess} with {@link PatientPermission#IMAGING}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ImagingResultService {

    static final String RESOURCE = "IMAGING_RESULT";
    private static final PatientPermission PERMISSION = PatientPermission.IMAGING;
    static final String IMAGING_TYPE_MESSAGE =
            "must be one of XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, ECHO";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("imageDate"), Sort.Order.desc("imagingId"));

    private final ImagingResultRepository imagingResultRepository;
    private final LabResultRepository labResultRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== WRITES ====================

    /**
     * I1. Records a result as the calling (treating) doctor. Status defaults to PENDING, the date to clinic today.
     */
    public ImagingResultResponse createImagingResult(CreateImagingResultRequest request) {
        LabResultService.rejectFutureDate(request.getImageDate(), LocalDate.now(clock), "imageDate");
        String imagingType = requireImagingType(request.getImagingType());
        String requestedStatus = LabResultService.writableStatusOrNull(request.getStatus());

        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);
        LabResultService.requireUsableDocument(labResultRepository, request.getDocumentId(),
                request.getPatientSystemId());

        ImagingResult imagingResult = ImagingResult.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .imagingType(imagingType)
                .bodyPart(blankToNull(request.getBodyPart()))
                .radiologistReport(blankToNull(request.getRadiologistReport()))
                .findings(blankToNull(request.getFindings()))
                .impression(blankToNull(request.getImpression()))
                .status(requestedStatus != null ? requestedStatus : ImagingResult.STATUS_PENDING)
                .imageDate(request.getImageDate() != null ? request.getImageDate() : LocalDate.now(clock))
                .documentId(request.getDocumentId())
                .createdAt(LocalDateTime.now(clock))
                .build();

        ImagingResult saved = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getImagingId(), saved.getPatientSystemId());
        publish(saved, false, true);
        return toResponse(saved);
    }

    /**
     * I5. Partial update by the author doctor. Absent fields are unchanged; blank optional strings clear the field.
     */
    public ImagingResultResponse updateImagingResult(Integer imagingId, UpdateImagingResultRequest request) {
        LabResultService.rejectFutureDate(request.getImageDate(), LocalDate.now(clock), "imageDate");
        String imagingType = request.getImagingType() != null ? requireImagingType(request.getImagingType()) : null;
        String requestedStatus = request.getStatus() != null
                ? LabResultService.requireWritableStatus(request.getStatus()) : null;

        ImagingResult imagingResult = load(imagingId);
        consultationService.requireAuthorDoctor(imagingResult.getDoctorSystemId(),
                imagingResult.getPatientSystemId(), RESOURCE, imagingId, false);
        LabResultService.requireChangeable(imagingResult.getStatus(), requestedStatus);
        if (request.getDocumentId() != null) {
            LabResultService.requireUsableDocument(labResultRepository, request.getDocumentId(),
                    imagingResult.getPatientSystemId());
        }

        boolean criticalBefore = isCritical(imagingResult);
        if (imagingType != null) {
            imagingResult.setImagingType(imagingType);
        }
        if (request.getBodyPart() != null) {
            imagingResult.setBodyPart(blankToNull(request.getBodyPart()));
        }
        if (request.getRadiologistReport() != null) {
            imagingResult.setRadiologistReport(blankToNull(request.getRadiologistReport()));
        }
        if (request.getFindings() != null) {
            imagingResult.setFindings(blankToNull(request.getFindings()));
        }
        if (request.getImpression() != null) {
            imagingResult.setImpression(blankToNull(request.getImpression()));
        }
        if (request.getImageDate() != null) {
            imagingResult.setImageDate(request.getImageDate());
        }
        if (request.getDocumentId() != null) {
            imagingResult.setDocumentId(request.getDocumentId());
        }
        if (requestedStatus != null) {
            applyStatus(imagingResult, requestedStatus);
        }

        ImagingResult saved = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * I6. Status change by a doctor of the patient. Requesting the current status changes nothing. Never sets the
     * review stamp; moving into REQUIRES_REVIEW clears it.
     */
    public ImagingResultResponse updateImagingResultStatus(Integer imagingId, String status) {
        String requestedStatus = LabResultService.requireWritableStatus(status);

        ImagingResult imagingResult = load(imagingId);
        consultationService.requireRelatedDoctor(imagingResult.getPatientSystemId(), PERMISSION, imagingId);
        if (ImagingResult.STATUS_CANCELLED.equals(imagingResult.getStatus())) {
            throw new ConflictException(LabResultService.CANCELLED_CANNOT_CHANGE);
        }
        if (requestedStatus.equals(imagingResult.getStatus())) {
            return toResponse(imagingResult);
        }
        LabResultService.requireChangeable(imagingResult.getStatus(), requestedStatus);

        boolean criticalBefore = isCritical(imagingResult);
        applyStatus(imagingResult, requestedStatus);

        ImagingResult saved = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * I7. Sign-off by a doctor of the patient: status COMPLETED or CRITICAL and the review stamp (caller, clinic now,
     * notes), always replaced.
     */
    public ImagingResultResponse reviewImagingResult(Integer imagingId, ReviewResultRequest request) {
        String target = LabResultService.requireReviewStatus(request.getStatus());

        ImagingResult imagingResult = load(imagingId);
        consultationService.requireRelatedDoctor(imagingResult.getPatientSystemId(), PERMISSION, imagingId);
        if (ImagingResult.STATUS_CANCELLED.equals(imagingResult.getStatus())) {
            throw new ConflictException(LabResultService.CANCELLED_CANNOT_REVIEW);
        }

        boolean criticalBefore = isCritical(imagingResult);
        imagingResult.setStatus(target);
        imagingResult.setReviewedBySystemId(authorizationService.currentSystemId());
        imagingResult.setReviewedAt(LocalDateTime.now(clock));
        imagingResult.setReviewNotes(blankToNull(request.getReviewNotes()));

        ImagingResult saved = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
        return toResponse(saved);
    }

    /**
     * I8. Cancels the result (no row is deleted); the review stamp and the attachment reference are kept. Author
     * doctor or administrator. Cancelling a cancelled result changes nothing.
     */
    public void deleteImagingResult(Integer imagingId) {
        ImagingResult imagingResult = load(imagingId);
        consultationService.requireAuthorDoctor(imagingResult.getDoctorSystemId(),
                imagingResult.getPatientSystemId(), RESOURCE, imagingId, true);
        if (ImagingResult.STATUS_CANCELLED.equals(imagingResult.getStatus())) {
            return;
        }
        boolean criticalBefore = isCritical(imagingResult);
        imagingResult.setStatus(ImagingResult.STATUS_CANCELLED);

        ImagingResult saved = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("DELETE", RESOURCE, imagingId, saved.getPatientSystemId());
        publish(saved, criticalBefore, false);
    }

    // ==================== READS ====================

    /**
     * I2. One result (also a cancelled one).
     */
    @Transactional(readOnly = true)
    public ImagingResultResponse getImagingResultById(Integer imagingId) {
        ImagingResult imagingResult = load(imagingId);
        authorizationService.requirePatientAccess(imagingResult.getPatientSystemId(), PERMISSION, imagingId);
        auditService.logClinicalAccess("READ", RESOURCE, imagingId, imagingResult.getPatientSystemId());
        return toResponse(imagingResult);
    }

    /**
     * I3. The canonical paged list, newest first, scoped by role like the lab list. {@code critical=true} returns
     * CRITICAL results, {@code critical=false} every other status.
     */
    @Transactional(readOnly = true)
    public PageResponse<ImagingResultResponse> listImagingResults(String patientSystemId, String doctorSystemId,
                                                                  String status, String type, Boolean critical,
                                                                  Integer consultationId, int page, int size) {
        String statusFilter = LabResultService.statusFilterOrNull(status);
        String typeFilter = blankToNull(type) == null ? null
                : LabResultService.requireEnum(type, ImagingResult.IMAGING_TYPES, "type", IMAGING_TYPE_MESSAGE);
        LabResultService.ListScope scope = LabResultService.resolveListScope(authorizationService, patientSystemId,
                doctorSystemId, PERMISSION);

        String excludedStatus = null;
        boolean contradiction = false;
        if (Boolean.TRUE.equals(critical)) {
            if (statusFilter == null) {
                statusFilter = ImagingResult.STATUS_CRITICAL;
            } else if (!ImagingResult.STATUS_CRITICAL.equals(statusFilter)) {
                contradiction = true;
            }
        } else if (Boolean.FALSE.equals(critical)) {
            excludedStatus = ImagingResult.STATUS_CRITICAL;
        }

        var pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<ImagingResult> rows = contradiction
                ? Page.empty(pageable)
                : imagingResultRepository.search(scope.patientSystemId(), scope.doctorSystemId(), statusFilter,
                        excludedStatus, typeFilter, consultationId, pageable);
        if (scope.patientSystemId() != null) {
            auditService.logClinicalAccess("READ", RESOURCE, null, scope.patientSystemId());
        } else {
            auditService.logListRead(RESOURCE, rows.getContent(), ImagingResult::getPatientSystemId);
        }
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * I4. The calling doctor's review queue: unreviewed REQUIRES_REVIEW / CRITICAL results they authored or of
     * patients on their active care team.
     */
    @Transactional(readOnly = true)
    public PageResponse<ImagingResultResponse> getMyPendingReview(int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();
        Page<ImagingResult> rows = imagingResultRepository.findPendingReview(doctorSystemId,
                PageParams.of(page, size, Sort.unsorted()));
        auditService.logListRead(RESOURCE, rows.getContent(), ImagingResult::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    /**
     * I9 (deprecated). The patient's results as an array: newest first, at most 200, without CANCELLED rows.
     */
    @Transactional(readOnly = true)
    public List<ImagingResultResponse> getPatientImagingResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<ImagingResult> rows = imagingResultRepository
                .findTop200ByPatientSystemIdAndStatusNotOrderByImageDateDescImagingIdDesc(
                        patientSystemId, ImagingResult.STATUS_CANCELLED);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    // ==================== INTERNALS ====================

    private ImagingResult load(Integer imagingId) {
        return imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new ResourceNotFoundException("Imaging result not found"));
    }

    private static boolean isCritical(ImagingResult imagingResult) {
        return ImagingResult.STATUS_CRITICAL.equals(imagingResult.getStatus());
    }

    private static void applyStatus(ImagingResult imagingResult, String status) {
        imagingResult.setStatus(status);
        if (ImagingResult.STATUS_REQUIRES_REVIEW.equals(status)) {
            imagingResult.setReviewedBySystemId(null);
            imagingResult.setReviewedAt(null);
            imagingResult.setReviewNotes(null);
        }
    }

    private static String requireImagingType(String imagingType) {
        return LabResultService.requireEnum(imagingType, ImagingResult.IMAGING_TYPES, "imagingType",
                IMAGING_TYPE_MESSAGE);
    }

    private void publish(ImagingResult imagingResult, boolean criticalBefore, boolean created) {
        boolean critical = isCritical(imagingResult);
        eventPublisher.publishEvent(new ClinicalResultRecordedEvent(
                ClinicalResultRecordedEvent.IMAGING, imagingResult.getImagingId(), imagingResult.getPatientSystemId(),
                imagingResult.getDoctorSystemId(), authorizationService.currentSystemId(), imagingResult.getStatus(),
                critical, critical && !criticalBefore, created));
    }

    private ImagingResultResponse toResponse(ImagingResult imagingResult) {
        return toResponses(List.of(imagingResult)).get(0);
    }

    /** Maps a page of rows with one query for the names and one for the attachments. */
    private List<ImagingResultResponse> toResponses(List<ImagingResult> rows) {
        Set<String> people = new LinkedHashSet<>();
        Set<Integer> documents = new LinkedHashSet<>();
        for (ImagingResult row : rows) {
            people.add(row.getPatientSystemId());
            people.add(row.getDoctorSystemId());
            people.add(row.getReviewedBySystemId());
            documents.add(row.getDocumentId());
        }
        Map<String, LabResultRepository.PersonName> names = LabResultService.loadNames(labResultRepository, people);
        Set<Integer> usableDocuments = LabResultService.loadUsableDocuments(labResultRepository, documents);

        return rows.stream().map(row -> ImagingResultResponse.builder()
                        .imagingId(row.getImagingId())
                        .patientSystemId(row.getPatientSystemId())
                        .patientName(LabResultService.personName(names.get(row.getPatientSystemId())))
                        .doctorSystemId(row.getDoctorSystemId())
                        .doctorName(LabResultService.doctorName(names.get(row.getDoctorSystemId())))
                        .consultationId(row.getConsultationId())
                        .imagingType(row.getImagingType())
                        .bodyPart(row.getBodyPart())
                        .radiologistReport(row.getRadiologistReport())
                        .findings(row.getFindings())
                        .impression(row.getImpression())
                        .fileUrl(null)
                        .thumbnailUrl(null)
                        .status(row.getStatus())
                        .isCritical(isCritical(row))
                        .imageDate(row.getImageDate())
                        .reviewedBy(row.getReviewedBySystemId())
                        .reviewedByName(row.getReviewedBySystemId() == null ? null
                                : LabResultService.doctorName(names.get(row.getReviewedBySystemId())))
                        .reviewedAt(row.getReviewedAt())
                        .reviewNotes(row.getReviewNotes())
                        .documentId(row.getDocumentId() != null && usableDocuments.contains(row.getDocumentId())
                                ? row.getDocumentId() : null)
                        .createdAt(row.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }
}
