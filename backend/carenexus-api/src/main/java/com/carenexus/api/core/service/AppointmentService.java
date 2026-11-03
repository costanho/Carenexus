package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Appointment booking and lifecycle.
 *
 * Status model (values match the DB CHECK constraint on appointments.status):
 *   SCHEDULED   -> COMPLETED | CANCELLED | NO_SHOW, or RESCHEDULED by moving it to a new time
 *   RESCHEDULED -> COMPLETED | CANCELLED | NO_SHOW, or RESCHEDULED again (new time)
 *   COMPLETED, CANCELLED, NO_SHOW are terminal and can never be reopened.
 * SCHEDULED and RESCHEDULED are the "active" states: they occupy the doctor's calendar.
 *
 * All times are local wall-clock times of the clinic time zone (carenexus.scheduling.time-zone,
 * default Africa/Harare), which is what the clients send ("YYYY-MM-DDTHH:mm:00", no offset).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AppointmentService {

    public static final String SCHEDULED = "SCHEDULED";
    public static final String RESCHEDULED = "RESCHEDULED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String NO_SHOW = "NO_SHOW";

    /** Statuses that occupy a slot in the doctor's and the patient's calendar. */
    public static final Set<String> ACTIVE_STATUSES = Set.of(SCHEDULED, RESCHEDULED);
    private static final Set<String> TERMINAL_STATUSES = Set.of(COMPLETED, CANCELLED, NO_SHOW);
    private static final Set<String> VALID_STATUSES = Set.of(SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED, NO_SHOW);
    private static final Set<String> VALID_TYPES = Set.of("VIDEO", "IN_PERSON", "PHONE");

    private static final int MIN_DURATION_MINUTES = 5;
    private static final int MAX_DURATION_MINUTES = 240;
    private static final int SLOT_GRANULARITY_MINUTES = 5;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_LINK_LENGTH = 500;
    private static final String SLOT_INDEX_NAME = "ux_appointments_doctor_active_slot";
    private static final String PATIENT_SLOT_INDEX_NAME = "ux_appointments_patient_active_slot";
    private static final String RESOURCE_TYPE = "APPOINTMENT";

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    @Value("${carenexus.video.base-url:https://meet.jit.si}")
    private String videoBaseUrl;

    @Value("${carenexus.scheduling.time-zone:Africa/Harare}")
    private String schedulingTimeZone;

    /** First bookable hour (inclusive) for patients and caregivers. */
    @Value("${carenexus.scheduling.open-hour:8}")
    private int openHour;

    /** Appointments booked by patients and caregivers must END by this hour. */
    @Value("${carenexus.scheduling.close-hour:18}")
    private int closeHour;

    @Value("${carenexus.scheduling.allow-weekends:true}")
    private boolean allowWeekends;

    @Value("${carenexus.scheduling.max-advance-days:365}")
    private int maxAdvanceDays;

    // ==================== BOOKING ====================

    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        if (request == null) {
            throw new RuntimeException("Appointment details are required");
        }
        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();

        String patientSystemId = trimToNull(request.getPatientSystemId());
        String doctorSystemId = trimToNull(request.getDoctorSystemId());

        switch (role) {
            case AuthorizationService.PATIENT -> {
                if (patientSystemId == null) {
                    patientSystemId = callerId;
                } else if (!patientSystemId.equals(callerId)) {
                    throw new AuthorizationException("You can only book appointments for yourself", RESOURCE_TYPE, null);
                }
            }
            case AuthorizationService.CAREGIVER -> {
                if (patientSystemId == null) {
                    throw new RuntimeException("Patient ID is required");
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_APPOINTMENTS);
            }
            case AuthorizationService.DOCTOR -> {
                if (patientSystemId == null) {
                    throw new RuntimeException("Patient ID is required");
                }
                if (doctorSystemId == null) {
                    doctorSystemId = callerId;
                } else if (!doctorSystemId.equals(callerId)) {
                    throw new AuthorizationException("Doctors can only book appointments in their own schedule", RESOURCE_TYPE, null);
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_APPOINTMENTS);
            }
            case AuthorizationService.ADMIN -> {
                if (patientSystemId == null) {
                    throw new RuntimeException("Patient ID is required");
                }
            }
            default -> throw new AuthorizationException("Your role is not allowed to book appointments", RESOURCE_TYPE, null);
        }

        if (doctorSystemId == null) {
            throw new RuntimeException("Doctor ID is required");
        }
        if (patientSystemId.equals(doctorSystemId)) {
            throw new RuntimeException("You cannot book an appointment with yourself");
        }
        if (patientRepository.findBySystemId(patientSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Patient not found");
        }
        requireBookableDoctor(doctorSystemId);

        String type = normalizeType(request.getType(), "IN_PERSON");
        int duration = validateDuration(request.getDurationMinutes() != null ? request.getDurationMinutes() : 30);
        LocalDateTime scheduledAt = validateStartTime(request.getScheduledAt(), duration, role);

        Integer facilityId = request.getFacilityId();
        if (facilityId != null && appointmentRepository.countFacilitiesById(facilityId) == 0) {
            throw new ResourceNotFoundException("Facility not found");
        }

        lockAndCheckAvailability(doctorSystemId, patientSystemId, scheduledAt, duration, -1);

        Appointment appointment = Appointment.builder()
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .facilityId(facilityId)
                .scheduledAt(scheduledAt)
                .durationMinutes(duration)
                .type(type)
                .status(SCHEDULED)
                .reasonForVisit(limitText(request.getReasonForVisit(), "Reason for visit"))
                .videoConsultationLink("VIDEO".equals(type) ? resolveVideoLink(request.getVideoConsultationLink(), null, role) : null)
                .reminderSent(false)
                .notes(limitText(request.getNotes(), "Notes"))
                .build();

        Appointment savedAppointment = saveSlot(appointment);
        auditService.logClinicalAccess("CREATE", RESOURCE_TYPE, savedAppointment.getAppointmentId(), patientSystemId);
        return mapToResponse(savedAppointment);
    }

    // ==================== READS ====================

    public AppointmentResponse getAppointmentById(Integer appointmentId) {
        Appointment appointment = findAppointment(appointmentId);
        requireCanView(appointment);
        auditService.logClinicalAccess("READ", RESOURCE_TYPE, appointmentId, appointment.getPatientSystemId());
        return mapToResponse(appointment);
    }

    /**
     * All appointments of a patient. Caller must be the patient, a related doctor, a caregiver allowed to view
     * appointments, or an admin.
     */
    public List<AppointmentResponse> getAppointmentsForPatientAsCaller(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
        List<AppointmentResponse> result = getAppointmentsByPatientSystemId(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE_TYPE, null, patientSystemId);
        return result;
    }

    /**
     * Upcoming (active, future) appointments of a patient, with the same access rule as
     * {@link #getAppointmentsForPatientAsCaller}.
     */
    public List<AppointmentResponse> getUpcomingAppointmentsForPatientAsCaller(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
        List<AppointmentResponse> result = getUpcomingAppointments(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE_TYPE, null, patientSystemId);
        return result;
    }

    /** Internal (no access check): used by services that already authorized the caller. */
    public List<AppointmentResponse> getAppointmentsByPatientSystemId(String patientSystemId) {
        return appointmentRepository.findByPatientSystemId(patientSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /** Internal (no access check): used by services that already authorized the caller. */
    public List<AppointmentResponse> getAppointmentsForPatients(List<String> patientSystemIds) {
        if (patientSystemIds == null || patientSystemIds.isEmpty()) return List.of();
        return appointmentRepository.findByPatientSystemIdIn(patientSystemIds).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /** Internal (no access check): callers must have checked that the caller is this doctor or an admin. */
    public List<AppointmentResponse> getAppointmentsByDoctorSystemId(String doctorSystemId) {
        return appointmentRepository.findByDoctorSystemId(doctorSystemId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /** Internal (no access check): active appointments of the patient that have not started yet, soonest first. */
    public List<AppointmentResponse> getUpcomingAppointments(String patientSystemId) {
        LocalDateTime now = now();
        return appointmentRepository.findByPatientSystemIdAndStatusIn(patientSystemId, ACTIVE_STATUSES).stream()
                .filter(a -> a.getScheduledAt().isAfter(now))
                .sorted(Comparator.comparing(Appointment::getScheduledAt))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /** Internal (no access check): callers must have checked that the caller is this doctor or an admin. */
    public List<AppointmentResponse> getDoctorSchedule(String doctorSystemId, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new RuntimeException("Invalid date range");
        }
        return appointmentRepository.findByDoctorSystemIdAndScheduledAtBetween(doctorSystemId, start, end).stream()
                .sorted(Comparator.comparing(Appointment::getScheduledAt))
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Busy times of a doctor on one day, for slot pickers. Returns only start/end/duration of active
     * appointments, never who the patient is or why they come. Any authenticated user may call it.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDoctorBusySlots(String doctorSystemId, LocalDate date) {
        if (date == null) {
            throw new RuntimeException("Date is required");
        }
        if (doctorRepository.findBySystemId(doctorSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay().minusNanos(1);
        return appointmentRepository
                .findByDoctorSystemIdAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
                        doctorSystemId, ACTIVE_STATUSES, dayStart, dayEnd)
                .stream()
                .map(a -> {
                    Map<String, Object> slot = new LinkedHashMap<>();
                    slot.put("start", a.getScheduledAt());
                    slot.put("end", a.getScheduledAt().plusMinutes(a.getDurationMinutes()));
                    slot.put("durationMinutes", a.getDurationMinutes());
                    return slot;
                })
                .collect(Collectors.toList());
    }

    /** Clinic booking rules, so clients can show the same hours the server enforces. */
    public Map<String, Object> getBookingRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("timeZone", zone().getId());
        rules.put("openHour", openHour);
        rules.put("closeHour", closeHour);
        rules.put("allowWeekends", allowWeekends);
        rules.put("slotGranularityMinutes", SLOT_GRANULARITY_MINUTES);
        rules.put("minDurationMinutes", MIN_DURATION_MINUTES);
        rules.put("maxDurationMinutes", MAX_DURATION_MINUTES);
        rules.put("maxAdvanceDays", maxAdvanceDays);
        return rules;
    }

    public List<AppointmentResponse> getAppointmentsByStatus(String status) {
        authorizationService.requireAdmin();
        String normalized = normalizeStatus(status);
        return appointmentRepository.findByStatus(normalized).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public List<AppointmentResponse> getUpcomingForUser(String userSystemId, String userRole) {
        String role = upper(userRole);
        LocalDateTime now = now();
        if (AuthorizationService.PATIENT.equals(role)) {
            return getUpcomingAppointments(userSystemId);
        } else if (AuthorizationService.DOCTOR.equals(role)) {
            return appointmentRepository.findByDoctorSystemIdAndStatusIn(userSystemId, ACTIVE_STATUSES)
                    .stream()
                    .filter(a -> a.getScheduledAt().isAfter(now))
                    .sorted(Comparator.comparing(Appointment::getScheduledAt))
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }
        throw unsupportedRole();
    }

    public List<AppointmentResponse> getCompletedForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, COMPLETED);
    }

    public List<AppointmentResponse> getCancelledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, CANCELLED);
    }

    public List<AppointmentResponse> getRescheduledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, RESCHEDULED);
    }

    public List<AppointmentResponse> getNoShowForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, NO_SHOW);
    }

    // ==================== CHANGES ====================

    /**
     * Change the status of an appointment.
     * - COMPLETED / NO_SHOW: only the appointment's doctor (or an admin), and only once the appointment has started.
     * - CANCELLED: see {@link #cancelAppointment(Integer)}.
     * - RESCHEDULED: only by moving the appointment to a new time ({@link #rescheduleAppointment}).
     * - SCHEDULED: an appointment can never be reopened.
     * Setting the status it already has is a no-op.
     */
    public AppointmentResponse updateAppointmentStatus(Integer appointmentId, String newStatus) {
        String status = normalizeStatus(newStatus);
        Appointment appointment = findAppointment(appointmentId);

        if (CANCELLED.equals(status)) {
            return cancel(appointment);
        }

        requireCanView(appointment);
        if (status.equals(appointment.getStatus())) {
            return mapToResponse(appointment);
        }
        requireNotTerminal(appointment);

        switch (status) {
            case COMPLETED, NO_SHOW -> {
                requireAppointmentDoctorOrAdmin(appointment,
                        "Only the appointment's doctor can mark it as " + describeStatus(status));
                if (appointment.getScheduledAt().isAfter(now())) {
                    throw new RuntimeException("An appointment that has not started yet cannot be marked as " + describeStatus(status));
                }
            }
            case RESCHEDULED -> throw new RuntimeException("To reschedule an appointment, choose a new date and time");
            default -> throw new ConflictException("A " + describeStatus(appointment.getStatus())
                    + " appointment cannot be set back to scheduled");
        }

        appointment.setStatus(status);
        Appointment updatedAppointment = appointmentRepository.save(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE_TYPE, appointmentId, appointment.getPatientSystemId());
        return mapToResponse(updatedAppointment);
    }

    /**
     * Move an active appointment to a new time (and optionally a new duration). Status becomes RESCHEDULED.
     */
    public AppointmentResponse rescheduleAppointment(Integer appointmentId, RescheduleAppointmentRequest request) {
        if (request == null || request.getScheduledAt() == null) {
            throw new RuntimeException("Choose a new date and time to reschedule this appointment");
        }
        CreateAppointmentRequest update = CreateAppointmentRequest.builder()
                .scheduledAt(request.getScheduledAt())
                .durationMinutes(request.getDurationMinutes())
                .build();
        return updateAppointment(appointmentId, update);
    }

    /**
     * Edit an active (SCHEDULED/RESCHEDULED) appointment: time, duration, type, reason, notes, video link.
     * The patient and doctor of an appointment cannot be changed. Moving it to a new start time sets the status
     * to RESCHEDULED and runs the same checks as booking (future time, clinic hours, doctor free, patient free).
     */
    public AppointmentResponse updateAppointment(Integer appointmentId, CreateAppointmentRequest request) {
        if (request == null) {
            throw new RuntimeException("Appointment details are required");
        }
        Appointment appointment = findAppointment(appointmentId);
        requireCanManage(appointment);
        requireNotTerminal(appointment);

        String role = authorizationService.currentRole();
        boolean patientSide = isPatientSide(role);
        LocalDateTime now = now();

        LocalDateTime currentStart = appointment.getScheduledAt();
        int currentDuration = appointment.getDurationMinutes() != null ? appointment.getDurationMinutes() : 30;

        int newDuration = request.getDurationMinutes() != null
                ? validateDuration(request.getDurationMinutes())
                : currentDuration;
        LocalDateTime requestedStart = request.getScheduledAt() != null
                ? request.getScheduledAt().truncatedTo(ChronoUnit.MINUTES)
                : currentStart;

        boolean startChanged = !requestedStart.equals(currentStart);
        boolean slotChanged = startChanged || newDuration != currentDuration;

        if (slotChanged) {
            if (patientSide && !currentStart.isAfter(now)) {
                throw new RuntimeException("This appointment has already started and can no longer be rescheduled");
            }
            LocalDateTime validatedStart = validateStartTime(requestedStart, newDuration, role);
            requireBookableDoctor(appointment.getDoctorSystemId());
            lockAndCheckAvailability(appointment.getDoctorSystemId(), appointment.getPatientSystemId(),
                    validatedStart, newDuration, appointment.getAppointmentId());
            appointment.setScheduledAt(validatedStart);
            appointment.setDurationMinutes(newDuration);
            if (startChanged) {
                appointment.setStatus(RESCHEDULED);
                appointment.setReminderSent(false);
            }
        }

        if (request.getType() != null) {
            String newType = normalizeType(request.getType(), null);
            if ("VIDEO".equals(newType)) {
                appointment.setVideoConsultationLink(resolveVideoLink(request.getVideoConsultationLink(),
                        "VIDEO".equals(appointment.getType()) ? appointment.getVideoConsultationLink() : null, role));
            } else {
                appointment.setVideoConsultationLink(null);
            }
            appointment.setType(newType);
        } else if (request.getVideoConsultationLink() != null && "VIDEO".equals(appointment.getType())) {
            appointment.setVideoConsultationLink(resolveVideoLink(request.getVideoConsultationLink(),
                    appointment.getVideoConsultationLink(), role));
        }

        if (request.getReasonForVisit() != null) {
            appointment.setReasonForVisit(limitText(request.getReasonForVisit(), "Reason for visit"));
        }

        if (request.getNotes() != null) {
            appointment.setNotes(limitText(request.getNotes(), "Notes"));
        }

        Appointment updatedAppointment = slotChanged ? saveSlot(appointment) : appointmentRepository.save(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE_TYPE, appointmentId, appointment.getPatientSystemId());
        return mapToResponse(updatedAppointment);
    }

    /**
     * Soft-cancel (status CANCELLED). Allowed for the patient, the appointment's doctor, a caregiver with
     * appointment-management access to the patient, and admins. Patients and caregivers can only cancel
     * appointments that have not started yet.
     */
    public void cancelAppointment(Integer appointmentId) {
        cancel(findAppointment(appointmentId));
    }

    // ==================== HELPERS: ACCESS ====================

    private AppointmentResponse cancel(Appointment appointment) {
        requireCanManage(appointment);
        if (CANCELLED.equals(appointment.getStatus())) {
            throw new ConflictException("This appointment is already cancelled");
        }
        requireNotTerminal(appointment);
        if (isPatientSide(authorizationService.currentRole()) && !appointment.getScheduledAt().isAfter(now())) {
            throw new RuntimeException("This appointment has already started and can no longer be cancelled");
        }
        appointment.setStatus(CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE_TYPE, appointment.getAppointmentId(), appointment.getPatientSystemId());
        return mapToResponse(saved);
    }

    /** Caller may read this appointment: its doctor, or anyone with appointment read access to the patient. */
    private void requireCanView(Appointment appointment) {
        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (AuthorizationService.ADMIN.equals(role)) {
            return;
        }
        if (AuthorizationService.DOCTOR.equals(role) && callerId.equals(appointment.getDoctorSystemId())) {
            return;
        }
        authorizationService.requirePatientAccess(appointment.getPatientSystemId(), PatientPermission.APPOINTMENTS,
                appointment.getAppointmentId());
    }

    /**
     * Caller may change or cancel this appointment: the patient, the appointment's doctor, a caregiver whose
     * dependent_access allows scheduling appointments, or an admin.
     */
    private void requireCanManage(Appointment appointment) {
        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        switch (role) {
            case AuthorizationService.ADMIN -> {
                return;
            }
            case AuthorizationService.PATIENT -> {
                if (callerId.equals(appointment.getPatientSystemId())) {
                    return;
                }
            }
            case AuthorizationService.DOCTOR -> {
                if (callerId.equals(appointment.getDoctorSystemId())) {
                    return;
                }
            }
            case AuthorizationService.CAREGIVER -> {
                authorizationService.requirePatientAccess(appointment.getPatientSystemId(),
                        PatientPermission.MANAGE_APPOINTMENTS, appointment.getAppointmentId());
                return;
            }
            default -> {
                // denied below
            }
        }
        denyAppointment(callerId, appointment, "Caller is not a participant of the appointment",
                "You are not allowed to change this appointment");
    }

    private void requireAppointmentDoctorOrAdmin(Appointment appointment, String message) {
        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (AuthorizationService.ADMIN.equals(role)) {
            return;
        }
        if (AuthorizationService.DOCTOR.equals(role) && callerId.equals(appointment.getDoctorSystemId())) {
            return;
        }
        denyAppointment(callerId, appointment, "Only the appointment's doctor may set this status", message);
    }

    private void denyAppointment(String callerId, Appointment appointment, String auditReason, String message) {
        try {
            auditService.logAccessDenied(callerId, RESOURCE_TYPE, appointment.getAppointmentId(),
                    appointment.getPatientSystemId(), null, auditReason);
        } catch (RuntimeException ignored) {
            // The audit write must never mask the denial.
        }
        throw new AuthorizationException(message, RESOURCE_TYPE, appointment.getAppointmentId());
    }

    private static boolean isPatientSide(String role) {
        return AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role);
    }

    // ==================== HELPERS: VALIDATION ====================

    private Appointment findAppointment(Integer appointmentId) {
        if (appointmentId == null) {
            throw new RuntimeException("Appointment ID is required");
        }
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
    }

    private void requireNotTerminal(Appointment appointment) {
        if (TERMINAL_STATUSES.contains(appointment.getStatus())) {
            throw new ConflictException("This appointment is already marked as " + describeStatus(appointment.getStatus())
                    + " and can no longer be changed");
        }
    }

    /** The doctor exists, has an active doctor profile (verified) and an active user account. */
    private Doctor requireBookableDoctor(String doctorSystemId) {
        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        boolean userActive = userRepository.findBySystemId(doctorSystemId)
                .map(User::getIsActive)
                .map(Boolean.TRUE::equals)
                .orElse(false);
        if (!Boolean.TRUE.equals(doctor.getIsActive()) || !userActive) {
            throw new RuntimeException("This doctor is not accepting appointments at the moment");
        }
        return doctor;
    }

    private int validateDuration(Integer durationMinutes) {
        if (durationMinutes == null
                || durationMinutes < MIN_DURATION_MINUTES
                || durationMinutes > MAX_DURATION_MINUTES
                || durationMinutes % SLOT_GRANULARITY_MINUTES != 0) {
            throw new RuntimeException("Appointment duration must be between " + MIN_DURATION_MINUTES + " and "
                    + MAX_DURATION_MINUTES + " minutes, in steps of " + SLOT_GRANULARITY_MINUTES + " minutes");
        }
        return durationMinutes;
    }

    /**
     * Start time is required, in the future, within the booking horizon and on a 5-minute boundary.
     * Patients and caregivers must also stay within clinic hours (doctors and admins may book outside them).
     */
    private LocalDateTime validateStartTime(LocalDateTime requested, int durationMinutes, String role) {
        if (requested == null) {
            throw new RuntimeException("Scheduled date/time is required");
        }
        LocalDateTime start = requested.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime now = now();
        if (!start.isAfter(now)) {
            throw new RuntimeException("Appointment cannot be scheduled in the past");
        }
        if (start.isAfter(now.plusDays(maxAdvanceDays))) {
            throw new RuntimeException("Appointments can be booked at most " + maxAdvanceDays + " days in advance");
        }
        if (start.getMinute() % SLOT_GRANULARITY_MINUTES != 0) {
            throw new RuntimeException("Appointments must start on a " + SLOT_GRANULARITY_MINUTES + "-minute boundary");
        }
        if (isPatientSide(role)) {
            if (!allowWeekends && (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                throw new RuntimeException("Appointments cannot be booked on weekends");
            }
            LocalDateTime open = start.toLocalDate().atTime(openHour, 0);
            LocalDateTime close = closeHour >= 24
                    ? start.toLocalDate().plusDays(1).atStartOfDay()
                    : start.toLocalDate().atTime(closeHour, 0);
            if (start.isBefore(open) || start.plusMinutes(durationMinutes).isAfter(close)) {
                throw new RuntimeException(String.format(
                        "Appointments can only be booked between %02d:00 and %02d:00", openHour, closeHour));
            }
        }
        return start;
    }

    /**
     * Serializes bookings per doctor and per patient (transaction-scoped advisory locks, released at commit),
     * then rejects the slot if it overlaps another active appointment of the doctor or of the patient.
     */
    private void lockAndCheckAvailability(String doctorSystemId, String patientSystemId,
                                          LocalDateTime start, int durationMinutes, Integer excludeAppointmentId) {
        // Fixed order (doctor, then patient) so two bookings can never wait on each other.
        appointmentRepository.lockSchedule("doctor:" + doctorSystemId);
        appointmentRepository.lockSchedule("patient:" + patientSystemId);

        LocalDateTime end = start.plusMinutes(durationMinutes);
        if (appointmentRepository.countOverlappingForDoctor(doctorSystemId, start, end, excludeAppointmentId) > 0) {
            throw new ConflictException("This time slot is no longer available. Please choose another time.");
        }
        if (appointmentRepository.countOverlappingForPatient(patientSystemId, start, end, excludeAppointmentId) > 0) {
            throw new ConflictException("The patient already has another appointment at this time");
        }
    }

    /** Save + flush so the DB slot index (V13) is checked here and turned into a readable 409. */
    private Appointment saveSlot(Appointment appointment) {
        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : null;
            if (detail != null && detail.contains(SLOT_INDEX_NAME)) {
                throw new ConflictException("This time slot is no longer available. Please choose another time.");
            }
            if (detail != null && detail.contains(PATIENT_SLOT_INDEX_NAME)) {
                throw new ConflictException("The patient already has another appointment at this time");
            }
            throw e;
        }
    }

    /**
     * Video link for a VIDEO appointment. Doctors and admins may supply their own https link; any link sent by a
     * patient or caregiver is ignored so a patient cannot make the doctor's "Join" button open an arbitrary URL.
     * Otherwise the existing link is kept, or a new unguessable room is generated.
     */
    private String resolveVideoLink(String requestedLink, String existingLink, String role) {
        String link = trimToNull(requestedLink);
        boolean mayProvide = AuthorizationService.DOCTOR.equals(role) || AuthorizationService.ADMIN.equals(role);
        if (link != null && mayProvide) {
            if (!link.toLowerCase(Locale.ROOT).startsWith("https://") || link.length() > MAX_LINK_LENGTH) {
                throw new RuntimeException("Video consultation link must be an https:// URL of at most "
                        + MAX_LINK_LENGTH + " characters");
            }
            return link;
        }
        if (existingLink != null && !existingLink.isBlank()) {
            return existingLink;
        }
        return generateVideoLink();
    }

    /** Single place to swap the video provider later (Daily, Twilio, Zoom, ...). */
    private String generateVideoLink() {
        String base = videoBaseUrl == null || videoBaseUrl.isBlank() ? "https://meet.jit.si" : videoBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/carenexus-" + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeType(String type, String defaultType) {
        String value = trimToNull(type);
        if (value == null) {
            if (defaultType != null) {
                return defaultType;
            }
            throw new RuntimeException("Invalid appointment type. Must be VIDEO, IN_PERSON, or PHONE");
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (!VALID_TYPES.contains(normalized)) {
            throw new RuntimeException("Invalid appointment type. Must be VIDEO, IN_PERSON, or PHONE");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String value = trimToNull(status);
        String normalized = value == null ? null : value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized == null || !VALID_STATUSES.contains(normalized)) {
            throw new RuntimeException("Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED");
        }
        return normalized;
    }

    private static String limitText(String value, String field) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw new RuntimeException(field + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    private static String describeStatus(String status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case CANCELLED -> "cancelled";
            case NO_SHOW -> "no-show";
            case RESCHEDULED -> "rescheduled";
            default -> "scheduled";
        };
    }

    private List<AppointmentResponse> getByStatusForUser(String userSystemId, String userRole, String status) {
        String role = upper(userRole);
        List<Appointment> appointments;
        if (AuthorizationService.PATIENT.equals(role)) {
            appointments = appointmentRepository.findByPatientSystemIdAndStatus(userSystemId, status);
        } else if (AuthorizationService.DOCTOR.equals(role)) {
            appointments = appointmentRepository.findByDoctorSystemIdAndStatus(userSystemId, status);
        } else {
            throw unsupportedRole();
        }
        return appointments.stream()
                .sorted(Comparator.comparing(Appointment::getScheduledAt).reversed())
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private static AuthorizationException unsupportedRole() {
        return new AuthorizationException("Only patients and doctors have their own appointment lists");
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(schedulingTimeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone());
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
}
