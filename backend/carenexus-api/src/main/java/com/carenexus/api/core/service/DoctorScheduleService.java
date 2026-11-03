package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.core.dto.response.DoctorDashboardResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.model.*;
import com.carenexus.api.core.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
    private final UserRepository userRepository;
    private final RelationshipService relationshipService;

    /** Same clinic zone as AppointmentService: appointment times are local wall-clock times of this zone. */
    @Value("${carenexus.scheduling.time-zone:Africa/Harare}")
    private String schedulingTimeZone;

    private ZoneId zone() {
        try {
            return ZoneId.of(schedulingTimeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private static boolean isActiveAppointmentStatus(String status) {
        return "SCHEDULED".equals(status) || "RESCHEDULED".equals(status);
    }

    public List<ScheduleEntryResponse> getDoctorSchedule(String doctorSystemId) {
        if (doctorRepository.findBySystemId(doctorSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }

        List<Appointment> appointments = appointmentRepository.findByDoctorSystemId(doctorSystemId);
        List<Consultation> consultations = consultationRepository.findByDoctorSystemId(doctorSystemId);

        // One user lookup per distinct patient (not per row).
        Set<String> patientIds = new LinkedHashSet<>();
        appointments.forEach(a -> patientIds.add(a.getPatientSystemId()));
        consultations.forEach(c -> patientIds.add(c.getPatientSystemId()));
        Map<String, String> patientNameMap = new HashMap<>();
        for (String patientId : patientIds) {
            if (patientId == null) continue;
            userRepository.findBySystemId(patientId)
                    .ifPresent(user -> patientNameMap.put(patientId, user.getFirstName() + " " + user.getLastName()));
        }
        Map<Integer, Appointment> appointmentsById = new HashMap<>();
        appointments.forEach(a -> appointmentsById.put(a.getAppointmentId(), a));

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
            Integer linkedId = consultation.getAppointmentId();
            Appointment appointment = linkedId == null ? null
                    : appointmentsById.containsKey(linkedId) ? appointmentsById.get(linkedId)
                    : appointmentRepository.findById(linkedId).orElse(null);
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
        LocalDate today = LocalDate.now(zone());
        return getDoctorScheduleByDateRange(doctorSystemId, today, today);
    }

    public List<ScheduleEntryResponse> getDoctorUpcomingSchedule(String doctorSystemId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);
        LocalDateTime now = LocalDateTime.now(zone());

        // The full schedule is newest first; "upcoming" must be the NEXT entries, soonest first, and must not
        // include cancelled / completed / no-show appointments.
        return allSchedule.stream()
                .filter(entry -> entry.getScheduledAt() != null && entry.getScheduledAt().isAfter(now))
                .filter(entry -> !"APPOINTMENT".equals(entry.getType())
                        || isActiveAppointmentStatus(entry.getAppointmentStatus()))
                .sorted(Comparator.comparing(ScheduleEntryResponse::getScheduledAt))
                .limit(10)
                .collect(Collectors.toList());
    }

    public List<ScheduleEntryResponse> getDoctorPastSchedule(String doctorSystemId) {
        List<ScheduleEntryResponse> allSchedule = getDoctorSchedule(doctorSystemId);
        LocalDateTime now = LocalDateTime.now(zone());

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
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));

        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<Appointment> allAppointments = appointmentRepository.findByDoctorSystemId(systemId);
        List<Consultation> allConsultations = consultationRepository.findByDoctorSystemId(systemId);
        // This doctor's patients (care team, non-cancelled appointments, consultations), not every patient in the DB.
        int totalPatients = relationshipService.getPatientsForDoctor(systemId).size();

        LocalDateTime now = LocalDateTime.now(zone());

        List<Appointment> upcomingAppointments = allAppointments.stream()
                .filter(a -> a.getScheduledAt() != null && a.getScheduledAt().isAfter(now))
                .filter(a -> isActiveAppointmentStatus(a.getStatus()))
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
                .totalPatients(totalPatients)
                .totalAppointments(allAppointments.size())
                .upcomingAppointments(upcomingAppointments.size())
                .completedConsultations((int) completedConsultations)
                .pendingConsultations((int) pendingConsultations)
                .todaySchedule(todaySchedule)
                .upcomingSchedule(upcomingSchedule)
                .build();
    }
}
