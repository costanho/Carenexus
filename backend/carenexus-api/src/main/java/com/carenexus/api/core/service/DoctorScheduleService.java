package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.core.dto.response.DoctorDashboardResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.model.*;
import com.carenexus.api.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorScheduleService {

    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;

    public List<ScheduleEntryResponse> getDoctorSchedule(String doctorSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> appointments = appointmentRepository.findByDoctorSystemId(doctorSystemId);
        List<Consultation> consultations = consultationRepository.findByDoctorSystemId(doctorSystemId);

        Map<String, String> patientNameMap = new HashMap<>();
        for (Appointment appointment : appointments) {
            User user = userRepository.findBySystemId(appointment.getPatientSystemId()).orElse(null);
            if (user != null && !patientNameMap.containsKey(appointment.getPatientSystemId())) {
                patientNameMap.put(appointment.getPatientSystemId(), user.getFirstName() + " " + user.getLastName());
            }
        }
        for (Consultation consultation : consultations) {
            User user = userRepository.findBySystemId(consultation.getPatientSystemId()).orElse(null);
            if (user != null && !patientNameMap.containsKey(consultation.getPatientSystemId())) {
                patientNameMap.put(consultation.getPatientSystemId(), user.getFirstName() + " " + user.getLastName());
            }
        }

        List<ScheduleEntryResponse> schedule = new ArrayList<>();

        for (Appointment appointment : appointments) {
            ScheduleEntryResponse entry = ScheduleEntryResponse.builder()
                    .entryId("APT_" + appointment.getAppointmentId())
                    .type("APPOINTMENT")
                    .appointmentId(appointment.getAppointmentId())
                    .patientSystemId(appointment.getPatientSystemId())
                    .patientName(patientNameMap.getOrDefault(appointment.getPatientSystemId(), "Unknown"))
                    .doctorSystemId(appointment.getDoctorSystemId())
                    .scheduledAt(appointment.getScheduledAt())
                    .appointmentType(appointment.getType())
                    .appointmentStatus(appointment.getStatus())
                    .durationMinutes(appointment.getDurationMinutes())
                    .reasonForVisit(appointment.getReasonForVisit())
                    .videoConsultationLink(appointment.getVideoConsultationLink())
                    .reminderSent(appointment.getReminderSent())
                    .notes(appointment.getNotes())
                    .createdAt(appointment.getCreatedAt())
                    .updatedAt(appointment.getUpdatedAt())
                    .build();
            schedule.add(entry);
        }

        for (Consultation consultation : consultations) {
            Appointment appointment = appointmentRepository.findById(consultation.getAppointmentId())
                    .orElse(null);
            LocalDateTime scheduledAt = appointment != null ? appointment.getScheduledAt() : null;

            ScheduleEntryResponse entry = ScheduleEntryResponse.builder()
                    .entryId("CON_" + consultation.getConsultationId())
                    .type("CONSULTATION")
                    .consultationId(consultation.getConsultationId())
                    .appointmentId(consultation.getAppointmentId())
                    .patientSystemId(consultation.getPatientSystemId())
                    .patientName(patientNameMap.getOrDefault(consultation.getPatientSystemId(), "Unknown"))
                    .doctorSystemId(consultation.getDoctorSystemId())
                    .scheduledAt(scheduledAt)
                    .chiefComplaint(consultation.getChiefComplaint())
                    .diagnosis(consultation.getDiagnosis())
                    .treatmentPlan(consultation.getTreatmentPlan())
                    .followUpRequired(consultation.getFollowUpRequired())
                    .followUpDate(consultation.getFollowUpDate())
                    .consultationStatus(consultation.getStatus())
                    .startedAt(consultation.getStartedAt())
                    .completedAt(consultation.getCompletedAt())
                    .durationMinutes(consultation.getDurationMinutes())
                    .createdAt(consultation.getCreatedAt())
                    .build();
            schedule.add(entry);
        }

        schedule.sort(Comparator.comparing(ScheduleEntryResponse::getScheduledAt, Comparator.nullsLast(Comparator.reverseOrder())));

        return schedule;
    }

    public List<ScheduleEntryResponse> getDoctorScheduleByDateRange(String doctorSystemId, LocalDate startDate, LocalDate endDate) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null &&
                        !entry.getScheduledAt().isBefore(startDateTime) &&
                        !entry.getScheduledAt().isAfter(endDateTime))
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorTodaySchedule(String doctorSystemId) {
        LocalDate today = LocalDate.now();
        return getDoctorScheduleByDateRange(doctorSystemId, today, today);
    }

    public List<ScheduleEntryResponse> getDoctorUpcomingSchedule(String doctorSystemId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);
        LocalDateTime now = LocalDateTime.now();

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null && entry.getScheduledAt().isAfter(now))
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorPastSchedule(String doctorSystemId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);
        LocalDateTime now = LocalDateTime.now();

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null && entry.getScheduledAt().isBefore(now))
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorScheduleByStatus(String doctorSystemId, String status) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);

        return allSchedule.stream()
                .filter(entry -> {
                    if ("APPOINTMENT".equals(entry.getType())) {
                        return status.equals(entry.getAppointmentStatus());
                    } else {
                        return status.equals(entry.getConsultationStatus());
                    }
                })
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorScheduleByPatient(String doctorSystemId, String patientSystemId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);

        return allSchedule.stream()
                .filter(entry -> patientSystemId.equals(entry.getPatientSystemId()))
                .collect(Collectors.toList());
    }

    public DoctorDashboardResponse getDoctorDashboard(String systemId) {
        Doctor doctor = doctorRepository.findBySystemId(systemId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Appointment> allAppointments = appointmentRepository.findByDoctorSystemId(systemId);
        List<Consultation> allConsultations = consultationRepository.findByDoctorSystemId(systemId);
        List<Patient> allPatients = patientRepository.findAll();

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();

        List<Appointment> upcomingAppointments = allAppointments.stream()
                .filter(a -> a.getScheduledAt().isAfter(now))
                .collect(Collectors.toList());

        List<ScheduleEntryResponse> todaySchedule = getDoctorTodaySchedule(systemId);
        List<ScheduleEntryResponse> upcomingSchedule = getDoctorUpcomingSchedule(systemId);

        long completedConsultations = allConsultations.stream()
                .filter(c -> "COMPLETED".equals(c.getStatus()))
                .count();

        long pendingConsultations = allConsultations.stream()
                .filter(c -> "DRAFT".equals(c.getStatus()) || "IN_PROGRESS".equals(c.getStatus()))
                .count();

        return DoctorDashboardResponse.builder()
                .doctorSystemId(doctor.getSystemId())
                .userSystemId(user.getSystemId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .specialization(doctor.getSpecialization())
                .licenseNo(doctor.getLicenseNo())
                .bio(doctor.getBio())
                .isActive(doctor.getIsActive())
                .totalPatients(allPatients.size())
                .totalAppointments(allAppointments.size())
                .upcomingAppointments(upcomingAppointments.size())
                .completedConsultations((int) completedConsultations)
                .pendingConsultations((int) pendingConsultations)
                .todaySchedule(todaySchedule)
                .upcomingSchedule(upcomingSchedule)
                .build();
    }
}
