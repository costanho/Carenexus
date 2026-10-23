package com.carenexus.api.core.service;

import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.AppointmentRepository;
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
public class AppointmentService {

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;

    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        // Validate required fields
        if (request.getPatientSystemId() == null) {
            throw new RuntimeException("Patient ID is required");
        }
        if (request.getDoctorSystemId() == null) {
            throw new RuntimeException("Doctor ID is required");
        }
        if (request.getScheduledAt() == null) {
            throw new RuntimeException("Scheduled date/time is required");
        }
        if (request.getScheduledAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Appointment cannot be scheduled in the past");
        }

        // Validate type
        String type = request.getType() != null ? request.getType() : "IN_PERSON";
        if (!isValidType(type)) {
            throw new RuntimeException("Invalid appointment type. Must be VIDEO, IN_PERSON, or PHONE");
        }

        // VIDEO appointments require consultation link
        if ("VIDEO".equals(type) && (request.getVideoConsultationLink() == null || request.getVideoConsultationLink().isEmpty())) {
            throw new RuntimeException("Video consultation link is required for VIDEO appointments");
        }

        // Create appointment
        Appointment appointment = Appointment.builder()
                .patientSystemId(request.getPatientSystemId())
                .doctorSystemId(request.getDoctorSystemId())
                .facilityId(request.getFacilityId())
                .scheduledAt(request.getScheduledAt())
                .durationMinutes(request.getDurationMinutes() != null ? request.getDurationMinutes() : 30)
                .type(type)
                .status("SCHEDULED")
                .reasonForVisit(request.getReasonForVisit())
                .videoConsultationLink(request.getVideoConsultationLink())
                .reminderSent(false)
                .notes(request.getNotes())
                .build();

        Appointment savedAppointment = appointmentRepository.save(appointment);
        return mapToResponse(savedAppointment);
    }

    public AppointmentResponse getAppointmentById(Integer appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));
        return mapToResponse(appointment);
    }

    public List<AppointmentResponse> getAppointmentsByPatientSystemId(String patientSystemId) {
        return appointmentRepository.findByPatientSystemId(patientSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<AppointmentResponse> getAppointmentsByDoctorSystemId(String doctorSystemId) {
        return appointmentRepository.findByDoctorSystemId(doctorSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<AppointmentResponse> getUpcomingAppointments(String patientSystemId) {
        List<Appointment> appointments = appointmentRepository.findByPatientSystemIdAndStatus(patientSystemId, "SCHEDULED");
        return appointments.stream()
                .filter(a -> a.getScheduledAt().isAfter(LocalDateTime.now()))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public AppointmentResponse updateAppointmentStatus(Integer appointmentId, String newStatus) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        if (!isValidStatus(newStatus)) {
            throw new RuntimeException("Invalid appointment status");
        }

        appointment.setStatus(newStatus);
        Appointment updatedAppointment = appointmentRepository.save(appointment);
        return mapToResponse(updatedAppointment);
    }

    public AppointmentResponse updateAppointment(Integer appointmentId, CreateAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        if (!"SCHEDULED".equals(appointment.getStatus())) {
            throw new RuntimeException("Can only update SCHEDULED appointments");
        }

        if (request.getScheduledAt() != null) {
            if (request.getScheduledAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Appointment cannot be scheduled in the past");
            }
            appointment.setScheduledAt(request.getScheduledAt());
        }

        if (request.getDurationMinutes() != null) {
            appointment.setDurationMinutes(request.getDurationMinutes());
        }

        if (request.getType() != null) {
            if (!isValidType(request.getType())) {
                throw new RuntimeException("Invalid appointment type");
            }
            appointment.setType(request.getType());
        }

        if (request.getReasonForVisit() != null) {
            appointment.setReasonForVisit(request.getReasonForVisit());
        }

        if (request.getVideoConsultationLink() != null) {
            appointment.setVideoConsultationLink(request.getVideoConsultationLink());
        }

        if (request.getNotes() != null) {
            appointment.setNotes(request.getNotes());
        }

        Appointment updatedAppointment = appointmentRepository.save(appointment);
        return mapToResponse(updatedAppointment);
    }

    public void cancelAppointment(Integer appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new RuntimeException("Appointment not found"));

        if ("COMPLETED".equals(appointment.getStatus()) || "CANCELLED".equals(appointment.getStatus())) {
            throw new RuntimeException("Cannot cancel a " + appointment.getStatus() + " appointment");
        }

        appointment.setStatus("CANCELLED");
        appointmentRepository.save(appointment);
    }

    public List<AppointmentResponse> getDoctorSchedule(String doctorSystemId, LocalDateTime start, LocalDateTime end) {
        return appointmentRepository.findByDoctorSystemIdAndScheduledAtBetween(doctorSystemId, start, end).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<AppointmentResponse> getAppointmentsByStatus(String status) {
        return appointmentRepository.findByStatus(status).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<AppointmentResponse> getUpcomingForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            return getUpcomingAppointments(userSystemId);
        } else if ("DOCTOR".equals(userRole)) {
            return appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, "SCHEDULED")
                    .stream()
                    .filter(a -> a.getScheduledAt().isAfter(LocalDateTime.now()))
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<AppointmentResponse> getCompletedForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            return appointmentRepository.findByPatientSystemIdAndStatus(userSystemId, "COMPLETED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            return appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, "COMPLETED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<AppointmentResponse> getCancelledForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            return appointmentRepository.findByPatientSystemIdAndStatus(userSystemId, "CANCELLED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            return appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, "CANCELLED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<AppointmentResponse> getRescheduledForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            return appointmentRepository.findByPatientSystemIdAndStatus(userSystemId, "RESCHEDULED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            return appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, "RESCHEDULED")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    public List<AppointmentResponse> getNoShowForUser(String userSystemId, String userRole) {
        if ("PATIENT".equals(userRole)) {
            return appointmentRepository.findByPatientSystemIdAndStatus(userSystemId, "NO_SHOW")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        } else if ("DOCTOR".equals(userRole)) {
            return appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, "NO_SHOW")
                    .stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw new RuntimeException("Unsupported user role");
    }

    private AppointmentResponse mapToResponse(Appointment appointment) {
        return AppointmentResponse.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(appointment.getPatientSystemId())
                .doctorSystemId(appointment.getDoctorSystemId())
                .facilityId(appointment.getFacilityId())
                .scheduledAt(appointment.getScheduledAt())
                .durationMinutes(appointment.getDurationMinutes())
                .type(appointment.getType())
                .status(appointment.getStatus())
                .reasonForVisit(appointment.getReasonForVisit())
                .videoConsultationLink(appointment.getVideoConsultationLink())
                .reminderSent(appointment.getReminderSent())
                .notes(appointment.getNotes())
                .createdAt(appointment.getCreatedAt())
                .updatedAt(appointment.getUpdatedAt())
                .build();
    }

    private boolean isValidType(String type) {
        return type != null && (type.equals("VIDEO") || type.equals("IN_PERSON") || type.equals("PHONE"));
    }

    private boolean isValidStatus(String status) {
        return status != null && (status.equals("SCHEDULED") || status.equals("COMPLETED") ||
                status.equals("CANCELLED") || status.equals("NO_SHOW") || status.equals("RESCHEDULED"));
    }
}
