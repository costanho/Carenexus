package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreateConsultationRequest;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
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
public class ConsultationService {

    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public ConsultationResponse createConsultation(CreateConsultationRequest request) {
        if (request.getPatientId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorId() == null) {
            throw new RuntimeException("Doctor ID is required");
        }
        if (request.getAppointmentId() == null) {
            throw new RuntimeException("Appointment ID is required");
        }

        String status = request.getStatus() != null ? request.getStatus() : "DRAFT";
        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid consultation status. Must be DRAFT, IN_PROGRESS, COMPLETED, or CANCELLED");
        }

        Consultation consultation = Consultation.builder()
                .appointmentId(request.getAppointmentId())
                .patientId(request.getPatientId())
                .doctorId(request.getDoctorId())
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

        Consultation savedConsultation = consultationRepository.save(consultation);
        return mapToResponse(savedConsultation);
    }

    public ConsultationResponse getConsultationById(Integer consultationId) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));
        return mapToResponse(consultation);
    }

    public List<ConsultationResponse> getConsultationsByPatientId(Integer patientId) {
        return consultationRepository.findByPatientId(patientId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ConsultationResponse> getConsultationsByDoctorId(Integer doctorId) {
        return consultationRepository.findByDoctorId(doctorId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<ConsultationResponse> getConsultationsByAppointmentId(Integer appointmentId) {
        return consultationRepository.findByAppointmentId(appointmentId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public ConsultationResponse updateConsultation(Integer consultationId, CreateConsultationRequest request) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));

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
        }

        Consultation updatedConsultation = consultationRepository.save(consultation);
        return mapToResponse(updatedConsultation);
    }

    public ConsultationResponse updateConsultationStatus(Integer consultationId, String status) {
        Consultation consultation = consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));

        if (!isValidStatus(status)) {
            throw new RuntimeException("Invalid consultation status");
        }

        consultation.setStatus(status);
        if ("IN_PROGRESS".equals(status)) {
            consultation.setStartedAt(LocalDateTime.now());
        }
        if ("COMPLETED".equals(status)) {
            consultation.setCompletedAt(LocalDateTime.now());
        }

        Consultation updatedConsultation = consultationRepository.save(consultation);
        return mapToResponse(updatedConsultation);
    }

    public void deleteConsultation(Integer consultationId) {
        consultationRepository.findById(consultationId)
                .orElseThrow(() -> new RuntimeException("Consultation not found"));
        consultationRepository.deleteById(consultationId);
    }

    public List<ConsultationResponse> getCompletedForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return consultationRepository.findByPatientIdAndStatus(patient.getPatientId(), "COMPLETED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return consultationRepository.findByDoctorIdAndStatus(doctor.getDoctorId(), "COMPLETED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ConsultationResponse> getInProgressForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return consultationRepository.findByPatientIdAndStatus(patient.getPatientId(), "IN_PROGRESS")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return consultationRepository.findByDoctorIdAndStatus(doctor.getDoctorId(), "IN_PROGRESS")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ConsultationResponse> getCancelledForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return consultationRepository.findByPatientIdAndStatus(patient.getPatientId(), "CANCELLED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return consultationRepository.findByDoctorIdAndStatus(doctor.getDoctorId(), "CANCELLED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<ConsultationResponse> getDraftForUser(Integer userId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            Patient patient = patientRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Patient profile not found"));
            return consultationRepository.findByPatientIdAndStatus(patient.getPatientId(), "DRAFT")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            Doctor doctor = doctorRepository.findByUserId(userId)
                    .orElseThrow(() -> new RuntimeException("Doctor profile not found"));
            return consultationRepository.findByDoctorIdAndStatus(doctor.getDoctorId(), "DRAFT")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    private ConsultationResponse mapToResponse(Consultation consultation) {
        return ConsultationResponse.builder()
                .consultationId(consultation.getConsultationId())
                .appointmentId(consultation.getAppointmentId())
                .patientId(consultation.getPatientId())
                .doctorId(consultation.getDoctorId())
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
