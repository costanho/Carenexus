package com.carenexus.api.core.service;

import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateConsultationRequest;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultations. Every public method enforces access for the current caller (see AuthorizationService) and
 * writes a clinical audit row after it succeeds.
 *
 * This class also holds the shared clinical-write checks ({@link #requireTreatingDoctor},
 * {@link #requireAuthorDoctor}, {@link #requireRelatedDoctor}) used by the record, prescription, lab and
 * imaging services, so the "who may author/change clinical data" rule lives in one place.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ConsultationService {

    private static final String RESOURCE = "CONSULTATION";

    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    // ==================== SHARED CLINICAL WRITE CHECKS ====================

    /**
     * Preflight for creating a clinical artifact (consultation, record, prescription, lab, imaging):
     * the caller must be an active DOCTOR, the patient must exist, the doctor must have a care relationship
     * with the patient, and an optional consultationId must belong to that patient.
     *
     * @return the caller's (author doctor's) system ID, to be stored as doctorSystemId
     */
    public String requireTreatingDoctor(String patientSystemId, Integer consultationId, PatientPermission permission) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();

        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        if (!Boolean.TRUE.equals(doctor.getIsActive())) {
            throw new AuthorizationException("Your clinician account is not active");
        }

        if (patientSystemId == null || patientSystemId.isBlank()) {
            throw new RuntimeException("Patient ID is required");
        }
        patientRepository.findBySystemId(patientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        authorizationService.requirePatientAccess(patientSystemId, permission);

        if (consultationId != null) {
            Consultation consultation = consultationRepository.findById(consultationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
            if (!patientSystemId.equals(consultation.getPatientSystemId())) {
                throw new RuntimeException("Consultation does not belong to this patient");
            }
        }
        return doctorSystemId;
    }

    /**
     * The caller must be the DOCTOR who authored the row (or an ADMIN when {@code allowAdmin}).
     * Denials are audited as ACCESS_DENIED.
     */
    public void requireAuthorDoctor(String authorDoctorSystemId, String patientSystemId, String resourceType,
                                    Integer resourceId, boolean allowAdmin) {
        if (allowAdmin && authorizationService.isAdmin()) {
            return;
        }
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (AuthorizationService.DOCTOR.equals(role) && caller.equals(authorDoctorSystemId)) {
            return;
        }
        try {
            auditService.logAccessDenied(caller, resourceType, resourceId, patientSystemId, null,
                    "Caller is not the authoring doctor");
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException("Only the doctor who created this item can change it", resourceType, resourceId);
    }

    /**
     * The caller must be a DOCTOR with a care relationship to the patient.
     */
    public void requireRelatedDoctor(String patientSystemId, PatientPermission permission, Integer resourceId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        authorizationService.requirePatientAccess(patientSystemId, permission, resourceId);
    }

    /**
     * Audit one READ row per distinct patient in a list result.
     */
    public <T> void auditListRead(String resourceType, Collection<T> rows, Function<T, String> patientOf) {
        rows.stream()
                .map(patientOf)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(patient -> auditService.logClinicalAccess("READ", resourceType, null, patient));
    }

    // ==================== CONSULTATIONS ====================

    public ConsultationResponse createConsultation(CreateConsultationRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        if (request.getAppointmentId() == null) {
            throw new RuntimeException("Appointment ID is required");
        }
        Appointment appointment = appointmentRepository.findById(request.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        // The appointment is the source of truth for who the consultation is between.
        if (request.getPatientSystemId() != null && !request.getPatientSystemId().equals(appointment.getPatientSystemId())) {
            throw new RuntimeException("Appointment does not belong to this patient");
        }
        String patientSystemId = appointment.getPatientSystemId();

        if (!authorizationService.currentSystemId().equals(appointment.getDoctorSystemId())) {
            throw new AuthorizationException("You can only document consultations for your own appointments",
                    RESOURCE, null);
        }
        if ("CANCELLED".equals(appointment.getStatus())) {
            throw new RuntimeException("Cannot create a consultation for a cancelled appointment");
        }
        String doctorSystemId = requireTreatingDoctor(patientSystemId, null, PatientPermission.CONSULTATIONS);
        boolean alreadyDocumented = consultationRepository.findByAppointmentId(appointment.getAppointmentId()).stream()
                .anyMatch(c -> !"CANCELLED".equals(c.getStatus()));
        if (alreadyDocumented) {
            throw new ConflictException("A consultation already exists for this appointment");
        }

        String status = request.getStatus() != null ? request.getStatus() : "DRAFT";
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid consultation status. Must be DRAFT, IN_PROGRESS, COMPLETED, or CANCELLED");
        }

        Consultation consultation = Consultation.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .chiefComplaint(request.getChiefComplaint())
                .historyOfPresentIllness(request.getHistoryOfPresentIllness())
                .physicalExamination(request.getPhysicalExamination())
                .clinicalNotes(request.getClinicalNotes())
                .diagnosis(request.getDiagnosis())
                .treatmentPlan(request.getTreatmentPlan())
                .followUpInstructions(request.getFollowUpInstructions())
                .followUpRequired(request.getFollowUpRequired() != null ? request.getFollowUpRequired() : false)
                .followUpDate(request.getFollowUpDate())
                .durationMinutes(request.getDurationMinutes())
                .status(status)
                .build();
        applyStatusTimestamps(consultation, status);

        Consultation savedConsultation = consultationRepository.save(consultation);
        auditService.logClinicalAccess("CREATE", RESOURCE, savedConsultation.getConsultationId(), patientSystemId);
        return mapToResponse(savedConsultation);
    }

    public ConsultationResponse getConsultationById(Integer consultationId) {
        Consultation consultation = load(consultationId);
        authorizationService.requirePatientAccess(consultation.getPatientSystemId(), PatientPermission.CONSULTATIONS, consultationId);
        auditService.logClinicalAccess("READ", RESOURCE, consultationId, consultation.getPatientSystemId());
        return mapToResponse(consultation);
    }

    public List<ConsultationResponse> getConsultationsByPatientSystemId(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CONSULTATIONS);
        List<Consultation> rows = consultationRepository.findByPatientSystemId(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * Internal use only (CaregiverService builds the dependent activity feed after checking each
     * dependent_access flag itself). Not exposed by any controller.
     */
    public List<ConsultationResponse> getConsultationsForPatients(List<String> patientSystemIds) {
        if (patientSystemIds == null || patientSystemIds.isEmpty()) return List.of();
        List<Consultation> rows = consultationRepository.findByPatientSystemIdIn(patientSystemIds);
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    public List<ConsultationResponse> getConsultationsByDoctorSystemId(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = consultationRepository.findByDoctorSystemId(doctorSystemId);
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Consultations between this doctor and one patient (doctor self or admin).
     */
    public List<ConsultationResponse> getDoctorPatientConsultations(String doctorSystemId, String patientSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = consultationRepository.findByDoctorSystemIdAndPatientSystemId(doctorSystemId, patientSystemId);
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    public List<ConsultationResponse> getConsultationsByAppointmentId(Integer appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        String patientSystemId = appointment.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CONSULTATIONS);
        List<Consultation> rows = consultationRepository.findByAppointmentId(appointmentId).stream()
                .filter(c -> patientSystemId.equals(c.getPatientSystemId()))
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    public ConsultationResponse updateConsultation(Integer consultationId, CreateConsultationRequest request) {
        Consultation consultation = load(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, false);

        // appointmentId / patientSystemId / doctorSystemId are fixed at creation and never changed here.
        if (request.getChiefComplaint() != null) {
            consultation.setChiefComplaint(request.getChiefComplaint());
        }
        if (request.getHistoryOfPresentIllness() != null) {
            consultation.setHistoryOfPresentIllness(request.getHistoryOfPresentIllness());
        }
        if (request.getPhysicalExamination() != null) {
            consultation.setPhysicalExamination(request.getPhysicalExamination());
        }
        if (request.getClinicalNotes() != null) {
            consultation.setClinicalNotes(request.getClinicalNotes());
        }
        if (request.getDiagnosis() != null) {
            consultation.setDiagnosis(request.getDiagnosis());
        }
        if (request.getTreatmentPlan() != null) {
            consultation.setTreatmentPlan(request.getTreatmentPlan());
        }
        if (request.getFollowUpInstructions() != null) {
            consultation.setFollowUpInstructions(request.getFollowUpInstructions());
        }
        if (request.getFollowUpRequired() != null) {
            consultation.setFollowUpRequired(request.getFollowUpRequired());
        }
        if (request.getFollowUpDate() != null) {
            consultation.setFollowUpDate(request.getFollowUpDate());
        }
        if (request.getDurationMinutes() != null) {
            consultation.setDurationMinutes(request.getDurationMinutes());
        }
        if (request.getStatus() != null) {
            if (!isValidStatus(request.getStatus())) {
                throw new RuntimeException("Invalid consultation status");
            }
            consultation.setStatus(request.getStatus());
            applyStatusTimestamps(consultation, request.getStatus());
        }

        Consultation updatedConsultation = consultationRepository.save(consultation);
        auditService.logClinicalAccess("UPDATE", RESOURCE, consultationId, consultation.getPatientSystemId());
        return mapToResponse(updatedConsultation);
    }

    public ConsultationResponse updateConsultationStatus(Integer consultationId, String status) {
        Consultation consultation = load(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, false);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid consultation status");
        }

        consultation.setStatus(status);
        applyStatusTimestamps(consultation, status);

        Consultation updatedConsultation = consultationRepository.save(consultation);
        auditService.logClinicalAccess("UPDATE", RESOURCE, consultationId, consultation.getPatientSystemId());
        return mapToResponse(updatedConsultation);
    }

    /**
     * Hard delete (the consultations table has no soft-delete column). Only the authoring doctor or an admin;
     * audited. Consultations that already have records/prescriptions/results attached fail with 409.
     */
    public void deleteConsultation(Integer consultationId) {
        Consultation consultation = load(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, true);
        consultationRepository.delete(consultation);
        consultationRepository.flush();
        auditService.logClinicalAccess("DELETE", RESOURCE, consultationId, consultation.getPatientSystemId());
    }

    public List<ConsultationResponse> getCompletedForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, "COMPLETED");
    }

    public List<ConsultationResponse> getInProgressForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, "IN_PROGRESS");
    }

    public List<ConsultationResponse> getCancelledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, "CANCELLED");
    }

    public List<ConsultationResponse> getDraftForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, "DRAFT");
    }

    private List<ConsultationResponse> getByStatusForUser(String userSystemId, String userRole, String status) {
        List<Consultation> rows;
        if ("PATIENT".equals(userRole)) {
            authorizationService.requirePatientAccess(userSystemId, PatientPermission.CONSULTATIONS);
            rows = consultationRepository.findByPatientSystemIdAndStatus(userSystemId, status);
        } else if ("DOCTOR".equals(userRole)) {
            authorizationService.requireDoctorSelf(userSystemId);
            rows = consultationRepository.findByDoctorSystemIdAndStatus(userSystemId, status);
        } else {
            throw new RuntimeException("Unsupported user role");
        }
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    private Consultation load(Integer consultationId) {
        return consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
    }

    private void applyStatusTimestamps(Consultation consultation, String status) {
        if ("IN_PROGRESS".equals(status) && consultation.getStartedAt() == null) {
            consultation.setStartedAt(LocalDateTime.now());
        }
        if ("COMPLETED".equals(status)) {
            consultation.setCompletedAt(LocalDateTime.now());
        }
    }

    private List<ConsultationResponse> toResponses(List<Consultation> rows) {
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private ConsultationResponse mapToResponse(Consultation consultation) {
        return ConsultationResponse.builder()
                .consultationId(consultation.getConsultationId())
                .appointmentId(consultation.getAppointmentId())
                .patientSystemId(consultation.getPatientSystemId())
                .doctorSystemId(consultation.getDoctorSystemId())
                .chiefComplaint(consultation.getChiefComplaint())
                .historyOfPresentIllness(consultation.getHistoryOfPresentIllness())
                .physicalExamination(consultation.getPhysicalExamination())
                .clinicalNotes(consultation.getClinicalNotes())
                .diagnosis(consultation.getDiagnosis())
                .treatmentPlan(consultation.getTreatmentPlan())
                .followUpInstructions(consultation.getFollowUpInstructions())
                .followUpRequired(consultation.getFollowUpRequired())
                .followUpDate(consultation.getFollowUpDate())
                .durationMinutes(consultation.getDurationMinutes())
                .status(consultation.getStatus())
                .startedAt(consultation.getStartedAt())
                .completedAt(consultation.getCompletedAt())
                .createdAt(consultation.getCreatedAt())
                .build();
    }

    private boolean isValidStatus(String status) {
        return status != null && (status.equals("DRAFT") || status.equals("IN_PROGRESS") ||
                status.equals("COMPLETED") || status.equals("CANCELLED"));
    }
}
