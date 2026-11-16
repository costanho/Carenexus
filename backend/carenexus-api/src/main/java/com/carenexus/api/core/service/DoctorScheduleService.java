package com.carenexus.api.core.service;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.AppointmentStatsResponse;
import com.carenexus.api.core.dto.response.DoctorDashboardResponse;
import com.carenexus.api.core.dto.response.ScheduleEntryResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.DoctorScheduleQueryRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The calling doctor's schedule and appointment views (API contract be-doctor-schedule). Every method is "self":
 * the doctor is always the caller ({@link AuthorizationService#requireDoctorSelf} with the caller's own id, which a
 * DOCTOR and an ADMIN pass). Lists are filtered, ordered, paged and counted in the database
 * ({@link DoctorScheduleQueryRepository}); appointment fields come from {@link AppointmentService#toResponses}.
 * All times are clinic wall-clock times ({@code carenexus.scheduling.time-zone}, {@link Clock} bean).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorScheduleService {

    /** Audit resource type of every list of this service. */
    public static final String RESOURCE = AppointmentService.RESOURCE;
    /** Cap (appointments) of the deprecated arrays and of the dashboard's today list. */
    public static final int LEGACY_CAP = AppointmentService.LEGACY_LIST_CAP;
    /** Length of the dashboard's upcoming list. */
    public static final int UPCOMING_LIST_SIZE = 10;

    static final String TYPE_APPOINTMENT = "APPOINTMENT";
    static final String TYPE_CONSULTATION = "CONSULTATION";
    static final String CONSULTATION_CANCELLED = "CANCELLED";

    static final String INVALID_STATUS_MESSAGE =
            "Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED";
    static final String NOT_APPOINTMENT_DOCTOR_MESSAGE = "Only the appointment's doctor can send a reminder";
    static final String NOT_ACTIVE_MESSAGE = "A reminder can only be sent for a scheduled appointment";
    static final String STARTED_MESSAGE = "A reminder can only be sent before the appointment starts";

    private static final Set<String> VALID_STATUSES = Set.of(AppointmentService.SCHEDULED,
            AppointmentService.RESCHEDULED, AppointmentService.COMPLETED, AppointmentService.CANCELLED,
            AppointmentService.NO_SHOW);
    private static final List<String> ACTIVE_STATUSES = List.of(AppointmentService.SCHEDULED,
            AppointmentService.RESCHEDULED);
    private static final DateTimeFormatter REMINDER_DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter REMINDER_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DEDUPE_HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", Locale.ROOT);

    private final DoctorRepository doctorRepository;
    private final DoctorScheduleQueryRepository scheduleRepository;
    private final UserRepository userRepository;
    private final RelationshipService relationshipService;
    private final AppointmentService appointmentService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final SystemSettingsService systemSettingsService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Value("${carenexus.scheduling.time-zone:Africa/Harare}")
    private String schedulingTimeZone;

    /** Result of a manual reminder ({@code POST /api/doctors/appointments/{id}/send-reminder}). */
    @Schema(description = "Result of a manual appointment reminder")
    public record ManualReminderResponse(
            @Schema(description = "The appointment", example = "412")
            Integer appointmentId,
            @Schema(description = "Clinic wall-clock time of the last manual reminder: now when alreadySent is false, "
                    + "the earlier send in this clock hour otherwise", example = "2026-11-19T16:05:41")
            LocalDateTime reminderSentAt,
            @Schema(description = "True when a manual reminder had already been sent in the current clock hour and this "
                    + "call sent nothing")
            boolean alreadySent) {
    }

    // ==================== ACCESS ====================

    /**
     * The caller's system id, after the self check every route of the doctor schedule applies: a DOCTOR passes for
     * themselves, an ADMIN passes, any other role gets 403.
     */
    public String requireDoctorCaller() {
        String callerId = authorizationService.currentSystemId();
        authorizationService.requireDoctorSelf(callerId);
        return callerId;
    }

    /** Self check plus the doctor profile the schedule routes need (an ADMIN has none: 404). */
    private Doctor requireDoctorProfile() {
        String callerId = requireDoctorCaller();
        return doctorRepository.findBySystemId(callerId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
    }

    // ==================== SCHEDULE ====================

    /**
     * Deprecated array ({@code GET /api/doctors/schedule}): the 200 most recent appointments of the doctor (every
     * status), each followed by its consultations. Audited as a list read.
     */
    public List<ScheduleEntryResponse> getLegacySchedule() {
        String doctorSystemId = requireDoctorProfile().getSystemId();
        List<Appointment> rows = scheduleRepository.findForDoctor(doctorSystemId,
                PageRequest.of(0, LEGACY_CAP, order(Sort.Direction.DESC)));
        List<ScheduleEntryResponse> entries = legacyLayout(doctorSystemId, rows, clinicNow());
        auditService.logListRead(RESOURCE, entries, ScheduleEntryResponse::getPatientSystemId);
        return entries;
    }

    /**
     * Appointments of the doctor that start in [from, to), every status, one row per appointment
     * ({@code GET /api/doctors/schedule/window}). Audited as a list read.
     */
    public PageResponse<ScheduleEntryResponse> getScheduleWindow(LocalDateTime from, LocalDateTime to, String order,
                                                                 Integer page, Integer size) {
        String doctorSystemId = requireDoctorProfile().getSystemId();
        Sort.Direction direction = parseOrder(order, Sort.Direction.ASC);
        requireWindow(from, to);
        Page<Appointment> result = scheduleRepository.findAll(
                appointmentFilter(doctorSystemId, null, null, from, to),
                PageParams.of(page, size, order(direction)));
        return pagedRows(doctorSystemId, result);
    }

    /**
     * The doctor's home ({@code GET /api/doctors/schedule/dashboard}): profile, counts from COUNT queries, today's
     * appointments (legacy layout, every status, newest first, at most 200) and the next 10 active appointments.
     * Audited as a list read over both lists.
     */
    public DoctorDashboardResponse getDashboard() {
        Doctor doctor = requireDoctorProfile();
        String doctorSystemId = doctor.getSystemId();
        User user = userRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        LocalDateTime now = clinicNow();
        int afterMinutes = needsOutcomeAfterMinutes();
        Map<String, Long> appointmentCounts = toMap(scheduleRepository.countAppointmentsByStatus(doctorSystemId));
        Map<String, Long> consultationCounts = toMap(scheduleRepository.countConsultationsByStatus(doctorSystemId));
        long upcoming = scheduleRepository.countForDoctorStartingAfter(doctorSystemId, ACTIVE_STATUSES, now);
        long needsOutcome = scheduleRepository.countNeedsOutcome(doctorSystemId, afterMinutes, now);
        // This doctor's patients (care team, non-cancelled appointments, consultations).
        int totalPatients = relationshipService.getPatientsForDoctor(doctorSystemId).size();

        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        List<Appointment> today = scheduleRepository.findForDoctorBetween(doctorSystemId, dayStart,
                dayStart.plusDays(1), PageRequest.of(0, LEGACY_CAP, order(Sort.Direction.DESC)));
        List<Appointment> next = scheduleRepository.findForDoctorStartingAfter(doctorSystemId, ACTIVE_STATUSES, now,
                PageRequest.of(0, UPCOMING_LIST_SIZE, order(Sort.Direction.ASC)));

        List<ScheduleEntryResponse> todaySchedule = legacyLayout(doctorSystemId, today, now, afterMinutes);
        List<ScheduleEntryResponse> upcomingSchedule = legacyLayout(doctorSystemId, next, now, afterMinutes);
        List<ScheduleEntryResponse> audited = new ArrayList<>(todaySchedule);
        audited.addAll(upcomingSchedule);
        auditService.logListRead(RESOURCE, audited, ScheduleEntryResponse::getPatientSystemId);

        return DoctorDashboardResponse.builder()
                .doctorSystemId(doctorSystemId)
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
                .totalAppointments(toInt(sum(appointmentCounts)))
                .upcomingAppointments(toInt(upcoming))
                .needsOutcomeCount(toInt(needsOutcome))
                .completedConsultations(toInt(countOf(consultationCounts, "COMPLETED")))
                .pendingConsultations(toInt(countOf(consultationCounts, "DRAFT")
                        + countOf(consultationCounts, "IN_PROGRESS")))
                .todaySchedule(todaySchedule)
                .upcomingSchedule(upcomingSchedule)
                .build();
    }

    // ==================== APPOINTMENTS ====================

    /**
     * Deprecated array ({@code GET /api/doctors/appointments}): at most 200 appointments of the caller, newest first,
     * every status. An ADMIN gets an empty array. Audited by {@link AppointmentService}.
     */
    public List<AppointmentResponse> getLegacyAppointments() {
        String callerId = requireDoctorCaller();
        return appointmentService.getAppointmentsForDoctorAsCaller(callerId);
    }

    /**
     * Filterable paged list of the caller's appointments ({@code GET /api/doctors/appointments/search}); an ADMIN gets
     * an empty page. Audited as a list read.
     *
     * @param statuses        one or more statuses, comma-separated and/or repeated; empty means every status
     * @param patientSystemId plain filter on the caller's own rows (no relationship check)
     * @param from            inclusive lower bound on scheduledAt
     * @param to              exclusive upper bound on scheduledAt
     * @param order           asc or desc (default)
     */
    public PageResponse<ScheduleEntryResponse> searchAppointments(List<String> statuses, String patientSystemId,
                                                                  LocalDateTime from, LocalDateTime to, String order,
                                                                  Integer page, Integer size) {
        String callerId = requireDoctorCaller();
        Set<String> statusFilter = parseStatuses(statuses);
        Sort.Direction direction = parseOrder(order, Sort.Direction.DESC);
        if (from != null && to != null) {
            requireWindow(from, to);
        }
        Page<Appointment> result = scheduleRepository.findAll(
                appointmentFilter(callerId, statusFilter, patientSystemId, from, to),
                PageParams.of(page, size, order(direction)));
        return pagedRows(callerId, result);
    }

    /**
     * The caller's appointments that still need an outcome ({@code GET /api/doctors/appointments/needs-outcome}):
     * SCHEDULED or RESCHEDULED and ended more than {@code appointments.needs-outcome-after-minutes} ago, most recently
     * started first. An ADMIN gets an empty page. Audited as a list read.
     */
    public PageResponse<ScheduleEntryResponse> getNeedsOutcome(String patientSystemId, Integer page, Integer size) {
        String callerId = requireDoctorCaller();
        LocalDateTime now = clinicNow();
        int afterMinutes = needsOutcomeAfterMinutes();
        Page<Integer> ids = scheduleRepository.findNeedsOutcomeIds(callerId,
                patientSystemId == null ? "" : patientSystemId, afterMinutes, now,
                PageParams.of(page, size, Sort.unsorted()));
        List<Appointment> rows = ids.getContent().isEmpty() ? List.of()
                : inOrder(ids.getContent(), scheduleRepository.findAppointmentsByIds(ids.getContent()));
        List<ScheduleEntryResponse> entries = appointmentRows(callerId, rows, now, afterMinutes);
        auditService.logListRead(RESOURCE, entries, ScheduleEntryResponse::getPatientSystemId);
        return PageResponse.of(ids, entries);
    }

    /**
     * Counts of the caller's appointments ({@code GET /api/doctors/appointments/stats}): one COUNT ... GROUP BY status
     * plus the upcoming and needs-outcome counts. Not audited (no patient data). An ADMIN gets zeros.
     */
    public AppointmentStatsResponse getStats() {
        String callerId = requireDoctorCaller();
        LocalDateTime now = clinicNow();
        Map<String, Long> counts = toMap(scheduleRepository.countAppointmentsByStatus(callerId));
        long total = sum(counts);
        if (total == 0) {
            return AppointmentStatsResponse.empty();
        }
        long completed = countOf(counts, AppointmentService.COMPLETED);
        long cancelled = countOf(counts, AppointmentService.CANCELLED);
        long noShow = countOf(counts, AppointmentService.NO_SHOW);
        long rescheduled = countOf(counts, AppointmentService.RESCHEDULED);
        long upcoming = scheduleRepository.countForDoctorStartingAfter(callerId, ACTIVE_STATUSES, now);
        long needsOutcome = scheduleRepository.countNeedsOutcome(callerId, needsOutcomeAfterMinutes(), now);
        long closed = completed + cancelled + noShow;
        double completionRate = closed == 0 ? 0.0 : (completed * 100.0) / closed;
        double noShowRate = (noShow * 100.0) / total;
        return new AppointmentStatsResponse(total, upcoming, completed, cancelled, noShow, rescheduled,
                completionRate, noShowRate, needsOutcome);
    }

    // ==================== MANUAL REMINDER ====================

    /**
     * Sends one in-app reminder about an upcoming appointment to the patient and the caregivers who may see the
     * patient's appointments ({@code POST /api/doctors/appointments/{id}/send-reminder}). Only the appointment's
     * doctor, only for an active appointment that has not started. At most one manual reminder per appointment and
     * clinic clock hour goes out (conditional update of {@code manual_reminder_sent_at}); a repeat inside the hour
     * sends nothing and answers {@code alreadySent = true}. The automatic reminder flags are never touched.
     */
    @Transactional
    public ManualReminderResponse sendManualReminder(Integer appointmentId) {
        String callerId = requireDoctorCaller();
        Appointment appointment = scheduleRepository.findAppointment(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        String patientSystemId = appointment.getPatientSystemId();
        if (!callerId.equals(appointment.getDoctorSystemId())) {
            auditService.logAccessDenied(callerId, RESOURCE, appointmentId, patientSystemId, null,
                    "Manual reminder refused: caller is not the appointment's doctor");
            throw new AuthorizationException(NOT_APPOINTMENT_DOCTOR_MESSAGE, RESOURCE, appointmentId);
        }
        if (!AppointmentService.ACTIVE_STATUSES.contains(appointment.getStatus())) {
            throw new ConflictException(NOT_ACTIVE_MESSAGE);
        }
        LocalDateTime now = clinicNow().truncatedTo(ChronoUnit.SECONDS);
        if (!appointment.getScheduledAt().isAfter(now)) {
            throw new ConflictException(STARTED_MESSAGE);
        }

        LocalDateTime hourStart = now.truncatedTo(ChronoUnit.HOURS);
        if (scheduleRepository.claimManualReminder(appointmentId, now, hourStart) == 0) {
            // Lost the claim: the appointment changed under the lock (cancelled, outcome, moved into the past),
            // or a reminder already went out in this clock hour.
            requireStillRemindable(appointmentId, now);
            LocalDateTime sentAt = manualReminderTimes(List.of(appointmentId)).get(appointmentId);
            return new ManualReminderResponse(appointmentId, sentAt != null ? sentAt : now, true);
        }

        AppointmentResponse view = appointmentService.toResponses(List.of(appointment)).get(0);
        notificationPublisher.publishToPatientCircle(patientSystemId, PatientPermission.APPOINTMENTS,
                reminderTemplate(appointment, view, callerId, now));
        auditService.logClinicalAccess("UPDATE", RESOURCE, appointmentId, patientSystemId);
        return new ManualReminderResponse(appointmentId, now, false);
    }

    /** Re-reads the committed appointment row and applies the active and not-started rules to it. */
    private void requireStillRemindable(Integer appointmentId, LocalDateTime now) {
        List<Object[]> rows = scheduleRepository.findCurrentState(appointmentId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Appointment not found");
        }
        Object[] row = rows.get(0);
        if (!AppointmentService.ACTIVE_STATUSES.contains(String.valueOf(row[0]))) {
            throw new ConflictException(NOT_ACTIVE_MESSAGE);
        }
        LocalDateTime start = toLocalDateTime(row[1]);
        if (start == null || !start.isAfter(now)) {
            throw new ConflictException(STARTED_MESSAGE);
        }
    }

    private NotificationCommand reminderTemplate(Appointment appointment, AppointmentResponse view, String callerId,
                                                 LocalDateTime now) {
        String doctorName = view.getDoctorName() != null ? view.getDoctorName() : "your doctor";
        String patientName = view.getPatientName() != null ? view.getPatientName() : "The patient";
        LocalDateTime start = appointment.getScheduledAt();
        String message = "Reminder from " + doctorName + ": " + patientName + " has an appointment on "
                + REMINDER_DAY.format(start) + " at " + REMINDER_TIME.format(start) + " (clinic time).";
        return NotificationCommand.builder()
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_REMINDER)
                .priority(NotificationPriority.NORMAL)
                .title("Appointment reminder")
                .message(message)
                .target(NotificationTarget.APPOINTMENTS)
                .resourceType(RESOURCE)
                .resourceId(appointment.getAppointmentId())
                .patientSystemId(appointment.getPatientSystemId())
                .actorSystemId(callerId)
                .dedupeKey("APPT_REMINDER_MANUAL:" + appointment.getAppointmentId() + ":" + DEDUPE_HOUR.format(now))
                .expiresAt(start.plusMinutes(durationOf(appointment)))
                .build();
    }

    // ==================== ROWS ====================

    private PageResponse<ScheduleEntryResponse> pagedRows(String doctorSystemId, Page<Appointment> page) {
        List<ScheduleEntryResponse> entries = appointmentRows(doctorSystemId, page.getContent(), clinicNow(),
                page.getContent().isEmpty() ? 0 : needsOutcomeAfterMinutes());
        auditService.logListRead(RESOURCE, entries, ScheduleEntryResponse::getPatientSystemId);
        return PageResponse.of(page, entries);
    }

    private List<ScheduleEntryResponse> legacyLayout(String doctorSystemId, List<Appointment> rows, LocalDateTime now) {
        return legacyLayout(doctorSystemId, rows, now, rows.isEmpty() ? 0 : needsOutcomeAfterMinutes());
    }

    /**
     * Legacy layout: one APPOINTMENT row per appointment, each followed by every consultation of that appointment
     * (any status) as a CONSULTATION row, oldest consultation first.
     */
    private List<ScheduleEntryResponse> legacyLayout(String doctorSystemId, List<Appointment> rows, LocalDateTime now,
                                                     int afterMinutes) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<ScheduleEntryResponse> appointmentEntries = appointmentRows(doctorSystemId, rows, now, afterMinutes);
        Map<Integer, List<Consultation>> consultations = scheduleRepository
                .findConsultationsOfAppointments(doctorSystemId, idsOf(rows)).stream()
                .collect(Collectors.groupingBy(Consultation::getAppointmentId));
        List<ScheduleEntryResponse> result = new ArrayList<>();
        for (ScheduleEntryResponse entry : appointmentEntries) {
            result.add(entry);
            for (Consultation consultation : consultations.getOrDefault(entry.getAppointmentId(), List.of())) {
                result.add(consultationRow(consultation, entry));
            }
        }
        return result;
    }

    /** APPOINTMENT rows for these appointments, in the given order (batch lookups only). */
    private List<ScheduleEntryResponse> appointmentRows(String doctorSystemId, List<Appointment> rows,
                                                        LocalDateTime now, int afterMinutes) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = idsOf(rows);
        List<AppointmentResponse> views = appointmentService.toResponses(rows);
        Map<Integer, DoctorScheduleQueryRepository.ConsultationLink> links = linkedConsultations(
                scheduleRepository.findConsultationLinks(doctorSystemId, ids));
        Map<Integer, LocalDateTime> reminders = manualReminderTimes(ids);

        List<ScheduleEntryResponse> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Appointment a = rows.get(i);
            AppointmentResponse view = views.get(i);
            DoctorScheduleQueryRepository.ConsultationLink link = links.get(a.getAppointmentId());
            result.add(ScheduleEntryResponse.builder()
                    .entryId("APT_" + a.getAppointmentId())
                    .type(TYPE_APPOINTMENT)
                    .appointmentId(a.getAppointmentId())
                    .consultationId(link != null ? link.getConsultationId() : null)
                    .patientSystemId(a.getPatientSystemId())
                    .patientName(view.getPatientName() != null ? view.getPatientName() : "Unknown")
                    .doctorSystemId(a.getDoctorSystemId())
                    .scheduledAt(a.getScheduledAt())
                    .appointmentType(view.getType())
                    .appointmentStatus(view.getStatus())
                    .durationMinutes(view.getDurationMinutes())
                    .reasonForVisit(view.getReasonForVisit())
                    // Legacy meeting links are never returned; calls use callJoinRoute.
                    .videoConsultationLink(null)
                    .reminderSent(view.getReminderSent())
                    .reminderSentAt(reminders.get(a.getAppointmentId()))
                    .notes(view.getNotes())
                    .facilityName(view.getFacilityName())
                    .contactPhone(Appointment.TYPE_PHONE.equals(view.getType()) ? view.getContactPhone() : null)
                    .consultationStatus(link != null ? link.getStatus() : null)
                    .needsOutcome(needsOutcome(a, now, afterMinutes))
                    .createdAt(view.getCreatedAt())
                    .updatedAt(view.getUpdatedAt())
                    .callStatus(view.getCallStatus())
                    .callJoinRoute(view.getCallJoinRoute())
                    .joinWindowOpensAt(view.getJoinWindowOpensAt())
                    .joinWindowClosesAt(view.getJoinWindowClosesAt())
                    .build());
        }
        return result;
    }

    private static ScheduleEntryResponse consultationRow(Consultation consultation, ScheduleEntryResponse appointment) {
        return ScheduleEntryResponse.builder()
                .entryId("CON_" + consultation.getConsultationId())
                .type(TYPE_CONSULTATION)
                .appointmentId(consultation.getAppointmentId())
                .consultationId(consultation.getConsultationId())
                .patientSystemId(consultation.getPatientSystemId())
                .patientName(appointment.getPatientName())
                .doctorSystemId(consultation.getDoctorSystemId())
                .scheduledAt(appointment.getScheduledAt())
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
                .needsOutcome(false)
                .build();
    }

    /** Per appointment: the newest non-cancelled consultation (by createdAt, then consultationId). */
    private static Map<Integer, DoctorScheduleQueryRepository.ConsultationLink> linkedConsultations(
            List<DoctorScheduleQueryRepository.ConsultationLink> links) {
        Comparator<DoctorScheduleQueryRepository.ConsultationLink> newest = Comparator
                .comparing(DoctorScheduleQueryRepository.ConsultationLink::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(DoctorScheduleQueryRepository.ConsultationLink::getConsultationId);
        Map<Integer, DoctorScheduleQueryRepository.ConsultationLink> result = new HashMap<>();
        for (DoctorScheduleQueryRepository.ConsultationLink link : links) {
            if (CONSULTATION_CANCELLED.equals(link.getStatus())) {
                continue;
            }
            result.merge(link.getAppointmentId(), link, (a, b) -> newest.compare(a, b) >= 0 ? a : b);
        }
        return result;
    }

    private Map<Integer, LocalDateTime> manualReminderTimes(Collection<Integer> ids) {
        Map<Integer, LocalDateTime> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (Object[] row : scheduleRepository.findManualReminderTimes(ids)) {
            LocalDateTime sentAt = toLocalDateTime(row[1]);
            if (row[0] != null && sentAt != null) {
                result.put(((Number) row[0]).intValue(), sentAt);
            }
        }
        return result;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    /** Status SCHEDULED or RESCHEDULED and start + duration + N minutes before clinic now (same rule as the SQL). */
    private static boolean needsOutcome(Appointment appointment, LocalDateTime now, int afterMinutes) {
        return AppointmentService.ACTIVE_STATUSES.contains(appointment.getStatus())
                && appointment.getScheduledAt() != null
                && appointment.getScheduledAt().plusMinutes((long) durationOf(appointment) + afterMinutes).isBefore(now);
    }

    // ==================== FILTERS AND PARAMETERS ====================

    private static Specification<Appointment> appointmentFilter(String doctorSystemId, Collection<String> statuses,
                                                                String patientSystemId, LocalDateTime from,
                                                                LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("doctorSystemId"), doctorSystemId));
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (patientSystemId != null) {
                predicates.add(cb.equal(root.get("patientSystemId"), patientSystemId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("scheduledAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static void requireWindow(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new BadRequestException("to must be after from");
        }
    }

    /** Comma-separated and/or repeated statuses, trimmed and case-insensitive; empty means every status. */
    static Set<String> parseStatuses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> statuses = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String status = part.trim().toUpperCase(Locale.ROOT);
                if (status.isEmpty()) {
                    continue;
                }
                if (!VALID_STATUSES.contains(status)) {
                    throw new BadRequestException(INVALID_STATUS_MESSAGE);
                }
                statuses.add(status);
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }

    static Sort.Direction parseOrder(String order, Sort.Direction defaultDirection) {
        if (order == null || order.isBlank()) {
            return defaultDirection;
        }
        String value = order.trim();
        if ("asc".equalsIgnoreCase(value)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equalsIgnoreCase(value)) {
            return Sort.Direction.DESC;
        }
        throw new BadRequestException("Invalid value for parameter 'order'");
    }

    /** scheduledAt, then appointmentId, in the same direction (deterministic pages). */
    private static Sort order(Sort.Direction direction) {
        return Sort.by(direction, "scheduledAt").and(Sort.by(direction, "appointmentId"));
    }

    // ==================== HELPERS ====================

    private int needsOutcomeAfterMinutes() {
        return systemSettingsService.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES);
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(schedulingTimeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Clinic wall-clock now. */
    private LocalDateTime clinicNow() {
        return LocalDateTime.now(clock.withZone(zone()));
    }

    private static int durationOf(Appointment appointment) {
        return appointment.getDurationMinutes() != null ? appointment.getDurationMinutes() : 30;
    }

    private static List<Integer> idsOf(List<Appointment> rows) {
        return rows.stream().map(Appointment::getAppointmentId).toList();
    }

    /** {@code rows} in the order of {@code ids} (rows missing from the database are skipped). */
    private static List<Appointment> inOrder(List<Integer> ids, List<Appointment> rows) {
        Map<Integer, Appointment> byId = rows.stream()
                .collect(Collectors.toMap(Appointment::getAppointmentId, Function.identity(), (a, b) -> a));
        List<Appointment> result = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            Appointment row = byId.get(id);
            if (row != null) {
                result.add(row);
            }
        }
        return result;
    }

    private static Map<String, Long> toMap(List<DoctorScheduleQueryRepository.StatusCount> counts) {
        Map<String, Long> result = new HashMap<>();
        for (DoctorScheduleQueryRepository.StatusCount count : counts) {
            if (count.getStatus() != null && count.getTotal() != null) {
                result.merge(count.getStatus(), count.getTotal(), Long::sum);
            }
        }
        return result;
    }

    private static long countOf(Map<String, Long> counts, String status) {
        return counts.getOrDefault(status, 0L);
    }

    private static long sum(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static int toInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
