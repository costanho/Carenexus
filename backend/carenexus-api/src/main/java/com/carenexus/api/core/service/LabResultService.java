package com.carenexus.api.core.service;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateLabResultRequest;
import com.carenexus.api.core.dto.response.LabResultResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.LabResult;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.LabResultRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lab results. Every public method enforces access for the current caller and writes a clinical audit row
 * after it succeeds. Create/update/delete are DOCTOR-only (author doctor; delete also admin); status and
 * review changes need a DOCTOR with a care relationship to the patient; reads follow
 * AuthorizationService.requirePatientAccess with {@link PatientPermission#LAB_RESULTS}. System-wide lists are
 * admin-only.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LabResultService {

    private static final String RESOURCE = "LAB_RESULT";
    private static final PatientPermission PERMISSION = PatientPermission.LAB_RESULTS;

    private final LabResultRepository labResultRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public LabResultResponse createLabResult(CreateLabResultRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getTestName() == null || request.getTestName().isEmpty()) {
            throw new RuntimeException("Test name is required");
        }
        if (request.getTestType() == null || request.getTestType().isEmpty()) {
            throw new RuntimeException("Test type is required");
        }

        if (!isValidTestType(request.getTestType())) {
            throw new RuntimeException("Invalid test type. Must be BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, or OTHER");
        }

        String status = request.getStatus() != null ? request.getStatus() : "PENDING";
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status. Must be PENDING, COMPLETED, CRITICAL, REQUIRES_REVIEW, or CANCELLED");
        }
        String resultData = validJsonOrNull(request.getResultData());

        // Author is always the calling doctor; request.doctorSystemId and request.reviewedBy are ignored
        // (reviews are recorded through POST /api/lab-results/{id}/review).
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        LabResult labResult = LabResult.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .testName(request.getTestName())
                .testType(request.getTestType())
                .resultValue(request.getResultValue())
                .unit(request.getUnit())
                .referenceRange(request.getReferenceRange())
                .resultData(resultData)
                .status(status)
                .isCritical(request.getIsCritical() != null ? request.getIsCritical() : false)
                .testDate(request.getTestDate() != null ? request.getTestDate() : LocalDate.now())
                .fileUrl(request.getFileUrl())
                .build();

        LabResult savedLabResult = labResultRepository.save(labResult);
        auditService.logClinicalAccess("CREATE", RESOURCE, savedLabResult.getResultId(), savedLabResult.getPatientSystemId());
        return mapToResponse(savedLabResult);
    }

    public LabResultResponse getLabResultById(Integer resultId) {
        LabResult labResult = load(resultId);
        authorizationService.requirePatientAccess(labResult.getPatientSystemId(), PERMISSION, resultId);
        auditService.logClinicalAccess("READ", RESOURCE, resultId, labResult.getPatientSystemId());
        return mapToResponse(labResult);
    }

    public List<LabResultResponse> getPatientLabResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, labResultRepository.findByPatientSystemId(patientSystemId));
    }

    public List<LabResultResponse> getDoctorLabResults(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        return auditedList(labResultRepository.findByDoctorSystemId(doctorSystemId));
    }

    public List<LabResultResponse> getConsultationLabResults(Integer consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
        String patientSystemId = consultation.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, labResultRepository.findByConsultationId(consultationId).stream()
                .filter(r -> patientSystemId.equals(r.getPatientSystemId()))
                .toList());
    }

    /**
     * System-wide list by test type. Admin only.
     */
    public List<LabResultResponse> getLabResultsByTestType(String testType) {
        authorizationService.requireAdmin();
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        return auditedList(labResultRepository.findByTestType(testType));
    }

    public List<LabResultResponse> getPatientLabResultsByTestType(String patientSystemId, String testType) {
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, labResultRepository.findByPatientSystemIdAndTestType(patientSystemId, testType));
    }

    public List<LabResultResponse> getDoctorLabResultsByTestType(String doctorSystemId, String testType) {
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        authorizationService.requireDoctorSelf(doctorSystemId);
        return auditedList(labResultRepository.findByDoctorSystemIdAndTestType(doctorSystemId, testType));
    }

    /**
     * System-wide list by status. Admin only.
     */
    public List<LabResultResponse> getLabResultsByStatus(String status) {
        authorizationService.requireAdmin();
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return auditedList(labResultRepository.findByStatus(status));
    }

    public List<LabResultResponse> getPatientLabResultsByStatus(String patientSystemId, String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, labResultRepository.findByPatientSystemIdAndStatus(patientSystemId, status));
    }

    /**
     * System-wide critical results. Admin only (doctors use /me/critical, scoped to their own results).
     */
    public List<LabResultResponse> getCriticalLabResults() {
        authorizationService.requireAdmin();
        return auditedList(labResultRepository.findByIsCriticalTrue());
    }

    public List<LabResultResponse> getPatientCriticalLabResults(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        return patientList(patientSystemId, labResultRepository.findByPatientSystemIdAndIsCriticalTrue(patientSystemId));
    }

    /**
     * System-wide review queue. Admin only.
     */
    public List<LabResultResponse> getPendingReviewLabResults() {
        authorizationService.requireAdmin();
        return auditedList(labResultRepository.findByStatusAndReviewedBySystemIdIsNull("REQUIRES_REVIEW"));
    }

    public LabResultResponse updateLabResult(Integer resultId, CreateLabResultRequest request) {
        LabResult labResult = load(resultId);
        consultationService.requireAuthorDoctor(labResult.getDoctorSystemId(), labResult.getPatientSystemId(),
                RESOURCE, resultId, false);

        if (request.getTestName() != null) {
            labResult.setTestName(request.getTestName());
        }
        if (request.getTestType() != null) {
            if (!isValidTestType(request.getTestType())) {
                throw new RuntimeException("Invalid test type");
            }
            labResult.setTestType(request.getTestType());
        }
        if (request.getResultValue() != null) {
            labResult.setResultValue(request.getResultValue());
        }
        if (request.getUnit() != null) {
            labResult.setUnit(request.getUnit());
        }
        if (request.getReferenceRange() != null) {
            labResult.setReferenceRange(request.getReferenceRange());
        }
        if (request.getResultData() != null) {
            labResult.setResultData(validJsonOrNull(request.getResultData()));
        }
        if (request.getStatus() != null) {
            if (!isValidStatus(request.getStatus())) {
                throw new RuntimeException("Invalid status");
            }
            labResult.setStatus(request.getStatus());
        }
        if (request.getIsCritical() != null) {
            labResult.setIsCritical(request.getIsCritical());
        }
        if (request.getTestDate() != null) {
            labResult.setTestDate(request.getTestDate());
        }
        if (request.getFileUrl() != null) {
            labResult.setFileUrl(request.getFileUrl());
        }

        LabResult updatedLabResult = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, labResult.getPatientSystemId());
        return mapToResponse(updatedLabResult);
    }

    /**
     * Sign off a result. The reviewer is always the calling doctor, who must have a care relationship with
     * the patient.
     */
    public LabResultResponse reviewLabResult(Integer resultId, String status) {
        LabResult labResult = load(resultId);
        consultationService.requireRelatedDoctor(labResult.getPatientSystemId(), PERMISSION, resultId);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        labResult.setReviewedBySystemId(authorizationService.currentSystemId());
        labResult.setReviewedAt(LocalDateTime.now());
        labResult.setStatus(status);

        LabResult updatedLabResult = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, labResult.getPatientSystemId());
        return mapToResponse(updatedLabResult);
    }

    public LabResultResponse updateLabResultStatus(Integer resultId, String status) {
        LabResult labResult = load(resultId);
        consultationService.requireRelatedDoctor(labResult.getPatientSystemId(), PERMISSION, resultId);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        labResult.setStatus(status);
        LabResult updatedLabResult = labResultRepository.save(labResult);
        auditService.logClinicalAccess("UPDATE", RESOURCE, resultId, labResult.getPatientSystemId());
        return mapToResponse(updatedLabResult);
    }

    /**
     * Hard delete (lab_results has no soft-delete column; use status CANCELLED to retire a result instead).
     * Authoring doctor or admin only; audited.
     */
    public void deleteLabResult(Integer resultId) {
        LabResult labResult = load(resultId);
        consultationService.requireAuthorDoctor(labResult.getDoctorSystemId(), labResult.getPatientSystemId(),
                RESOURCE, resultId, true);
        labResultRepository.delete(labResult);
        labResultRepository.flush();
        auditService.logClinicalAccess("DELETE", RESOURCE, resultId, labResult.getPatientSystemId());
    }

    public List<LabResultResponse> getLabResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));
            return getPatientLabResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            doctorRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
            return getDoctorLabResults(userSystemId);
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<LabResultResponse> getCriticalLabResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));
            return getPatientCriticalLabResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            // Only the critical results this doctor ordered, never the whole system's.
            authorizationService.requireDoctorSelf(userSystemId);
            return auditedList(labResultRepository.findByDoctorSystemIdAndIsCriticalTrue(userSystemId));
        }
        throw new RuntimeException("Unsupported user role");
    }

    private LabResult load(Integer resultId) {
        return labResultRepository.findById(resultId)
                .orElseThrow(() -> new ResourceNotFoundException("Lab result not found"));
    }

    /** One READ row for this patient, then map. */
    private List<LabResultResponse> patientList(String patientSystemId, List<LabResult> rows) {
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /** One READ row per distinct patient in the list, then map. */
    private List<LabResultResponse> auditedList(List<LabResult> rows) {
        consultationService.auditListRead(RESOURCE, rows, LabResult::getPatientSystemId);
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    /**
     * resultData is stored as jsonb: blank becomes null, anything else must be valid JSON.
     */
    private String validJsonOrNull(String resultData) {
        if (resultData == null || resultData.isBlank()) {
            return null;
        }
        try {
            objectMapper.readTree(resultData);
            return resultData;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("resultData must be valid JSON");
        }
    }

    private LabResultResponse mapToResponse(LabResult labResult) {
        return LabResultResponse.builder()
                .resultId(labResult.getResultId())
                .patientSystemId(labResult.getPatientSystemId())
                .doctorSystemId(labResult.getDoctorSystemId())
                .consultationId(labResult.getConsultationId())
                .testName(labResult.getTestName())
                .testType(labResult.getTestType())
                .resultValue(labResult.getResultValue())
                .unit(labResult.getUnit())
                .referenceRange(labResult.getReferenceRange())
                .resultData(labResult.getResultData())
                .status(labResult.getStatus())
                .isCritical(labResult.getIsCritical())
                .testDate(labResult.getTestDate())
                .reviewedBy(labResult.getReviewedBySystemId())
                .reviewedAt(labResult.getReviewedAt())
                .fileUrl(labResult.getFileUrl())
                .createdAt(labResult.getCreatedAt())
                .build();
    }

    private boolean isValidStatus(String status) {
        return status != null && (status.equals("PENDING") || status.equals("COMPLETED") ||
                status.equals("CRITICAL") || status.equals("REQUIRES_REVIEW") || status.equals("CANCELLED"));
    }

    private boolean isValidTestType(String testType) {
        return testType != null && (testType.equals("BLOOD") || testType.equals("URINE") ||
                testType.equals("STOOL") || testType.equals("CULTURE") || testType.equals("BIOPSY") ||
                testType.equals("GENETIC") || testType.equals("SWAB") || testType.equals("OTHER"));
    }
}
