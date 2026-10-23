package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.DoctorPatientResponse;
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
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorPatientService {

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentService appointmentService;

    public List<DoctorPatientResponse> getDoctorPatients(String doctorSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorSystemId(doctorSystemId);

        Set<String> patientSystemIds = doctorAppointments.stream()
                .map(Appointment::getPatientSystemId)
                .collect(Collectors.toSet());

        List<Patient> patients = patientSystemIds.stream()
                .map(systemId -> patientRepository.findBySystemId(systemId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return patients.stream()
                .map(patient -> buildDoctorPatientResponse(patient, doctorAppointments))
                .collect(Collectors.toList());
    }

    public List<DoctorPatientResponse> getDoctorPatientsByStatus(String doctorSystemId, String status) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorSystemId(doctorSystemId);
        List<Appointment> filteredAppointments = doctorAppointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(status))
                .collect(Collectors.toList());

        Set<String> patientSystemIds = filteredAppointments.stream()
                .map(Appointment::getPatientSystemId)
                .collect(Collectors.toSet());

        List<Patient> patients = patientSystemIds.stream()
                .map(systemId -> patientRepository.findBySystemId(systemId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return patients.stream()
                .map(patient -> buildDoctorPatientResponse(patient, doctorAppointments))
                .collect(Collectors.toList());
    }

    public List<DoctorPatientResponse> getDoctorScheduledPatients(String doctorSystemId) {
        return getDoctorPatientsByStatus(doctorSystemId, "SCHEDULED");
    }

    public List<DoctorPatientResponse> getDoctorCompletedPatients(String doctorSystemId) {
        return getDoctorPatientsByStatus(doctorSystemId, "COMPLETED");
    }

    public List<DoctorPatientResponse> getDoctorCancelledPatients(String doctorSystemId) {
        return getDoctorPatientsByStatus(doctorSystemId, "CANCELLED");
    }

    public List<DoctorPatientResponse> getDoctorNoShowPatients(String doctorSystemId) {
        return getDoctorPatientsByStatus(doctorSystemId, "NO_SHOW");
    }

    public List<DoctorPatientResponse> getDoctorRescheduledPatients(String doctorSystemId) {
        return getDoctorPatientsByStatus(doctorSystemId, "RESCHEDULED");
    }

    public DoctorPatientResponse getDoctorPatientDetail(String doctorSystemId, String patientSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        Patient patient = patientRepository.findBySystemId(patientSystemId)
                .orElseThrow(() -> new RuntimeException("Patient not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorSystemId(doctorSystemId);
        List<Appointment> patientWithDoctorAppointments = doctorAppointments.stream()
                .filter(a -> a.getPatientSystemId().equals(patientSystemId))
                .collect(Collectors.toList());

        if (patientWithDoctorAppointments.isEmpty()) {
            throw new RuntimeException("No appointments found between this doctor and patient");
        }

        return buildDoctorPatientResponse(patient, doctorAppointments);
    }

    public List<DoctorPatientResponse> searchDoctorPatients(String doctorSystemId, String searchTerm) {
        List<DoctorPatientResponse> allPatients = getDoctorPatients(doctorSystemId);

        String lowerSearchTerm = searchTerm.toLowerCase();

        return allPatients.stream()
                .filter(p -> p.getFirstName().toLowerCase().contains(lowerSearchTerm) ||
                             p.getLastName().toLowerCase().contains(lowerSearchTerm) ||
                             p.getEmail().toLowerCase().contains(lowerSearchTerm) ||
                             p.getPhone().contains(searchTerm))
                .collect(Collectors.toList());
    }

    private DoctorPatientResponse buildDoctorPatientResponse(Patient patient, List<Appointment> doctorAppointments) {
        User user = userRepository.findBySystemId(patient.getPatientSystemId())
                .orElse(null);

        List<Appointment> patientAppointments = doctorAppointments.stream()
                .filter(a -> a.getPatientSystemId().equals(patient.getPatientSystemId()))
                .collect(Collectors.toList());

        long scheduledCount = patientAppointments.stream()
                .filter(a -> "SCHEDULED".equals(a.getStatus()))
                .count();

        long completedCount = patientAppointments.stream()
                .filter(a -> "COMPLETED".equals(a.getStatus()))
                .count();

        long cancelledCount = patientAppointments.stream()
                .filter(a -> "CANCELLED".equals(a.getStatus()))
                .count();

        long noShowCount = patientAppointments.stream()
                .filter(a -> "NO_SHOW".equals(a.getStatus()))
                .count();

        long rescheduledCount = patientAppointments.stream()
                .filter(a -> "RESCHEDULED".equals(a.getStatus()))
                .count();

        LocalDateTime lastAppointmentDate = patientAppointments.stream()
                .map(Appointment::getScheduledAt)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<AppointmentResponse> recentAppointments = patientAppointments.stream()
                .sorted(Comparator.comparing(Appointment::getScheduledAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(this::mapAppointmentToResponse)
                .collect(Collectors.toList());

        return DoctorPatientResponse.builder()
                .patientSystemId(patient.getPatientSystemId())
                .userSystemId(user != null ? user.getSystemId() : null)
                .firstName(user != null ? user.getFirstName() : "Unknown")
                .lastName(user != null ? user.getLastName() : "Unknown")
                .email(user != null ? user.getEmail() : null)
                .phone(user != null ? user.getPhone() : null)
                .dateOfBirth(patient.getDateOfBirth())
                .gender(patient.getGender())
                .bloodType(patient.getBloodType())
                .allergies(patient.getAllergies())
                .chronicConditions(patient.getChronicConditions())
                .healthStatus(patient.getHealthStatus())
                .totalAppointments(patientAppointments.size())
                .lastAppointmentDate(lastAppointmentDate)
                .upcomingAppointmentCount((int) scheduledCount)
                .completedAppointmentCount((int) completedCount)
                .cancelledAppointmentCount((int) cancelledCount)
                .noShowCount((int) noShowCount)
                .rescheduledCount((int) rescheduledCount)
                .recentAppointments(recentAppointments)
                .patientCreatedAt(patient.getCreatedAt())
                .build();
    }

    private AppointmentResponse mapAppointmentToResponse(Appointment appointment) {
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
}
