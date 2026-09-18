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
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorId() == null) {
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
                .patientId(request.getPatientId())
                .doctorId(request.getDoctorId())
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
                .reviewedBy(request.getReviewedBy())
                .build();

        ImagingResult savedImagingResult = imagingResultRepository.save(imagingResult);
        return mapToResponse(savedImagingResult);
    }

    public ImagingResultResponse getImagingResultById(Integer imagingId) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));
        return mapToResponse(imagingResult);
    }

    public List<ImagingResultResponse> getPatientImagingResults(Integer patientId) {
        return imagingResultRepository.findByPatientId(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getDoctorImagingResults(Integer doctorId) {
        return imagingResultRepository.findByDoctorId(doctorId).stream()
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

    public List<ImagingResultResponse> getPatientImagingResultsByType(Integer patientId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return imagingResultRepository.findByPatientIdAndImagingType(patientId, imagingType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getDoctorImagingResultsByType(Integer doctorId, String imagingType) {
        if (!isValidImagingType(imagingType)) {
            throw new RuntimeException("Invalid imaging type");
        }
        return imagingResultRepository.findByDoctorIdAndImagingType(doctorId, imagingType).stream()
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

    public List<ImagingResultResponse> getPatientImagingResultsByStatus(Integer patientId, String status) {
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }
        return imagingResultRepository.findByPatientIdAndStatus(patientId, status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getCriticalImagingResults() {
        return imagingResultRepository.findByStatus("CRITICAL").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPatientCriticalImagingResults(Integer patientId) {
        return imagingResultRepository.findByPatientIdAndStatus(patientId, "CRITICAL").stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ImagingResultResponse> getPendingReviewImagingResults() {
        return imagingResultRepository.findByStatusAndReviewedByIsNull("REQUIRES_REVIEW").stream()
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

    public ImagingResultResponse reviewImagingResult(Integer imagingId, Integer reviewedBy, String status) {
        ImagingResult imagingResult = imagingResultRepository.findById(imagingId)
                .orElseThrow(() -> new RuntimeException("Imaging result not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status");
        }

        imagingResult.setReviewedBy(reviewedBy);
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

    public List<ImagingResultResponse> getImagingResultsForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientImagingResults(patient.getPatientId());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return getDoctorImagingResults(doctor.getDoctorId());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ImagingResultResponse> getCriticalImagingResultsForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return getPatientCriticalImagingResults(patient.getPatientId());
        } else if ("DOCTOR".equals(userRole)) {
            return getCriticalImagingResults();
        }
        throw new RuntimeException("Unsupported user role");
    }

    private ImagingResultResponse mapToResponse(ImagingResult imagingResult) {
        return ImagingResultResponse.builder()
                .imagingId(imagingResult.getImagingId())
                .patientId(imagingResult.getPatientId())
                .doctorId(imagingResult.getDoctorId())
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
                .reviewedBy(imagingResult.getReviewedBy())
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
