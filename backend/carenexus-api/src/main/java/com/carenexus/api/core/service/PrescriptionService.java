package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreatePrescriptionRequest;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.carenexus.api.core.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public PrescriptionResponse createPrescription(CreatePrescriptionRequest request) {
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorId() == null) {
            throw new RuntimeException("Doctor ID is required");
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

        Prescription prescription = Prescription.builder()
                .patientId(request.getPatientId())
                .doctorId(request.getDoctorId())
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
        return mapToResponse(savedPrescription);
    }

    public PrescriptionResponse getPrescriptionById(Integer prescriptionId) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        return mapToResponse(prescription);
    }

    public List<PrescriptionResponse> getPatientPrescriptions(Integer patientId) {
        return prescriptionRepository.findByPatientId(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<PrescriptionResponse> getDoctorPrescriptions(Integer doctorId) {
        return prescriptionRepository.findByDoctorId(doctorId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<PrescriptionResponse> getConsultationPrescriptions(Integer consultationId) {
        return prescriptionRepository.findByConsultationId(consultationId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public PrescriptionResponse updatePrescription(Integer prescriptionId, CreatePrescriptionRequest request) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));

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
        return mapToResponse(updatedPrescription);
    }

    public PrescriptionResponse updatePrescriptionStatus(Integer prescriptionId, String status) {
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid prescription status");
        }

        prescription.setStatus(status);
        Prescription updatedPrescription = prescriptionRepository.save(prescription);
        return mapToResponse(updatedPrescription);
    }

    public void deletePrescription(Integer prescriptionId) {
        prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new RuntimeException("Prescription not found"));
        prescriptionRepository.deleteById(prescriptionId);
    }

    public List<PrescriptionResponse> getActiveForUser(Integer userId, String userRole) {
        return getPrescriptionsByStatusForUser(userId, userRole, "ACTIVE");
    }

    public List<PrescriptionResponse> getCompletedForUser(Integer userId, String userRole) {
        return getPrescriptionsByStatusForUser(userId, userRole, "COMPLETED");
    }

    public List<PrescriptionResponse> getCancelledForUser(Integer userId, String userRole) {
        return getPrescriptionsByStatusForUser(userId, userRole, "CANCELLED");
    }

    public List<PrescriptionResponse> getExpiredForUser(Integer userId, String userRole) {
        return getPrescriptionsByStatusForUser(userId, userRole, "EXPIRED");
    }

    public List<PrescriptionResponse> getOnHoldForUser(Integer userId, String userRole) {
        return getPrescriptionsByStatusForUser(userId, userRole, "ON_HOLD");
    }

    private List<PrescriptionResponse> getPrescriptionsByStatusForUser(Integer userId, String userRole, String status) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return prescriptionRepository.findByPatientIdAndStatus(patient.getPatientId(), status)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return prescriptionRepository.findByDoctorIdAndStatus(doctor.getDoctorId(), status)
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    private PrescriptionResponse mapToResponse(Prescription prescription) {
        return PrescriptionResponse.builder()
                .prescriptionId(prescription.getPrescriptionId())
                .patientId(prescription.getPatientId())
                .doctorId(prescription.getDoctorId())
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
