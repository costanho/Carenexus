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

    public List<ScheduleEntryResponse> getDoctorSchedule(Integer doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        List<Appointment> appointments = appointmentRepository.findByDoctorId(doctorId);
        List<Consultation> consultations = consultationRepository.findByDoctorId(doctorId);

        Map<Integer, Patient> patientMap = buildPatientMap();
        Map<Integer, String> patientNameMap = patientMap.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey(),
                        e -> {
                            User user = userRepository.findById(e.getValue().getUserId()).orElse(null);
                            return user != null ? user.getFirstName() + " " + user.getLastName() : "Unknown";
                        }
                ));

        List<ScheduleEntryResponse> schedule = new ArrayList<>();

        for (Appointment appointment : appointments) {
            ScheduleEntryResponse entry = ScheduleEntryResponse.builder()
                    .entryId("APT_" + appointment.getAppointmentId())
                    .type("APPOINTMENT")
                    .appointmentId(appointment.getAppointmentId())
                    .patientId(appointment.getPatientId())
                    .patientName(patientNameMap.getOrDefault(appointment.getPatientId(), "Unknown"))
                    .doctorId(appointment.getDoctorId())
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
                    .patientId(consultation.getPatientId())
                    .patientName(patientNameMap.getOrDefault(consultation.getPatientId(), "Unknown"))
                    .doctorId(consultation.getDoctorId())
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

    public List<ScheduleEntryResponse> getDoctorScheduleByDateRange(Integer doctorId, LocalDate startDate, LocalDate endDate) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorId);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(23, 59, 59);

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null &&
                        !entry.getScheduledAt().isBefore(startDateTime) &&
                        !entry.getScheduledAt().isAfter(endDateTime))
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorTodaySchedule(Integer doctorId) {
        LocalDate today = LocalDate.now();
        return getDoctorScheduleByDateRange(doctorId, today, today);
    }

    public List<ScheduleEntryResponse> getDoctorUpcomingSchedule(Integer doctorId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorId);
        LocalDateTime now = LocalDateTime.now();

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null && entry.getScheduledAt().isAfter(now))
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorPastSchedule(Integer doctorId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorId);
        LocalDateTime now = LocalDateTime.now();

        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null && entry.getScheduledAt().isBefore(now))
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorScheduleByStatus(Integer doctorId, String status) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorId);

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

    public List<ScheduleEntryResponse> getDoctorScheduleByPatient(Integer doctorId, Integer patientId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorId);

        return allSchedule.stream()
                .filter(entry -> patientId.equals(entry.getPatientId()))
                .collect(Collectors.toList());
    }

    public DoctorDashboardResponse getDoctorDashboard(Integer userId) {
        Doctor doctor = doctorRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException("Doctor profile not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Appointment> allAppointments = appointmentRepository.findByDoctorId(doctor.getDoctorId());
        List<Consultation> allConsultations = consultationRepository.findByDoctorId(doctor.getDoctorId());
        List<Patient> allPatients = patientRepository.findAll();

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();

        List<Appointment> upcomingAppointments = allAppointments.stream()
                .filter(a -> a.getScheduledAt().isAfter(now))
                .collect(Collectors.toList());

        List<ScheduleEntryResponse> todaySchedule = getDoctorTodaySchedule(doctor.getDoctorId());
        List<ScheduleEntryResponse> upcomingSchedule = getDoctorUpcomingSchedule(doctor.getDoctorId());

        long completedConsultations = allConsultations.stream()
                .filter(c -> "COMPLETED".equals(c.getStatus()))
                .count();

        long pendingConsultations = allConsultations.stream()
                .filter(c -> "DRAFT".equals(c.getStatus()) || "IN_PROGRESS".equals(c.getStatus()))
                .count();

        return DoctorDashboardResponse.builder()
                .doctorId(doctor.getDoctorId())
                .userId(user.getUserId())
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

    private Map<Integer, Patient> buildPatientMap() {
        List<Patient> patients = patientRepository.findAll();
        return patients.stream()
                .collect(Collectors.toMap(Patient::getPatientId, p -> p));
    }
}
