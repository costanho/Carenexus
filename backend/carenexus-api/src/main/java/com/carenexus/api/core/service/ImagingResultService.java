package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreateImagingResultRequest;
import com.carenexus.api.core.dto.response.ImagingResultResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.ImagingResult;
import com.carenexus.api.core.model.Patient;
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

@Service
@RequiredArgsConstructor
@Transactional
public class ImagingResultService {

    private final ImagingResultRepository imagingResultRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public ImagingResultResponse createImagingResult(CreateImagingResultRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorSystemId() == null) {
            throw new RuntimeException("Doctor ID is required");
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

        ImagingResult imagingResult = ImagingResult.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(request.getDoctorSystemId())
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
                .reviewedBySystemId(request.getReviewedBy())
                .build();

        ImagingResult savedImagingResult = imagingResultRepository.save(imagingResult);
        return mapToResponse(savedImagingResult);
    }

    public ImagingResultResponse getImagingResultById(Integer imagingId) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));
        return mapToResponse(imagingResult);
    }

    public List<ImagingResultResponse> getPatientImagingResults(String patientSystemId) {
        return imagingResultRepository.findByPatientSystemId(patientSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getDoctorImagingResults(String doctorSystemId) {
        return imagingResultRepository.findByDoctorSystemId(doctorSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getConsultationImagingResults(Integer consultationId) {
        return imagingResultRepository.findByConsultationId(consultationId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getImagingResultsByType(String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return imagingResultRepository.findByImagingType(imagingType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPatientImagingResultsByType(String patientSystemId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return imagingResultRepository.findByPatientSystemIdAndImagingType(patientSystemId, imagingType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getDoctorImagingResultsByType(String doctorSystemId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return imagingResultRepository.findByDoctorSystemIdAndImagingType(doctorSystemId, imagingType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getImagingResultsByStatus(String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return imagingResultRepository.findByStatus(status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPatientImagingResultsByStatus(String patientSystemId, String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return imagingResultRepository.findByPatientSystemIdAndStatus(patientSystemId, status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getCriticalImagingResults() {
        return imagingResultRepository.findByStatus("CRITICAL").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPatientCriticalImagingResults(String patientSystemId) {
        return imagingResultRepository.findByPatientSystemIdAndStatus(patientSystemId, "CRITICAL").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPendingReviewImagingResults() {
        return imagingResultRepository.findByStatusAndReviewedBySystemIdIsNull("REQUIRES_REVIEW").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ImagingResultResponse updateImagingResult(Integer imagingId, CreateImagingResultRequest request) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));

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
        return mapToResponse(updatedImagingResult);
    }

    public ImagingResultResponse reviewImagingResult(Integer imagingId, String reviewedBy, String status) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        imagingResult.setReviewedBySystemId(reviewedBy);
        imagingResult.setReviewedAt(LocalDateTime.now());
        imagingResult.setStatus(status);

        ImagingResult updatedImagingResult = imagingResultRepository.save(imagingResult);
        return mapToResponse(updatedImagingResult);
    }

    public ImagingResultResponse updateImagingResultStatus(Integer imagingId, String status) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        imagingResult.setStatus(status);
        ImagingResult updatedImagingResult = imagingResultRepository.save(imagingResult);
        return mapToResponse(updatedImagingResult);
    }

    public void deleteImagingResult(Integer imagingId) {
        imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));
        imagingResultRepository.deleteById(imagingId);
    }

    public List<ImagingResultResponse> getImagingResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientImagingResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return getDoctorImagingResults(userSystemId);
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ImagingResultResponse> getCriticalImagingResultsForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientCriticalImagingResults(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            return getCriticalImagingResults();
        }
        throw new RuntimeException("Unsupported user role");
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
