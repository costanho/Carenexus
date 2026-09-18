package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreateLabResultRequest;
import com.carenexus.api.core.dto.response.LabResultResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.LabResult;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.LabResultRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class LabResultService {

    private final LabResultRepository labResultRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public LabResultResponse createLabResult(CreateLabResultRequest request) {
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorId() == null) {
            throw new RuntimeException("Doctor ID is required");
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

        LabResult labResult = LabResult.builder()
                .patientId(request.getPatientId())
                .doctorId(request.getDoctorId())
                .consultationId(request.getConsultationId())
                .testName(request.getTestName())
                .testType(request.getTestType())
                .resultValue(request.getResultValue())
                .unit(request.getUnit())
                .referenceRange(request.getReferenceRange())
                .resultData(request.getResultData())
                .status(status)
                .isCritical(request.getIsCritical() != null ? request.getIsCritical() : false)
                .testDate(request.getTestDate() != null ? request.getTestDate() : LocalDate.now())
                .reviewedBy(request.getReviewedBy())
                .fileUrl(request.getFileUrl())
                .build();

        LabResult savedLabResult = labResultRepository.save(labResult);
        return mapToResponse(savedLabResult);
    }

    public LabResultResponse getLabResultById(Integer resultId) {
        LabResult labResult = labResultRepository.findById(resultId)
                .orElseThrow(() -> new RuntimeException("Lab result not found"));
        return mapToResponse(labResult);
    }

    public List<LabResultResponse> getPatientLabResults(Integer patientId) {
        return labResultRepository.findByPatientId(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getDoctorLabResults(Integer doctorId) {
        return labResultRepository.findByDoctorId(doctorId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getConsultationLabResults(Integer consultationId) {
        return labResultRepository.findByConsultationId(consultationId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getLabResultsByTestType(String testType) {
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        return labResultRepository.findByTestType(testType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getPatientLabResultsByTestType(Integer patientId, String testType) {
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        return labResultRepository.findByPatientIdAndTestType(patientId, testType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getDoctorLabResultsByTestType(Integer doctorId, String testType) {
        if (!isValidTestType(testType)) {
            throw new RuntimeException("Invalid test type");
        }
        return labResultRepository.findByDoctorIdAndTestType(doctorId, testType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getLabResultsByStatus(String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return labResultRepository.findByStatus(status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getPatientLabResultsByStatus(Integer patientId, String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return labResultRepository.findByPatientIdAndStatus(patientId, status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getCriticalLabResults() {
        return labResultRepository.findByIsCriticalTrue().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getPatientCriticalLabResults(Integer patientId) {
        return labResultRepository.findByPatientIdAndIsCriticalTrue(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<LabResultResponse> getPendingReviewLabResults() {
        return labResultRepository.findByStatusAndReviewedByIsNull("REQUIRES_REVIEW").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public LabResultResponse updateLabResult(Integer resultId, CreateLabResultRequest request) {
        LabResult labResult = labResultRepository.findById(resultId)
                .orElseThrow(() -> new RuntimeException("Lab result not found"));

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
            labResult.setResultData(request.getResultData());
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
        return mapToResponse(updatedLabResult);
    }

    public LabResultResponse reviewLabResult(Integer resultId, Integer reviewedBy, String status) {
        LabResult labResult = labResultRepository.findById(resultId)
                .orElseThrow(() -> new RuntimeException("Lab result not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        labResult.setReviewedBy(reviewedBy);
        labResult.setReviewedAt(LocalDateTime.now());
        labResult.setStatus(status);

        LabResult updatedLabResult = labResultRepository.save(labResult);
        return mapToResponse(updatedLabResult);
    }

    public LabResultResponse updateLabResultStatus(Integer resultId, String status) {
        LabResult labResult = labResultRepository.findById(resultId)
                .orElseThrow(() -> new RuntimeException("Lab result not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        labResult.setStatus(status);
        LabResult updatedLabResult = labResultRepository.save(labResult);
        return mapToResponse(updatedLabResult);
    }

    public void deleteLabResult(Integer resultId) {
        labResultRepository.findById(resultId)
                .orElseThrow(() -> new RuntimeException("Lab result not found"));
        labResultRepository.deleteById(resultId);
    }

    public List<LabResultResponse> getLabResultsForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientLabResults(patient.getPatientId());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return getDoctorLabResults(doctor.getDoctorId());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<LabResultResponse> getCriticalLabResultsForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientCriticalLabResults(patient.getPatientId());
        } else if ("DOCTOR".equals(userRole)) {
            return getCriticalLabResults();
        }
        throw new RuntimeException("Unsupported user role");
    }

    private LabResultResponse mapToResponse(LabResult labResult) {
        return LabResultResponse.builder()
                .resultId(labResult.getResultId())
                .patientId(labResult.getPatientId())
                .doctorId(labResult.getDoctorId())
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
                .reviewedBy(labResult.getReviewedBy())
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
