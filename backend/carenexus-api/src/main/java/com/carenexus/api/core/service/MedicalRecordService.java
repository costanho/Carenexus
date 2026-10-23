package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.MedicalRecord;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.MedicalRecordRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class MedicalRecordService {

    private final MedicalRecordRepository medicalRecordRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public MedicalRecordResponse createRecord(CreateMedicalRecordRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorSystemId() == null) {
            throw new RuntimeException("Doctor ID is required");
        }
        if (request.getRecordType() == null || request.getRecordType().isEmpty()) {
            throw new RuntimeException("Record type is required");
        }
        if (request.getTitle() == null || request.getTitle().isEmpty()) {
            throw new RuntimeException("Title is required");
        }

        if (!isValidRecordType(request.getRecordType())) {
            throw new RuntimeException("Invalid record type. Must be CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE, or OPERATION");
        }

        MedicalRecord record = MedicalRecord.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(request.getDoctorSystemId())
                .consultationId(request.getConsultationId())
                .recordType(request.getRecordType())
                .title(request.getTitle())
                .description(request.getDescription())
                .treatmentPlan(request.getTreatmentPlan())
                .observations(request.getObservations())
                .icdCodes(request.getIcdCodes())
                .isDeleted(false)
                .build();

        MedicalRecord savedRecord = medicalRecordRepository.save(record);
        return mapToResponse(savedRecord);
    }

    public MedicalRecordResponse getRecordById(Integer recordId) {
        MedicalRecord record = medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new RuntimeException("Medical record not found"));
        return mapToResponse(record);
    }


    public List<MedicalRecordResponse> getPatientRecords(String patientSystemId) {
        return medicalRecordRepository.findByPatientSystemIdAndIsDeletedFalse(patientSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<MedicalRecordResponse> getDoctorRecords(String doctorSystemId) {
        return medicalRecordRepository.findByDoctorSystemIdAndIsDeletedFalse(doctorSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<MedicalRecordResponse> getRecordsByType(String recordType) {
        return medicalRecordRepository.findByRecordTypeAndIsDeletedFalse(recordType).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<MedicalRecordResponse> getConsultationRecords(Integer consultationId) {
        return medicalRecordRepository.findByConsultationIdAndIsDeletedFalse(consultationId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public MedicalRecordResponse updateRecord(Integer recordId, CreateMedicalRecordRequest request) {
        MedicalRecord record = medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new RuntimeException("Medical record not found"));

        if (request.getTitle() != null) {
            record.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            record.setDescription(request.getDescription());
        }
        if (request.getTreatmentPlan() != null) {
            record.setTreatmentPlan(request.getTreatmentPlan());
        }
        if (request.getObservations() != null) {
            record.setObservations(request.getObservations());
        }
        if (request.getIcdCodes() != null) {
            record.setIcdCodes(request.getIcdCodes());
        }
        if (request.getRecordType() != null) {
            if (!isValidRecordType(request.getRecordType())) {
                throw new RuntimeException("Invalid record type");
            }
            record.setRecordType(request.getRecordType());
        }

        MedicalRecord updatedRecord = medicalRecordRepository.save(record);
        return mapToResponse(updatedRecord);
    }

    public void softDeleteRecord(Integer recordId) {
        MedicalRecord record = medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new RuntimeException("Medical record not found"));

        record.setIsDeleted(true);
        record.setDeletedAt(LocalDateTime.now());
        medicalRecordRepository.save(record);
    }

    public void deleteRecord(Integer recordId) {
        medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new RuntimeException("Medical record not found"));
        medicalRecordRepository.deleteById(recordId);
    }

    // Methods for filtering by record type for logged-in user
    private List<MedicalRecordResponse> getRecordsByTypeForUser(String userSystemId, String userRole, String recordType) {
        if ("PATIENT".equals(userRole)) {
            return medicalRecordRepository.findByPatientSystemIdAndRecordTypeAndIsDeletedFalse(userSystemId, recordType)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return medicalRecordRepository.findByDoctorSystemIdAndRecordTypeAndIsDeletedFalse(userSystemId, recordType)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<MedicalRecordResponse> getConsultRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "CONSULT");
    }

    public List<MedicalRecordResponse> getLabRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "LAB");
    }

    public List<MedicalRecordResponse> getImagingRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "IMAGING");
    }

    public List<MedicalRecordResponse> getPrescriptionRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "PRESCRIPTION");
    }

    public List<MedicalRecordResponse> getReferralRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "REFERRAL");
    }

    public List<MedicalRecordResponse> getDischargeRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "DISCHARGE");
    }

    public List<MedicalRecordResponse> getNoteRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "NOTE");
    }

    public List<MedicalRecordResponse> getOperationRecordsForUser(String userSystemId, String userRole) {
        return getRecordsByTypeForUser(userSystemId, userRole, "OPERATION");
    }

    private MedicalRecordResponse mapToResponse(MedicalRecord record) {
        return MedicalRecordResponse.builder()
                .recordId(record.getRecordId())
                .patientSystemId(record.getPatientSystemId())
                .doctorSystemId(record.getDoctorSystemId())
                .consultationId(record.getConsultationId())
                .recordType(record.getRecordType())
                .title(record.getTitle())
                .description(record.getDescription())
                .treatmentPlan(record.getTreatmentPlan())
                .observations(record.getObservations())
                .icdCodes(record.getIcdCodes())
                .isDeleted(record.getIsDeleted())
                .deletedAt(record.getDeletedAt())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private boolean isValidRecordType(String recordType) {
        return recordType != null && (recordType.equals("CONSULT") || recordType.equals("LAB") ||
                recordType.equals("IMAGING") || recordType.equals("PRESCRIPTION") ||
                recordType.equals("REFERRAL") || recordType.equals("DISCHARGE") ||
                recordType.equals("NOTE") || recordType.equals("OPERATION"));
    }
}
