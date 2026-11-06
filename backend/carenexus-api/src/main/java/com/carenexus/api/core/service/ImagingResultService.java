package com.carenexus.api.core.service;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateImagingResultRequest;
import com.carenexus.api.core.dto.response.ImagingResultResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.ImagingResult;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.ImagingResultRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Imaging results. Every public method enforces access for the current caller and writes a clinical audit row
 * after it succeeds. Create/update/delete are DOCTOR-only (author doctor; delete also admin); status and
 * review changes need a DOCTOR with a care relationship to the patient; reads follow
 * AuthorizationService.requirePatientAccess with {@link PatientPermission#IMAGING}. System-wide lists are
 * admin-only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ImagingResultService {

    private static final String RESOURCE = "IMAGING_RESULT";
    private static final PatientPermission PERMISSION = PatientPermission.IMAGING;

    private final ImagingResultRepository imagingResultRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public ImagingResultResponse createImagingResult(CreateImagingResultRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getImagingType() == null || request.getImagingType().isEmpty()) {
            throw new RuntimeException("Imaging type is required");
        }

        if (!isValidImagingType(request.getImagingType())) {
            throw new RuntimeException("Invalid imaging type. Must be XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, or ECHO");
        }

        String status = request.getStatus() != null ? request.getStatus() : "PENDING";
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status. Must be PENDING, COMPLETED, REQUIRES_REVIEW, or CRITICAL");
        }

        // Author is always the calling doctor; request.doctorSystemId and request.reviewedBy are ignored
        // (reviews are recorded through POST /api/imaging-results/{id}/review).
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        ImagingResult imagingResult = ImagingResult.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .imagingType(request.getImagingType())
                .bodyPart(request.getBodyPart())
                .radiologistReport(request.getRadiologistReport())
                .findings(request.getFindings())
                .impression(request.getImpression())
                .fileUrl(request.getFileUrl())
                .thumbnailUrl(request.getThumbnailUrl())
                .status(status)
                .imageDate(request.getImageDate() != null ? request.getImageDate() : LocalDate.now())
                .build();

        ImagingResult savedImagingResult = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("CREATE", RESOURCE, savedImagingResult.getImagingId(), savedImagingResult.getPatientSystemId());
        return mapToResponse(savedImagingResult);
    }

    public ImagingResultResponse getImagingResultById(Integer imagingId) {
        ImagingResult imagingResult = load(imagingId);
        authorizationService.requirePatientAccess(imagingResult.getPatientSystemId(), PERMISSION, imagingId);
        auditService.logClinicalAccess("READ", RESOURCE, imagingId, imagingResult.getPatientSystemId());
        return mapToResponse(imagingResult);
    }

    public List<ImagingResultResponse> getPatientImagingResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, imagingResultRepository.findByPatientSystemId(patientSystemId));
    }

    public List<ImagingResultResponse> getDoctorImagingResults(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        return auditedList(imagingResultRepository.findByDoctorSystemId(doctorSystemId));
    }

    public List<ImagingResultResponse> getConsultationImagingResults(Integer consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
        String patientSystemId = consultation.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, imagingResultRepository.findByConsultationId(consultationId).stream()
                .filter(r -> patientSystemId.equals(r.getPatientSystemId()))
                .toList());
    }

    /**
     * System-wide list by imaging type. Admin only.
     */
    public List<ImagingResultResponse> getImagingResultsByType(String imagingType) {
        authorizationService.requireAdmin();
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return auditedList(imagingResultRepository.findByImagingType(imagingType));
    }

    public List<ImagingResultResponse> getPatientImagingResultsByType(String patientSystemId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, imagingResultRepository.findByPatientSystemIdAndImagingType(patientSystemId, imagingType));
    }

    public List<ImagingResultResponse> getDoctorImagingResultsByType(String doctorSystemId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        authorizationService.requireDoctorSelf(doctorSystemId);
        return auditedList(imagingResultRepository.findByDoctorSystemIdAndImagingType(doctorSystemId, imagingType));
    }

    /**
     * System-wide list by status. Admin only.
     */
    public List<ImagingResultResponse> getImagingResultsByStatus(String status) {
        authorizationService.requireAdmin();
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return auditedList(imagingResultRepository.findByStatus(status));
    }

    public List<ImagingResultResponse> getPatientImagingResultsByStatus(String patientSystemId, String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, imagingResultRepository.findByPatientSystemIdAndStatus(patientSystemId, status));
    }

    /**
     * System-wide critical results. Admin only (doctors use /me/critical, scoped to their own results).
     */
    public List<ImagingResultResponse> getCriticalImagingResults() {
        authorizationService.requireAdmin();
        return auditedList(imagingResultRepository.findByStatus("CRITICAL"));
    }

    public List<ImagingResultResponse> getPatientCriticalImagingResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, imagingResultRepository.findByPatientSystemIdAndStatus(patientSystemId, "CRITICAL"));
    }

    /**
     * System-wide review queue. Admin only.
     */
    public List<ImagingResultResponse> getPendingReviewImagingResults() {
        authorizationService.requireAdmin();
        return auditedList(imagingResultRepository.findByStatusAndReviewedBySystemIdIsNull("REQUIRES_REVIEW"));
    }

    public ImagingResultResponse updateImagingResult(Integer imagingId, CreateImagingResultRequest request) {
        ImagingResult imagingResult = load(imagingId);
        consultationService.requireAuthorDoctor(imagingResult.getDoctorSystemId(), imagingResult.getPatientSystemId(),
                RESOURCE, imagingId, false);

        if (request.getImagingType() != null) {
            if (!isValidImagingType(request.getImagingType())) {
                throw new RuntimeException("Invalid imaging type");
            }
            imagingResult.setImagingType(request.getImagingType());
        }
        if (request.getBodyPart() != null) {
            imagingResult.setBodyPart(request.getBodyPart());
        }
        if (request.getRadiologistReport() != null) {
            imagingResult.setRadiologistReport(request.getRadiologistReport());
        }
        if (request.getFindings() != null) {
            imagingResult.setFindings(request.getFindings());
        }
        if (request.getImpression() != null) {
            imagingResult.setImpression(request.getImpression());
        }
        if (request.getFileUrl() != null) {
            imagingResult.setFileUrl(request.getFileUrl());
        }
        if (request.getThumbnailUrl() != null) {
            imagingResult.setThumbnailUrl(request.getThumbnailUrl());
        }
        if (request.getStatus() != null) {
            if (!isValidStatus(request.getStatus())) {
                throw new RuntimeException("Invalid status");
            }
            imagingResult.setStatus(request.getStatus());
        }
        if (request.getImageDate() != null) {
            imagingResult.setImageDate(request.getImageDate());
        }

        ImagingResult updatedImagingResult = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, imagingResult.getPatientSystemId());
        return mapToResponse(updatedImagingResult);
    }

    /**
     * Sign off a result. The reviewer is always the calling doctor, who must have a care relationship with
     * the patient.
     */
    public ImagingResultResponse reviewImagingResult(Integer imagingId, String status) {
        ImagingResult imagingResult = load(imagingId);
        consultationService.requireRelatedDoctor(imagingResult.getPatientSystemId(), PERMISSION, imagingId);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        imagingResult.setReviewedBySystemId(authorizationService.currentSystemId());
        imagingResult.setReviewedAt(LocalDateTime.now());
        imagingResult.setStatus(status);

        ImagingResult updatedImagingResult = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, imagingResult.getPatientSystemId());
        return mapToResponse(updatedImagingResult);
    }

    public ImagingResultResponse updateImagingResultStatus(Integer imagingId, String status) {
        ImagingResult imagingResult = load(imagingId);
        consultationService.requireRelatedDoctor(imagingResult.getPatientSystemId(), PERMISSION, imagingId);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        imagingResult.setStatus(status);
        ImagingResult updatedImagingResult = imagingResultRepository.save(imagingResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, imagingId, imagingResult.getPatientSystemId());
        return mapToResponse(updatedImagingResult);
    }

    /**
     * Hard delete (imaging_results has no soft-delete column). Authoring doctor or admin only; audited.
     */
    public void deleteImagingResult(Integer imagingId) {
        ImagingResult imagingResult = load(imagingId);
        consultationService.requireAuthorDoctor(imagingResult.getDoctorSystemId(), imagingResult.getPatientSystemId(),
                RESOURCE, imagingId, true);
        imagingResultRepository.delete(imagingResult);
        imagingResultRepository.flush();
        auditService.logClinicalAccess("DELETE", RESOURCE, imagingId, imagingResult.getPatientSystemId());
    }

    public List<ImagingResultResponse> getImagingResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));
            return getPatientImagingResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            doctorRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
            return getDoctorImagingResults(userSystemId);
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ImagingResultResponse> getCriticalImagingResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));
            return getPatientCriticalImagingResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            // Only the critical results this doctor ordered, never the whole system's.
            authorizationService.requireDoctorSelf(userSystemId);
            return auditedList(imagingResultRepository.findByDoctorSystemIdAndStatus(userSystemId, "CRITICAL"));
        }
        throw new RuntimeException("Unsupported user role");
    }

    private ImagingResult load(Integer imagingId) {
        return imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new ResourceNotFoundException("Imaging result not found"));
    }

    /** One READ row for this patient, then map. */
    private List<ImagingResultResponse> patientList(String patientSystemId, List<ImagingResult> rows) {
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /** One READ row per distinct patient in the list, then map. */
    private List<ImagingResultResponse> auditedList(List<ImagingResult> rows) {
        consultationService.auditListRead(RESOURCE, rows, ImagingResult::getPatientSystemId);
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private ImagingResultResponse mapToResponse(ImagingResult imagingResult) {
        return ImagingResultResponse.builder()
                .imagingId(imagingResult.getImagingId())
                .patientSystemId(imagingResult.getPatientSystemId())
                .doctorSystemId(imagingResult.getDoctorSystemId())
                .consultationId(imagingResult.getConsultationId())
                .imagingType(imagingResult.getImagingType())
                .bodyPart(imagingResult.getBodyPart())
                .radiologistReport(imagingResult.getRadiologistReport())
                .findings(imagingResult.getFindings())
                .impression(imagingResult.getImpression())
                .fileUrl(imagingResult.getFileUrl())
                .thumbnailUrl(imagingResult.getThumbnailUrl())
                .status(imagingResult.getStatus())
                .imageDate(imagingResult.getImageDate())
                .reviewedBy(imagingResult.getReviewedBySystemId())
                .reviewedAt(imagingResult.getReviewedAt())
                .createdAt(imagingResult.getCreatedAt())
                .build();
    }

    private boolean isValidStatus(String status) {
        return status != null && (status.equals("PENDING") || status.equals("COMPLETED") ||
                status.equals("REQUIRES_REVIEW") || status.equals("CRITICAL"));
    }

    private boolean isValidImagingType(String imagingType) {
        return imagingType != null && (imagingType.equals("XRAY") || imagingType.equals("MRI") ||
                imagingType.equals("CT_SCAN") || imagingType.equals("ULTRASOUND") || imagingType.equals("PET") ||
                imagingType.equals("MAMMOGRAPHY") || imagingType.equals("DEXA") || imagingType.equals("ECHO"));
    }
}
