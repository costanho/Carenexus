package com.carenexus.api.core.service;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateMedicalRecordRequest;
import com.carenexus.api.core.dto.response.MedicalRecordResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.MedicalRecord;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.MedicalRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Medical records. Every public method enforces access for the current caller and writes a clinical audit row
 * after it succeeds. Writes are DOCTOR-only (author doctor); reads follow AuthorizationService.requirePatientAccess
 * with {@link PatientPermission#RECORDS}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MedicalRecordService {

    private static final String RESOURCE = "MEDICAL_RECORD";
    private static final PatientPermission PERMISSION = PatientPermission.RECORDS;

    private final MedicalRecordRepository medicalRecordRepository;
    private final DoctorRepository doctorRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public MedicalRecordResponse createRecord(CreateMedicalRecordRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
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

        // Author is always the calling doctor; request.doctorSystemId is ignored.
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        MedicalRecord record = MedicalRecord.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
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
        auditService.logClinicalAccess("CREATE", RESOURCE, savedRecord.getRecordId(), savedRecord.getPatientSystemId());
        return mapToResponse(savedRecord);
    }

    public MedicalRecordResponse getRecordById(Integer recordId) {
        MedicalRecord record = load(recordId);
        authorizationService.requirePatientAccess(record.getPatientSystemId(), PERMISSION, recordId);
        auditService.logClinicalAccess("READ", RESOURCE, recordId, record.getPatientSystemId());
        return mapToResponse(record);
    }

    public List<MedicalRecordResponse> getPatientRecords(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<MedicalRecord> rows = medicalRecordRepository.findByPatientSystemIdAndIsDeletedFalse(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    public List<MedicalRecordResponse> getDoctorRecords(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<MedicalRecord> rows = medicalRecordRepository.findByDoctorSystemIdAndIsDeletedFalse(doctorSystemId);
        consultationService.auditListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Records this doctor wrote for one patient (doctor self or admin).
     */
    public List<MedicalRecordResponse> getDoctorPatientRecords(String doctorSystemId, String patientSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<MedicalRecord> rows = medicalRecordRepository
                .findByDoctorSystemIdAndPatientSystemIdAndIsDeletedFalse(doctorSystemId, patientSystemId);
        consultationService.auditListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * System-wide list by record type. Admin only.
     */
    public List<MedicalRecordResponse> getRecordsByType(String recordType) {
        authorizationService.requireAdmin();
        List<MedicalRecord> rows = medicalRecordRepository.findByRecordTypeAndIsDeletedFalse(recordType);
        consultationService.auditListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
    }

    public List<MedicalRecordResponse> getConsultationRecords(Integer consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
        String patientSystemId = consultation.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<MedicalRecord> rows = medicalRecordRepository.findByConsultationIdAndIsDeletedFalse(consultationId).stream()
                .filter(r -> patientSystemId.equals(r.getPatientSystemId()))
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    public MedicalRecordResponse updateRecord(Integer recordId, CreateMedicalRecordRequest request) {
        MedicalRecord record = load(recordId);
        consultationService.requireAuthorDoctor(record.getDoctorSystemId(), record.getPatientSystemId(), RESOURCE, recordId, false);

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
        auditService.logClinicalAccess("UPDATE", RESOURCE, recordId, record.getPatientSystemId());
        return mapToResponse(updatedRecord);
    }

    /**
     * Soft delete (is_deleted / deleted_at). Authoring doctor or admin only; audited.
     */
    public void softDeleteRecord(Integer recordId) {
        MedicalRecord record = load(recordId);
        consultationService.requireAuthorDoctor(record.getDoctorSystemId(), record.getPatientSystemId(), RESOURCE, recordId, true);

        record.setIsDeleted(true);
        record.setDeletedAt(LocalDateTime.now());
        medicalRecordRepository.save(record);
        auditService.logClinicalAccess("DELETE", RESOURCE, recordId, record.getPatientSystemId());
    }

    // Methods for filtering by record type for logged-in user
    private List<MedicalRecordResponse> getRecordsByTypeForUser(String userSystemId, String userRole, String recordType) {
        List<MedicalRecord> rows;
        if ("PATIENT".equals(userRole)) {
            authorizationService.requirePatientAccess(userSystemId, PERMISSION);
            rows = medicalRecordRepository.findByPatientSystemIdAndRecordTypeAndIsDeletedFalse(userSystemId, recordType);
        } else if ("DOCTOR".equals(userRole)) {
            authorizationService.requireDoctorSelf(userSystemId);
            doctorRepository.findBySystemId(userSystemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
            rows = medicalRecordRepository.findByDoctorSystemIdAndRecordTypeAndIsDeletedFalse(userSystemId, recordType);
        } else {
            throw new RuntimeException("Unsupported user role");
        }
        consultationService.auditListRead(RESOURCE, rows, MedicalRecord::getPatientSystemId);
        return toResponses(rows);
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

    private MedicalRecord load(Integer recordId) {
        return medicalRecordRepository.findByRecordIdAndIsDeletedFalse(recordId)
                .orElseThrow(() -> new ResourceNotFoundException("Medical record not found"));
    }

    private List<MedicalRecordResponse> toResponses(List<MedicalRecord> rows) {
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
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
