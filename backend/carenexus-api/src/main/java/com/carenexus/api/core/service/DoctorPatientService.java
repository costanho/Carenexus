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

    public List<DoctorPatientResponse> getDoctorPatients(Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorId(doctorId);

        Set<Integer> patientIds = doctorAppointments.stream()
                .map(Appointment::getPatientId)
                .collect(Collectors.toSet());

        List<Patient> patients = patientIds.stream()
                .map(patientId -> patientRepository.findById(patientId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return patients.stream()
                .map(patient -> buildDoctorPatientResponse(patient, doctorAppointments))
                .collect(Collectors.toList());
    }

    public List<DoctorPatientResponse> getDoctorPatientsByStatus(Integer doctorId, String status) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorId(doctorId);
        List<Appointment> filteredAppointments = doctorAppointments.stream()
                .filter(a -> a.getStatus().equalsIgnoreCase(status))
                .collect(Collectors.toList());

        Set<Integer> patientIds = filteredAppointments.stream()
                .map(Appointment::getPatientId)
                .collect(Collectors.toSet());

        List<Patient> patients = patientIds.stream()
                .map(patientId -> patientRepository.findById(patientId).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return patients.stream()
                .map(patient -> buildDoctorPatientResponse(patient, doctorAppointments))
                .collect(Collectors.toList());
    }

    public List<DoctorPatientResponse> getDoctorScheduledPatients(Integer doctorId) {
        return getDoctorPatientsByStatus(doctorId, "SCHEDULED");
    }

    public List<DoctorPatientResponse> getDoctorCompletedPatients(Integer doctorId) {
        return getDoctorPatientsByStatus(doctorId, "COMPLETED");
    }

    public List<DoctorPatientResponse> getDoctorCancelledPatients(Integer doctorId) {
        return getDoctorPatientsByStatus(doctorId, "CANCELLED");
    }

    public List<DoctorPatientResponse> getDoctorNoShowPatients(Integer doctorId) {
        return getDoctorPatientsByStatus(doctorId, "NO_SHOW");
    }

    public List<DoctorPatientResponse> getDoctorRescheduledPatients(Integer doctorId) {
        return getDoctorPatientsByStatus(doctorId, "RESCHEDULED");
    }

    public DoctorPatientResponse getDoctorPatientDetail(Integer doctorId, Integer patientId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new RuntimeException("Patient not found"));

        List<Appointment> doctorAppointments = appointmentRepository.findByDoctorId(doctorId);
        List<Appointment> patientWithDoctorAppointments = doctorAppointments.stream()
                .filter(a -> a.getPatientId().equals(patientId))
                .collect(Collectors.toList());

        if (patientWithDoctorAppointments.isEmpty()) {
            throw new RuntimeException("No appointments found between this doctor and patient");
        }

        return buildDoctorPatientResponse(patient, doctorAppointments);
    }

    public List<DoctorPatientResponse> searchDoctorPatients(Integer doctorId, String searchTerm) {
        List<DoctorPatientResponse> allPatients = getDoctorPatients(doctorId);

        String lowerSearchTerm = searchTerm.toLowerCase();

        return allPatients.stream()
                .filter(p -> p.getFirstName().toLowerCase().contains(lowerSearchTerm) ||
                             p.getLastName().toLowerCase().contains(lowerSearchTerm) ||
                             p.getEmail().toLowerCase().contains(lowerSearchTerm) ||
                             p.getPhone().contains(searchTerm))
                .collect(Collectors.toList());
    }

    private DoctorPatientResponse buildDoctorPatientResponse(Patient patient, List<Appointment> doctorAppointments) {
        User user = userRepository.findById(patient.getUserId())
                .orElse(null);

        List<Appointment> patientAppointments = doctorAppointments.stream()
                .filter(a -> a.getPatientId().equals(patient.getPatientId()))
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
                .patientId(patient.getPatientId())
                .userId(user != null ? user.getUserId() : null)
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
                .patientId(appointment.getPatientId())
                .doctorId(appointment.getDoctorId())
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
