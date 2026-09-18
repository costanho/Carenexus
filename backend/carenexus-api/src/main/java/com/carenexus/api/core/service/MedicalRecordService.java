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
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorId() == null) {
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
                .patientId(request.getPatientId())
                .doctorId(request.getDoctorId())
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

    public List<MedicalRecordResponse> getPatientRecords(Integer patientId) {
        return medicalRecordRepository.findByPatientIdAndIsDeletedFalse(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<MedicalRecordResponse> getDoctorRecords(Integer doctorId) {
        return medicalRecordRepository.findByDoctorIdAndIsDeletedFalse(doctorId).stream()
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
    private List<MedicalRecordResponse> getRecordsByTypeForUser(Integer userId, String userRole, String recordType) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return medicalRecordRepository.findByPatientIdAndRecordTypeAndIsDeletedFalse(patient.getPatientId(), recordType)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return medicalRecordRepository.findByDoctorIdAndRecordTypeAndIsDeletedFalse(doctor.getDoctorId(), recordType)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<MedicalRecordResponse> getConsultRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "CONSULT");
    }

    public List<MedicalRecordResponse> getLabRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "LAB");
    }

    public List<MedicalRecordResponse> getImagingRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "IMAGING");
    }

    public List<MedicalRecordResponse> getPrescriptionRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "PRESCRIPTION");
    }

    public List<MedicalRecordResponse> getReferralRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "REFERRAL");
    }

    public List<MedicalRecordResponse> getDischargeRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "DISCHARGE");
    }

    public List<MedicalRecordResponse> getNoteRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "NOTE");
    }

    public List<MedicalRecordResponse> getOperationRecordsForUser(Integer userId, String userRole) {
        return getRecordsByTypeForUser(userId, userRole, "OPERATION");
    }

    private MedicalRecordResponse mapToResponse(MedicalRecord record) {
        return MedicalRecordResponse.builder()
                .recordId(record.getRecordId())
                .patientId(record.getPatientId())
                .doctorId(record.getDoctorId())
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
