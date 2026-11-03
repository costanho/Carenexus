package com.carenexus.api.core.service;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreatePrescriptionRequest;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prescriptions. Every public method enforces access for the current caller and writes a clinical audit row
 * after it succeeds. Writes are DOCTOR-only (author doctor); reads follow AuthorizationService.requirePatientAccess
 * with {@link PatientPermission#MEDICATIONS}.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PrescriptionService {

    private static final String RESOURCE = "PRESCRIPTION";
    private static final PatientPermission PERMISSION = PatientPermission.MEDICATIONS;

    private final PrescriptionRepository prescriptionRepository;
    private final ConsultationRepository consultationRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    public PrescriptionResponse createPrescription(CreatePrescriptionRequest request) {
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getMedicationName() == null || request.getMedicationName().isEmpty()) {
            throw new RuntimeException("Medication name is required");
        }
        if (request.getDosage() == null || request.getDosage().isEmpty()) {
            throw new RuntimeException("Dosage is required");
        }
        if (request.getFrequency() == null || request.getFrequency().isEmpty()) {
            throw new RuntimeException("Frequency is required");
        }

        String route = request.getRoute() != null ? request.getRoute() : "ORAL";
        if (!isValidRoute(route)) {
            throw new RuntimeException("Invalid route. Must be ORAL, TOPICAL, INJECTION, INHALED, SUBLINGUAL, RECTAL, or IV");
        }

        String status = request.getStatus() != null ? request.getStatus() : "ACTIVE";
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid status. Must be ACTIVE, COMPLETED, CANCELLED, EXPIRED, or ON_HOLD");
        }

        // Author is always the calling doctor; request.doctorSystemId is ignored.
        String doctorSystemId = consultationService.requireTreatingDoctor(
                request.getPatientSystemId(), request.getConsultationId(), PERMISSION);

        Prescription prescription = Prescription.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(doctorSystemId)
                .consultationId(request.getConsultationId())
                .medicationName(request.getMedicationName())
                .genericName(request.getGenericName())
                .dosage(request.getDosage())
                .frequency(request.getFrequency())
                .route(route)
                .quantity(request.getQuantity())
                .refillsAllowed(request.getRefillsAllowed() != null ? request.getRefillsAllowed() : 0)
                .specialInstructions(request.getSpecialInstructions())
                .status(status)
                .prescribedDate(request.getPrescribedDate() != null ? request.getPrescribedDate() : LocalDate.now())
                .expiryDate(request.getExpiryDate())
                .build();

        Prescription savedPrescription = prescriptionRepository.save(prescription);
        auditService.logClinicalAccess("CREATE", RESOURCE, savedPrescription.getPrescriptionId(), savedPrescription.getPatientSystemId());
        return mapToResponse(savedPrescription);
    }

    public PrescriptionResponse getPrescriptionById(Integer prescriptionId) {
        Prescription prescription = load(prescriptionId);
        authorizationService.requirePatientAccess(prescription.getPatientSystemId(), PERMISSION, prescriptionId);
        auditService.logClinicalAccess("READ", RESOURCE, prescriptionId, prescription.getPatientSystemId());
        return mapToResponse(prescription);
    }

    public List<PrescriptionResponse> getPatientPrescriptions(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<Prescription> rows = prescriptionRepository.findByPatientSystemId(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    public List<PrescriptionResponse> getDoctorPrescriptions(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Prescription> rows = prescriptionRepository.findByDoctorSystemId(doctorSystemId);
        consultationService.auditListRead(RESOURCE, rows, Prescription::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Prescriptions this doctor wrote for one patient (doctor self or admin).
     */
    public List<PrescriptionResponse> getDoctorPatientPrescriptions(String doctorSystemId, String patientSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Prescription> rows = prescriptionRepository.findByDoctorSystemIdAndPatientSystemId(doctorSystemId, patientSystemId);
        consultationService.auditListRead(RESOURCE, rows, Prescription::getPatientSystemId);
        return toResponses(rows);
    }

    public List<PrescriptionResponse> getConsultationPrescriptions(Integer consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException("Consultation not found"));
        String patientSystemId = consultation.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PERMISSION);
        List<Prescription> rows = prescriptionRepository.findByConsultationId(consultationId).stream()
                .filter(p -> patientSystemId.equals(p.getPatientSystemId()))
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    public PrescriptionResponse updatePrescription(Integer prescriptionId, CreatePrescriptionRequest request) {
        Prescription prescription = load(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, false);

        if (request.getMedicationName() != null) {
            prescription.setMedicationName(request.getMedicationName());
        }
        if (request.getGenericName() != null) {
            prescription.setGenericName(request.getGenericName());
        }
        if (request.getDosage() != null) {
            prescription.setDosage(request.getDosage());
        }
        if (request.getFrequency() != null) {
            prescription.setFrequency(request.getFrequency());
        }
        if (request.getRoute() != null) {
            if (!isValidRoute(request.getRoute())) {
                throw new RuntimeException("Invalid route");
            }
            prescription.setRoute(request.getRoute());
        }
        if (request.getQuantity() != null) {
            prescription.setQuantity(request.getQuantity());
        }
        if (request.getRefillsAllowed() != null) {
            prescription.setRefillsAllowed(request.getRefillsAllowed());
        }
        if (request.getSpecialInstructions() != null) {
            prescription.setSpecialInstructions(request.getSpecialInstructions());
        }
        if (request.getExpiryDate() != null) {
            prescription.setExpiryDate(request.getExpiryDate());
        }
        if (request.getStatus() != null) {
            if (!isValidStatus(request.getStatus())) {
                throw new RuntimeException("Invalid status");
            }
            prescription.setStatus(request.getStatus());
        }

        Prescription updatedPrescription = prescriptionRepository.save(prescription);
        auditService.logClinicalAccess("UPDATE", RESOURCE, prescriptionId, prescription.getPatientSystemId());
        return mapToResponse(updatedPrescription);
    }

    public PrescriptionResponse updatePrescriptionStatus(Integer prescriptionId, String status) {
        Prescription prescription = load(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, false);

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid prescription status");
        }

        prescription.setStatus(status);
        Prescription updatedPrescription = prescriptionRepository.save(prescription);
        auditService.logClinicalAccess("UPDATE", RESOURCE, prescriptionId, prescription.getPatientSystemId());
        return mapToResponse(updatedPrescription);
    }

    /**
     * Hard delete (the prescriptions table has no soft-delete column; use status CANCELLED to retire a
     * prescription instead). Authoring doctor or admin only; audited.
     */
    public void deletePrescription(Integer prescriptionId) {
        Prescription prescription = load(prescriptionId);
        consultationService.requireAuthorDoctor(prescription.getDoctorSystemId(), prescription.getPatientSystemId(),
                RESOURCE, prescriptionId, true);
        prescriptionRepository.delete(prescription);
        prescriptionRepository.flush();
        auditService.logClinicalAccess("DELETE", RESOURCE, prescriptionId, prescription.getPatientSystemId());
    }

    public List<PrescriptionResponse> getActiveForUser(String userSystemId, String userRole) {
        return getPrescriptionsByStatusForUser(userSystemId, userRole, "ACTIVE");
    }

    public List<PrescriptionResponse> getCompletedForUser(String userSystemId, String userRole) {
        return getPrescriptionsByStatusForUser(userSystemId, userRole, "COMPLETED");
    }

    public List<PrescriptionResponse> getCancelledForUser(String userSystemId, String userRole) {
        return getPrescriptionsByStatusForUser(userSystemId, userRole, "CANCELLED");
    }

    public List<PrescriptionResponse> getExpiredForUser(String userSystemId, String userRole) {
        return getPrescriptionsByStatusForUser(userSystemId, userRole, "EXPIRED");
    }

    public List<PrescriptionResponse> getOnHoldForUser(String userSystemId, String userRole) {
        return getPrescriptionsByStatusForUser(userSystemId, userRole, "ON_HOLD");
    }

    private List<PrescriptionResponse> getPrescriptionsByStatusForUser(String userSystemId, String userRole, String status) {
        List<Prescription> rows;
        if ("PATIENT".equals(userRole)) {
            authorizationService.requirePatientAccess(userSystemId, PERMISSION);
            rows = prescriptionRepository.findByPatientSystemIdAndStatus(userSystemId, status);
        } else if ("DOCTOR".equals(userRole)) {
            authorizationService.requireDoctorSelf(userSystemId);
            rows = prescriptionRepository.findByDoctorSystemIdAndStatus(userSystemId, status);
        } else {
            throw new RuntimeException("Unsupported user role");
        }
        consultationService.auditListRead(RESOURCE, rows, Prescription::getPatientSystemId);
        return toResponses(rows);
    }

    private Prescription load(Integer prescriptionId) {
        return prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
    }

    private List<PrescriptionResponse> toResponses(List<Prescription> rows) {
        return rows.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private PrescriptionResponse mapToResponse(Prescription prescription) {
        return PrescriptionResponse.builder()
                .prescriptionId(prescription.getPrescriptionId())
                .patientSystemId(prescription.getPatientSystemId())
                .doctorSystemId(prescription.getDoctorSystemId())
                .consultationId(prescription.getConsultationId())
                .medicationName(prescription.getMedicationName())
                .genericName(prescription.getGenericName())
                .dosage(prescription.getDosage())
                .frequency(prescription.getFrequency())
                .route(prescription.getRoute())
                .quantity(prescription.getQuantity())
                .refillsAllowed(prescription.getRefillsAllowed())
                .specialInstructions(prescription.getSpecialInstructions())
                .status(prescription.getStatus())
                .prescribedDate(prescription.getPrescribedDate())
                .expiryDate(prescription.getExpiryDate())
                .createdAt(prescription.getCreatedAt())
                .build();
    }

    private boolean isValidStatus(String status) {
        return status != null && (status.equals("ACTIVE") || status.equals("COMPLETED") ||
                status.equals("CANCELLED") || status.equals("EXPIRED") || status.equals("ON_HOLD"));
    }

    private boolean isValidRoute(String route) {
        return route != null && (route.equals("ORAL") || route.equals("TOPICAL") ||
                route.equals("INJECTION") || route.equals("INHALED") ||
                route.equals("SUBLINGUAL") || route.equals("RECTAL") || route.equals("IV"));
    }
}
