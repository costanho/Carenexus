package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.service.CallInfoService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.AppointmentBookedEvent;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.common.spi.BookingConstraint;
import com.carenexus.api.common.spi.BookingRequestContext;
import com.carenexus.api.communications.service.CommunicationLabels;
import com.carenexus.api.core.dto.request.CreateAppointmentRequest;
import com.carenexus.api.core.dto.request.RescheduleAppointmentRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.BookingRulesResponse;
import com.carenexus.api.core.dto.response.BusySlotResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Appointment booking and lifecycle (API contract be-appointments-core).
 *
 * Status model (values match the DB CHECK constraint on appointments.status):
 *   SCHEDULED   -> COMPLETED | CANCELLED | NO_SHOW, or RESCHEDULED by moving it to a new time
 *   RESCHEDULED -> COMPLETED | CANCELLED | NO_SHOW, or RESCHEDULED again (new time)
 *   COMPLETED, CANCELLED, NO_SHOW are terminal and can never be changed again (409).
 *   Asking for the status an appointment already has is a no-op.
 * SCHEDULED and RESCHEDULED are the "active" states: they occupy the doctor's and the patient's calendar.
 * Cancelling or marking NO_SHOW is refused (409) while a COMPLETED consultation exists for the appointment.
 *
 * All times are local wall-clock times of the clinic time zone (carenexus.scheduling.time-zone,
 * default Africa/Harare), which is what the clients send ("YYYY-MM-DDTHH:mm:00", no offset).
 *
 * Events (published after the row is saved, inside the changing transaction): AppointmentBookedEvent,
 * AppointmentRescheduledEvent (start time changed), AppointmentCancelledEvent, AppointmentStatusChangedEvent
 * (COMPLETED, NO_SHOW), and the calls module's {@link AppointmentCallRelevantChangeEvent}.
 *
 * Video calls: VIDEO appointments get a managed call session (package com.carenexus.api.calls). Booking VIDEO, or
 * changing a type to VIDEO, is refused with 409 VIDEO_UNAVAILABLE when no call provider is configured. The legacy
 * video_consultation_link column is never written and never returned.
 *
 * PHONE appointments carry the number the doctor calls (appointments.contact_phone), which only the appointment's
 * doctor, the patient, caregivers who may view the patient's appointments and admins see.
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

    /** Audit resource type of appointments. */
    public static final String RESOURCE = "APPOINTMENT";

    /** Statuses that occupy a slot in the doctor's and the patient's calendar. */
    public static final Set<String> ACTIVE_STATUSES = Set.of(SCHEDULED, RESCHEDULED);
    private static final Set<String> TERMINAL_STATUSES = Set.of(COMPLETED, CANCELLED, NO_SHOW);
    private static final Set<String> VALID_STATUSES = Set.of(SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED, NO_SHOW);
    private static final Set<String> VALID_TYPES = Set.of(Appointment.TYPE_VIDEO, Appointment.TYPE_IN_PERSON,
            Appointment.TYPE_PHONE);

    /** Hard cap of the deprecated array endpoints (rule 0.8). */
    public static final int LEGACY_LIST_CAP = 200;

    private static final int DEFAULT_DURATION_MINUTES = 30;
    private static final int MIN_DURATION_MINUTES = 5;
    private static final int MAX_DURATION_MINUTES = 240;
    private static final int SLOT_GRANULARITY_MINUTES = 5;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_PHONE_LENGTH = 20;
    private static final String SLOT_INDEX_NAME = "ux_appointments_doctor_active_slot";
    private static final String PATIENT_SLOT_INDEX_NAME = "ux_appointments_patient_active_slot";

    static final String CONTACT_PHONE_REQUIRED = "CONTACT_PHONE_REQUIRED";
    static final String SLOT_TAKEN_MESSAGE = "This time slot is no longer available. Please choose another time.";
    static final String PATIENT_BUSY_MESSAGE = "The patient already has another appointment at this time";
    static final String INVALID_TYPE_MESSAGE = "Invalid appointment type. Must be VIDEO, IN_PERSON, or PHONE";
    static final String INVALID_STATUS_MESSAGE =
            "Invalid appointment status. Must be SCHEDULED, COMPLETED, CANCELLED, NO_SHOW, or RESCHEDULED";
    static final String VIDEO_UNAVAILABLE_MESSAGE =
            "Video appointments are not available at the moment. Please choose another appointment type.";
    static final String CONTACT_PHONE_REQUIRED_MESSAGE = "A contact phone number is required for a phone appointment";

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final CallInfoService callInfoService;
    private final CallProvider callProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<BookingConstraint> bookingConstraints;
    private final Clock clock;

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

    /**
     * Filters of the canonical list ({@code GET /api/appointments}). Null means "no filter" (or the caller's own id,
     * depending on the role).
     *
     * @param statuses raw values; each may hold several comma-separated statuses
     * @param from     inclusive lower bound on scheduledAt
     * @param to       exclusive upper bound on scheduledAt
     * @param order    "asc" or "desc" (default)
     */
    public record ListQuery(String patientSystemId, String doctorSystemId, List<String> statuses,
                            LocalDateTime from, LocalDateTime to, String order, Integer page, Integer size) {
    }

    // ==================== BOOKING ====================

    /**
     * Book an appointment. PATIENT for themselves; CAREGIVER with MANAGE_APPOINTMENTS on the patient; DOCTOR into
     * their own schedule for a related patient; ADMIN for anyone. Errors follow the order of the API contract.
     */
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        if (request == null) {
            throw new BadRequestException("Appointment details are required");
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
                    throw new AuthorizationException("You can only book appointments for yourself", RESOURCE, null);
                }
            }
            case AuthorizationService.CAREGIVER -> {
                if (patientSystemId == null) {
                    throw new BadRequestException("Patient ID is required");
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_APPOINTMENTS);
            }
            case AuthorizationService.DOCTOR -> {
                if (patientSystemId == null) {
                    throw new BadRequestException("Patient ID is required");
                }
                if (doctorSystemId == null) {
                    doctorSystemId = callerId;
                } else if (!doctorSystemId.equals(callerId)) {
                    throw new AuthorizationException("Doctors can only book appointments in their own schedule", RESOURCE, null);
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_APPOINTMENTS);
            }
            case AuthorizationService.ADMIN -> {
                if (patientSystemId == null) {
                    throw new BadRequestException("Patient ID is required");
                }
            }
            default -> throw new AuthorizationException("Your role is not allowed to book appointments", RESOURCE, null);
        }

        if (doctorSystemId == null) {
            throw new BadRequestException("Doctor ID is required");
        }
        if (patientSystemId.equals(doctorSystemId)) {
            throw new BadRequestException("You cannot book an appointment with yourself");
        }
        if (patientRepository.findBySystemId(patientSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Patient not found");
        }
        requireBookableDoctor(doctorSystemId);

        String type = normalizeType(request.getType(), Appointment.TYPE_IN_PERSON);
        if (Appointment.TYPE_VIDEO.equals(type)) {
            requireVideoAvailable();
        }
        if (request.getScheduledAt() == null) {
            throw new BadRequestException("Scheduled date/time is required");
        }
        int duration = validateDuration(request.getDurationMinutes() != null
                ? request.getDurationMinutes() : DEFAULT_DURATION_MINUTES);
        LocalDateTime scheduledAt = validateStartTime(request.getScheduledAt(), duration, role);

        Integer facilityId = request.getFacilityId();
        if (facilityId != null && appointmentRepository.countActiveFacilitiesById(facilityId) == 0) {
            throw new ResourceNotFoundException("Facility not found");
        }

        String contactPhone = Appointment.TYPE_PHONE.equals(type)
                ? resolveContactPhone(patientSystemId, request.getContactPhone(), null)
                : null;

        lockAndCheckAvailability(doctorSystemId, patientSystemId, scheduledAt, duration, -1);
        runBookingConstraints(new BookingRequestContext(doctorSystemId, patientSystemId, scheduledAt, duration,
                type, role, null));

        Appointment appointment = Appointment.builder()
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .facilityId(facilityId)
                .scheduledAt(scheduledAt)
                .durationMinutes(duration)
                .type(type)
                .status(SCHEDULED)
                .reasonForVisit(limitText(request.getReasonForVisit(), "Reason for visit"))
                // Legacy column: never written. Calls are managed per appointment (call_sessions).
                .videoConsultationLink(null)
                .reminderSent(false)
                .notes(limitText(request.getNotes(), "Notes"))
                .contactPhone(contactPhone)
                .build();

        Appointment saved = saveSlot(appointment);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getAppointmentId(), patientSystemId);
        eventPublisher.publishEvent(new AppointmentBookedEvent(saved.getAppointmentId(), patientSystemId,
                doctorSystemId, callerId, role, type, scheduledAt, duration));
        AppointmentCallRelevantChangeEvent.Kind callChange = Appointment.TYPE_VIDEO.equals(type)
                ? AppointmentCallRelevantChangeEvent.Kind.CREATED_VIDEO
                : null;
        publishCallChange(saved, callChange);
        return toResponse(saved, callInfoService.describeAfterChange(saved, callChange));
    }

    // ==================== READS ====================

    /** One appointment: its doctor, an admin, or anyone with APPOINTMENTS access to the patient. Audited. */
    public AppointmentResponse getAppointmentById(Integer appointmentId) {
        Appointment appointment = findAppointment(appointmentId);
        requireCanView(appointment);
        auditService.logClinicalAccess("READ", RESOURCE, appointmentId, appointment.getPatientSystemId());
        return toResponse(appointment, callInfoService.describeOne(appointment));
    }

    /**
     * Canonical paged list. Scoping: a PATIENT sees their own appointments; a CAREGIVER must name a patient they may
     * view appointments of; a DOCTOR sees only their own appointments; an ADMIN sees everything. All filters run in
     * the database; names, facilities and call info are batch-loaded.
     */
    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> listAppointments(ListQuery query) {
        ListQuery q = query != null ? query : new ListQuery(null, null, null, null, null, null, null, null);
        Sort.Direction direction = parseOrder(q.order());
        Set<String> statuses = parseStatuses(q.statuses());
        if (q.from() != null && q.to() != null && !q.to().isAfter(q.from())) {
            throw new BadRequestException("to must be after from");
        }

        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        String patientSystemId = trimToNull(q.patientSystemId());
        String doctorSystemId = trimToNull(q.doctorSystemId());

        switch (role) {
            case AuthorizationService.PATIENT -> {
                if (patientSystemId == null) {
                    patientSystemId = callerId;
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
            }
            case AuthorizationService.CAREGIVER -> {
                if (patientSystemId == null) {
                    throw new BadRequestException("patientSystemId is required");
                }
                authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
            }
            case AuthorizationService.DOCTOR -> {
                if (doctorSystemId == null) {
                    doctorSystemId = callerId;
                } else if (!doctorSystemId.equals(callerId)) {
                    authorizationService.requireDoctorSelf(doctorSystemId);
                }
            }
            case AuthorizationService.ADMIN -> {
                // every appointment, filtered as asked
            }
            default -> throw new AuthorizationException("You don't have permission to access this resource", RESOURCE, null);
        }

        Sort sort = Sort.by(direction, "scheduledAt").and(Sort.by(direction, "appointmentId"));
        Page<Appointment> page = appointmentRepository.findAll(
                filter(patientSystemId, doctorSystemId, statuses, q.from(), q.to(), null),
                PageParams.of(q.page(), q.size(), sort));
        List<Appointment> rows = page.getContent();
        auditService.logListRead(RESOURCE, rows, Appointment::getPatientSystemId);
        return PageResponse.of(page, toResponses(rows));
    }

    /**
     * Deprecated array of a patient's appointments (newest first, at most {@link #LEGACY_LIST_CAP}). Caller must be
     * the patient, a related doctor, a caregiver allowed to view appointments, or an admin.
     */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsForPatientAsCaller(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
        List<Appointment> rows = appointmentRepository.findFirst(
                filter(patientSystemId, null, null, null, null, null), newestFirst(), LEGACY_LIST_CAP);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * Deprecated array of a doctor's appointments (newest first, at most {@link #LEGACY_LIST_CAP}). Caller must be
     * that doctor or an admin.
     */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsForDoctorAsCaller(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Appointment> rows = appointmentRepository.findFirst(
                filter(null, doctorSystemId, null, null, null, null), newestFirst(), LEGACY_LIST_CAP);
        auditService.logListRead(RESOURCE, rows, Appointment::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Upcoming (active, future) appointments of a patient, with the same access rule as
     * {@link #getAppointmentsForPatientAsCaller}.
     */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getUpcomingAppointmentsForPatientAsCaller(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.APPOINTMENTS);
        List<AppointmentResponse> result = getUpcomingAppointments(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return result;
    }

    /** Internal (no access check): used by services that already authorized the caller. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsByPatientSystemId(String patientSystemId) {
        return toResponses(appointmentRepository.findAll(
                filter(patientSystemId, null, null, null, null, null), newestFirst()));
    }

    /** Internal (no access check): used by services that already authorized the caller. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsForPatients(List<String> patientSystemIds) {
        if (patientSystemIds == null || patientSystemIds.isEmpty()) return List.of();
        return toResponses(appointmentRepository.findByPatientSystemIdIn(patientSystemIds));
    }

    /** Internal (no access check): callers must have checked that the caller is this doctor or an admin. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsByDoctorSystemId(String doctorSystemId) {
        return toResponses(appointmentRepository.findAll(
                filter(null, doctorSystemId, null, null, null, null), newestFirst()));
    }

    /** Internal (no access check): active appointments of the patient that have not started yet, soonest first. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getUpcomingAppointments(String patientSystemId) {
        return toResponses(appointmentRepository.findAll(
                filter(patientSystemId, null, ACTIVE_STATUSES, null, null, now()), soonestFirst()));
    }

    /** Internal (no access check): callers must have checked that the caller is this doctor or an admin. */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> getDoctorSchedule(String doctorSystemId, LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new BadRequestException("Invalid date range");
        }
        return toResponses(appointmentRepository.findByDoctorSystemIdAndScheduledAtBetween(doctorSystemId, start, end)
                .stream()
                .sorted(java.util.Comparator.comparing(Appointment::getScheduledAt)
                        .thenComparing(Appointment::getAppointmentId))
                .toList());
    }

    /**
     * Busy times of a doctor on one clinic day, for slot pickers: start, end and duration of each active appointment
     * starting that day, ascending. Never who the patient is or why they come. Any authenticated user may call it.
     */
    @Transactional(readOnly = true)
    public List<BusySlotResponse> getDoctorBusySlots(String doctorSystemId, LocalDate date) {
        if (date == null) {
            throw new BadRequestException("Date is required");
        }
        if (doctorSystemId == null || doctorRepository.findBySystemId(doctorSystemId).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay().minusNanos(1);
        return appointmentRepository
                .findByDoctorSystemIdAndStatusInAndScheduledAtBetweenOrderByScheduledAtAsc(
                        doctorSystemId, ACTIVE_STATUSES, dayStart, dayEnd)
                .stream()
                .map(a -> new BusySlotResponse(a.getScheduledAt(), a.getScheduledAt().plusMinutes(durationOf(a)),
                        durationOf(a)))
                .toList();
    }

    /** Clinic booking rules, so clients can show the same hours and options the server enforces. */
    @Transactional(readOnly = true)
    public BookingRulesResponse getBookingRules() {
        return new BookingRulesResponse(zone().getId(), openHour, closeHour, allowWeekends, SLOT_GRANULARITY_MINUTES,
                MIN_DURATION_MINUTES, MAX_DURATION_MINUTES, maxAdvanceDays, isVideoCallsAvailable());
    }

    /** True when VIDEO appointments can be booked: a call provider is configured. */
    public boolean isVideoCallsAvailable() {
        return callProvider != null && callProvider.isConfigured();
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getAppointmentsByStatus(String status) {
        authorizationService.requireAdmin();
        String normalized = normalizeStatus(status);
        return toResponses(appointmentRepository.findAll(filter(null, null, Set.of(normalized), null, null, null),
                newestFirst()));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getUpcomingForUser(String userSystemId, String userRole) {
        String role = upper(userRole);
        if (AuthorizationService.PATIENT.equals(role)) {
            return getUpcomingAppointments(userSystemId);
        } else if (AuthorizationService.DOCTOR.equals(role)) {
            return toResponses(appointmentRepository.findAll(
                    filter(null, userSystemId, ACTIVE_STATUSES, null, null, now()), soonestFirst()));
        }
        throw unsupportedRole();
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getCompletedForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, COMPLETED);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getCancelledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, CANCELLED);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getRescheduledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, RESCHEDULED);
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> getNoShowForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, NO_SHOW);
    }

    // ==================== CHANGES ====================

    /**
     * Change the status of an appointment.
     * - CANCELLED: see {@link #cancelAppointment(Integer)} (manage rule), returns the appointment.
     * - COMPLETED / NO_SHOW: view rule, then only the appointment's doctor (or an admin), and only once the
     *   appointment has started; NO_SHOW is refused while a COMPLETED consultation exists.
     * - RESCHEDULED: only by moving the appointment to a new time ({@link #rescheduleAppointment}).
     * - SCHEDULED: an appointment can never be set back.
     * Setting the status it already has is a no-op (no event, no audit row).
     */
    public AppointmentResponse updateAppointmentStatus(Integer appointmentId, String newStatus) {
        String status = normalizeStatus(newStatus);
        Appointment appointment = findAppointmentForUpdate(appointmentId);

        if (CANCELLED.equals(status)) {
            return cancel(appointment);
        }

        requireCanView(appointment);
        if (status.equals(appointment.getStatus())) {
            return toResponse(appointment, callInfoService.describeOne(appointment));
        }
        requireNotTerminal(appointment);

        switch (status) {
            case COMPLETED, NO_SHOW -> {
                requireAppointmentDoctorOrAdmin(appointment,
                        "Only the appointment's doctor can mark it as " + describeStatus(status));
                if (appointment.getScheduledAt().isAfter(now())) {
                    throw new BadRequestException("An appointment that has not started yet cannot be marked as "
                            + describeStatus(status));
                }
                if (NO_SHOW.equals(status) && appointmentRepository.existsCompletedConsultation(appointmentId)) {
                    throw new ConflictException("This appointment has a completed consultation and cannot be marked as no-show");
                }
            }
            case RESCHEDULED -> throw new BadRequestException("To reschedule an appointment, choose a new date and time");
            default -> throw new ConflictException("A " + describeStatus(appointment.getStatus())
                    + " appointment cannot be set back to scheduled");
        }

        Appointment saved = applyOutcome(appointment, status, authorizationService.currentSystemId());
        return toResponse(saved, callInfoService.describeAfterChange(saved, AppointmentCallRelevantChangeEvent.Kind.ENDED));
    }

    /**
     * Move an active appointment to a new time (and optionally a new duration). Status becomes RESCHEDULED when the
     * start time really changes. Behaves exactly like {@link #updateAppointment} with those two fields.
     */
    public AppointmentResponse rescheduleAppointment(Integer appointmentId, RescheduleAppointmentRequest request) {
        if (request == null || request.getScheduledAt() == null) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "scheduledAt: is required");
        }
        CreateAppointmentRequest update = CreateAppointmentRequest.builder()
                .scheduledAt(request.getScheduledAt())
                .durationMinutes(request.getDurationMinutes())
                .build();
        return updateAppointment(appointmentId, update);
    }

    /**
     * Partial edit of an active (SCHEDULED/RESCHEDULED) appointment: time, duration, type, reason, notes, contact
     * phone. Absent or null fields are left unchanged; patient, doctor and facility cannot be changed and a legacy
     * videoConsultationLink is ignored. A new start time sets the status to RESCHEDULED and runs the same slot rules
     * as booking; BookingConstraint beans run only when the slot (start or duration) changes.
     */
    public AppointmentResponse updateAppointment(Integer appointmentId, CreateAppointmentRequest request) {
        if (request == null) {
            throw new BadRequestException("Appointment details are required");
        }
        Appointment appointment = findAppointmentForUpdate(appointmentId);
        requireCanManage(appointment);
        requireNotTerminal(appointment);

        String callerId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        boolean patientSide = isPatientSide(role);

        LocalDateTime currentStart = appointment.getScheduledAt();
        int currentDuration = durationOf(appointment);
        String currentType = appointment.getType();

        int newDuration = request.getDurationMinutes() != null ? request.getDurationMinutes() : currentDuration;
        LocalDateTime requestedStart = request.getScheduledAt() != null
                ? request.getScheduledAt().truncatedTo(ChronoUnit.MINUTES)
                : currentStart;
        boolean startChanged = !requestedStart.equals(currentStart);
        boolean slotChanged = startChanged || newDuration != currentDuration;

        // An unknown type is reported after the slot rules (contract order); the constraints see the current type then.
        String requestedType = request.getType() != null ? tryNormalizeType(request.getType()) : null;
        boolean invalidType = request.getType() != null && requestedType == null;
        String resultingType = requestedType != null ? requestedType : currentType;

        if (slotChanged) {
            if (patientSide && !currentStart.isAfter(now())) {
                throw new BadRequestException("This appointment has already started and can no longer be rescheduled");
            }
            int validatedDuration = validateDuration(newDuration);
            LocalDateTime validatedStart = validateStartTime(requestedStart, validatedDuration, role);
            requireBookableDoctor(appointment.getDoctorSystemId());
            lockAndCheckAvailability(appointment.getDoctorSystemId(), appointment.getPatientSystemId(),
                    validatedStart, validatedDuration, appointment.getAppointmentId());
            runBookingConstraints(new BookingRequestContext(appointment.getDoctorSystemId(),
                    appointment.getPatientSystemId(), validatedStart, validatedDuration, resultingType, role,
                    appointment.getAppointmentId()));
            appointment.setScheduledAt(validatedStart);
            appointment.setDurationMinutes(validatedDuration);
            if (startChanged) {
                appointment.setStatus(RESCHEDULED);
                appointment.setReminderSent(false);
            }
        }

        if (invalidType) {
            throw new BadRequestException(INVALID_TYPE_MESSAGE);
        }
        boolean typeChanged = false;
        if (!Objects.equals(resultingType, currentType)) {
            if (Appointment.TYPE_VIDEO.equals(resultingType)) {
                requireVideoAvailable();
            }
            typeChanged = Appointment.TYPE_VIDEO.equals(resultingType) || Appointment.TYPE_VIDEO.equals(currentType);
            appointment.setType(resultingType);
        }

        if (Appointment.TYPE_PHONE.equals(resultingType)) {
            appointment.setContactPhone(resolveContactPhone(appointment.getPatientSystemId(),
                    request.getContactPhone(), trimToNull(appointment.getContactPhone())));
        } else {
            appointment.setContactPhone(null);
        }

        if (request.getReasonForVisit() != null) {
            appointment.setReasonForVisit(emptyToNull(limitText(request.getReasonForVisit(), "Reason for visit")));
        }
        if (request.getNotes() != null) {
            appointment.setNotes(emptyToNull(limitText(request.getNotes(), "Notes")));
        }

        Appointment saved = slotChanged ? saveSlot(appointment) : appointmentRepository.saveAndFlush(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE, appointmentId, appointment.getPatientSystemId());
        if (startChanged) {
            eventPublisher.publishEvent(new AppointmentRescheduledEvent(saved.getAppointmentId(),
                    saved.getPatientSystemId(), saved.getDoctorSystemId(), callerId, role, currentStart,
                    saved.getScheduledAt()));
        }
        // Type change wins over a time change (the call session is rebuilt from the current time either way).
        AppointmentCallRelevantChangeEvent.Kind callChange = typeChanged
                ? AppointmentCallRelevantChangeEvent.Kind.TYPE_CHANGED
                : startChanged ? AppointmentCallRelevantChangeEvent.Kind.RESCHEDULED
                : slotChanged ? AppointmentCallRelevantChangeEvent.Kind.DURATION_CHANGED : null;
        publishCallChange(saved, callChange);
        return toResponse(saved, callInfoService.describeAfterChange(saved, callChange));
    }

    /**
     * Soft-cancel (status CANCELLED). Allowed for the patient, the appointment's doctor, a caregiver with
     * appointment-management access to the patient, and admins. Patients and caregivers can only cancel
     * appointments that have not started yet. Cancelling a cancelled appointment is a no-op.
     */
    public void cancelAppointment(Integer appointmentId) {
        cancel(findAppointmentForUpdate(appointmentId));
    }

    /**
     * Cancellation by the system (jobs, cascades such as a deactivated account). No caller authorisation. For a
     * terminal appointment, or an active one that has a COMPLETED consultation, the unchanged appointment is
     * returned and nothing is published: this method never throws for a state reason.
     *
     * @param reason one patient-safe sentence without clinical detail; shown to the people notified. Not stored.
     * @throws ResourceNotFoundException when the appointment does not exist
     */
    public AppointmentResponse cancelBySystem(Integer appointmentId, String reason) {
        Appointment appointment = findAppointmentForUpdate(appointmentId);
        if (TERMINAL_STATUSES.contains(appointment.getStatus())
                || appointmentRepository.existsCompletedConsultation(appointment.getAppointmentId())) {
            return toResponse(appointment, callInfoService.describeOne(appointment));
        }
        return applyCancel(appointment, null, null, trimToNull(reason), true);
    }

    /**
     * Consultation completed (AppointmentConsultationListener, inside the consultation's transaction): an
     * appointment that is still active and has started becomes COMPLETED through the normal outcome path
     * (audit row, AppointmentStatusChangedEvent with the consultation's doctor as actor). Anything else, including a
     * repeated event or an appointment that is already COMPLETED, is ignored.
     */
    public void completeFromConsultation(ConsultationCompletedEvent event) {
        if (event == null || event.appointmentId() == null) {
            return;
        }
        Appointment appointment = appointmentRepository.findByIdForUpdate(event.appointmentId()).orElse(null);
        if (appointment == null || !ACTIVE_STATUSES.contains(appointment.getStatus())
                || appointment.getScheduledAt().isAfter(now())) {
            return;
        }
        applyOutcome(appointment, COMPLETED, event.doctorSystemId());
    }

    // ==================== RESPONSES ====================

    /**
     * Responses for these rows with batch lookups (one query each for names, facilities and call sessions). The
     * contact phone of a PHONE appointment is filled only for callers allowed to see it, and is null when no caller is
     * authenticated (jobs).
     */
    @Transactional(readOnly = true)
    public List<AppointmentResponse> toResponses(List<Appointment> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return buildResponses(rows, callInfoService.describeAppointments(rows));
    }

    private AppointmentResponse toResponse(Appointment appointment, CallInfoService.CallInfo call) {
        Map<Integer, CallInfoService.CallInfo> calls = new HashMap<>();
        calls.put(appointment.getAppointmentId(), call != null ? call : CallInfoService.CallInfo.NONE);
        return buildResponses(List.of(appointment), calls).get(0);
    }

    private List<AppointmentResponse> buildResponses(List<Appointment> rows, Map<Integer, CallInfoService.CallInfo> calls) {
        Set<String> people = new LinkedHashSet<>();
        Set<Integer> facilityIds = new LinkedHashSet<>();
        for (Appointment a : rows) {
            people.add(a.getPatientSystemId());
            people.add(a.getDoctorSystemId());
            if (a.getFacilityId() != null) {
                facilityIds.add(a.getFacilityId());
            }
        }
        people.remove(null);
        Map<String, String> names = people.isEmpty() ? Map.of()
                : appointmentRepository.findPersonNames(people).stream()
                .collect(Collectors.toMap(AppointmentRepository.PersonName::getSystemId,
                        AppointmentService::fullName, (x, y) -> x));
        Map<Integer, AppointmentRepository.FacilitySummary> facilities = facilityIds.isEmpty() ? Map.of()
                : appointmentRepository.findFacilitySummaries(facilityIds).stream()
                .collect(Collectors.toMap(AppointmentRepository.FacilitySummary::getFacilityId, Function.identity(),
                        (x, y) -> x));

        Viewer viewer = currentViewer();
        Map<String, Boolean> caregiverCanView = new HashMap<>();
        List<AppointmentResponse> result = new ArrayList<>(rows.size());
        for (Appointment a : rows) {
            CallInfoService.CallInfo call = calls.getOrDefault(a.getAppointmentId(), CallInfoService.CallInfo.NONE);
            AppointmentRepository.FacilitySummary facility = a.getFacilityId() != null ? facilities.get(a.getFacilityId()) : null;
            String doctorName = names.get(a.getDoctorSystemId());
            String phone = a.getContactPhone() != null && canSeeContactPhone(viewer, a, caregiverCanView)
                    ? a.getContactPhone() : null;
            result.add(AppointmentResponse.builder()
                    .appointmentId(a.getAppointmentId())
                    .patientSystemId(a.getPatientSystemId())
                    .patientName(names.get(a.getPatientSystemId()))
                    .doctorSystemId(a.getDoctorSystemId())
                    .doctorName(doctorName != null ? CommunicationLabels.withDoctorTitle(doctorName) : null)
                    .facilityId(a.getFacilityId())
                    .facilityName(facility != null ? facility.getName() : null)
                    .facilityAddress(facility != null ? trimToNull(facility.getAddress()) : null)
                    .scheduledAt(a.getScheduledAt())
                    .durationMinutes(a.getDurationMinutes())
                    .type(a.getType())
                    .status(a.getStatus())
                    .reasonForVisit(a.getReasonForVisit())
                    // Legacy meeting links are never returned.
                    .videoConsultationLink(null)
                    .contactPhone(phone)
                    .reminderSent(a.getReminderSent())
                    .notes(a.getNotes())
                    .createdAt(a.getCreatedAt())
                    .updatedAt(a.getUpdatedAt())
                    .callStatus(call.callStatus())
                    .callJoinRoute(call.callJoinRoute())
                    .joinWindowOpensAt(call.joinWindowOpensAt())
                    .joinWindowClosesAt(call.joinWindowClosesAt())
                    .build());
        }
        return result;
    }

    /** The authenticated caller, or null (jobs, listeners without a request). */
    private record Viewer(String systemId, String role) {
    }

    private Viewer currentViewer() {
        try {
            return new Viewer(authorizationService.currentSystemId(), authorizationService.currentRole());
        } catch (AuthenticationException e) {
            return null;
        }
    }

    /** Appointment's doctor, the patient, a caregiver with APPOINTMENTS on the patient, or an admin. */
    private boolean canSeeContactPhone(Viewer viewer, Appointment a, Map<String, Boolean> caregiverCanView) {
        if (viewer == null || viewer.role() == null) {
            return false;
        }
        return switch (viewer.role()) {
            case AuthorizationService.ADMIN -> true;
            case AuthorizationService.DOCTOR -> viewer.systemId().equals(a.getDoctorSystemId());
            case AuthorizationService.PATIENT -> viewer.systemId().equals(a.getPatientSystemId());
            case AuthorizationService.CAREGIVER -> caregiverCanView.computeIfAbsent(a.getPatientSystemId(),
                    patient -> authorizationService.canAccessPatient(patient, PatientPermission.APPOINTMENTS));
            default -> false;
        };
    }

    private static String fullName(AppointmentRepository.PersonName person) {
        String first = person.getFirstName() != null ? person.getFirstName().trim() : "";
        String last = person.getLastName() != null ? person.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }

    // ==================== STATE CHANGES ====================

    private AppointmentResponse cancel(Appointment appointment) {
        requireCanManage(appointment);
        if (CANCELLED.equals(appointment.getStatus())) {
            return toResponse(appointment, callInfoService.describeOne(appointment));
        }
        requireNotTerminal(appointment);
        String role = authorizationService.currentRole();
        if (isPatientSide(role) && !appointment.getScheduledAt().isAfter(now())) {
            throw new BadRequestException("This appointment has already started and can no longer be cancelled");
        }
        if (appointmentRepository.existsCompletedConsultation(appointment.getAppointmentId())) {
            throw new ConflictException("This appointment has a completed consultation and can no longer be cancelled");
        }
        return applyCancel(appointment, authorizationService.currentSystemId(), role, null, false);
    }

    private AppointmentResponse applyCancel(Appointment appointment, String actorSystemId, String actorRole,
                                            String reason, boolean bySystem) {
        appointment.setStatus(CANCELLED);
        Appointment saved = appointmentRepository.saveAndFlush(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getAppointmentId(), saved.getPatientSystemId());
        eventPublisher.publishEvent(new AppointmentCancelledEvent(saved.getAppointmentId(), saved.getPatientSystemId(),
                saved.getDoctorSystemId(), actorSystemId, actorRole, saved.getScheduledAt(), reason, bySystem));
        publishCallChange(saved, AppointmentCallRelevantChangeEvent.Kind.CANCELLED);
        return toResponse(saved, callInfoService.describeAfterChange(saved, AppointmentCallRelevantChangeEvent.Kind.CANCELLED));
    }

    /**
     * COMPLETED or NO_SHOW: save, audit, AppointmentStatusChangedEvent, and the call change ENDED (a call session
     * that has not gone LIVE is closed BEFORE_COMMIT). The caller has checked every rule.
     */
    private Appointment applyOutcome(Appointment appointment, String status, String actorSystemId) {
        String previous = appointment.getStatus();
        appointment.setStatus(status);
        Appointment saved = appointmentRepository.saveAndFlush(appointment);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getAppointmentId(), saved.getPatientSystemId());
        eventPublisher.publishEvent(new AppointmentStatusChangedEvent(saved.getAppointmentId(),
                saved.getPatientSystemId(), saved.getDoctorSystemId(), actorSystemId, previous, status));
        publishCallChange(saved, AppointmentCallRelevantChangeEvent.Kind.ENDED);
        return saved;
    }

    // ==================== HELPERS: ACCESS ====================

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
            auditService.logAccessDenied(callerId, RESOURCE, appointment.getAppointmentId(),
                    appointment.getPatientSystemId(), null, auditReason);
        } catch (RuntimeException ignored) {
            // The audit write must never mask the denial.
        }
        throw new AuthorizationException(message, RESOURCE, appointment.getAppointmentId());
    }

    private static boolean isPatientSide(String role) {
        return AuthorizationService.PATIENT.equals(role) || AuthorizationService.CAREGIVER.equals(role);
    }

    // ==================== HELPERS: VALIDATION ====================

    private Appointment findAppointment(Integer appointmentId) {
        if (appointmentId == null) {
            throw new BadRequestException("Appointment ID is required");
        }
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
    }

    /** Loads and locks the row so concurrent changes of one appointment apply one after the other. */
    private Appointment findAppointmentForUpdate(Integer appointmentId) {
        if (appointmentId == null) {
            throw new BadRequestException("Appointment ID is required");
        }
        return appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
    }

    private void requireNotTerminal(Appointment appointment) {
        if (TERMINAL_STATUSES.contains(appointment.getStatus())) {
            throw new ConflictException("This appointment is already marked as " + describeStatus(appointment.getStatus())
                    + " and can no longer be changed");
        }
    }

    private void requireVideoAvailable() {
        if (!isVideoCallsAvailable()) {
            throw new ConflictException(ErrorResponse.VIDEO_UNAVAILABLE, VIDEO_UNAVAILABLE_MESSAGE);
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
            throw new BadRequestException("This doctor is not accepting appointments at the moment");
        }
        return doctor;
    }

    private int validateDuration(Integer durationMinutes) {
        if (durationMinutes == null
                || durationMinutes < MIN_DURATION_MINUTES
                || durationMinutes > MAX_DURATION_MINUTES
                || durationMinutes % SLOT_GRANULARITY_MINUTES != 0) {
            throw new BadRequestException("Appointment duration must be between " + MIN_DURATION_MINUTES + " and "
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
            throw new BadRequestException("Scheduled date/time is required");
        }
        LocalDateTime start = requested.truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime now = now();
        if (!start.isAfter(now)) {
            throw new BadRequestException("Appointment cannot be scheduled in the past");
        }
        if (start.isAfter(now.plusDays(maxAdvanceDays))) {
            throw new BadRequestException("Appointments can be booked at most " + maxAdvanceDays + " days in advance");
        }
        if (start.getMinute() % SLOT_GRANULARITY_MINUTES != 0) {
            throw new BadRequestException("Appointments must start on a " + SLOT_GRANULARITY_MINUTES + "-minute boundary");
        }
        if (isPatientSide(role)) {
            if (!allowWeekends && (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                throw new BadRequestException("Appointments cannot be booked on weekends");
            }
            LocalDateTime open = start.toLocalDate().atTime(openHour, 0);
            LocalDateTime close = closeHour >= 24
                    ? start.toLocalDate().plusDays(1).atStartOfDay()
                    : start.toLocalDate().atTime(closeHour, 0);
            if (start.isBefore(open) || start.plusMinutes(durationMinutes).isAfter(close)) {
                throw new BadRequestException(String.format(
                        "Appointments can only be booked between %02d:00 and %02d:00", openHour, closeHour));
            }
        }
        return start;
    }

    /**
     * The number used for a PHONE appointment: the one sent (trimmed), else the one already stored, else the
     * patient's number on file (account phone, then patient profile contact phone). 400 CONTACT_PHONE_REQUIRED when
     * there is none.
     */
    private String resolveContactPhone(String patientSystemId, String requested, String stored) {
        String sent = trimToNull(requested);
        if (sent != null) {
            if (sent.length() > MAX_PHONE_LENGTH) {
                throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, "contactPhone: must be at most 20 characters");
            }
            return sent;
        }
        if (stored != null) {
            return stored;
        }
        return appointmentRepository.findPhoneOnFile(patientSystemId)
                .map(AppointmentService::trimToNull)
                .filter(phone -> phone.length() <= MAX_PHONE_LENGTH)
                .orElseThrow(() -> new BadRequestException(CONTACT_PHONE_REQUIRED, CONTACT_PHONE_REQUIRED_MESSAGE));
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
            throw new ConflictException(SLOT_TAKEN_MESSAGE);
        }
        if (appointmentRepository.countOverlappingForPatient(patientSystemId, start, end, excludeAppointmentId) > 0) {
            throw new ConflictException(PATIENT_BUSY_MESSAGE);
        }
    }

    /** Every BookingConstraint bean, in order, inside the booking transaction (locks held). */
    private void runBookingConstraints(BookingRequestContext context) {
        bookingConstraints.orderedStream().forEach(constraint -> constraint.check(context));
    }

    /** Save + flush so the DB slot index (V13) is checked here and turned into a readable 409. */
    private Appointment saveSlot(Appointment appointment) {
        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : null;
            if (detail != null && detail.contains(SLOT_INDEX_NAME)) {
                throw new ConflictException(SLOT_TAKEN_MESSAGE);
            }
            if (detail != null && detail.contains(PATIENT_SLOT_INDEX_NAME)) {
                throw new ConflictException(PATIENT_BUSY_MESSAGE);
            }
            throw e;
        }
    }

    private String normalizeType(String type, String defaultType) {
        if (trimToNull(type) == null && defaultType != null) {
            return defaultType;
        }
        String normalized = tryNormalizeType(type);
        if (normalized == null) {
            throw new BadRequestException(INVALID_TYPE_MESSAGE);
        }
        return normalized;
    }

    /** IN_PERSON, VIDEO or PHONE (trimmed, case-insensitive, '-' and ' ' read as '_'); null when not one of them. */
    private static String tryNormalizeType(String type) {
        String value = trimToNull(type);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return VALID_TYPES.contains(normalized) ? normalized : null;
    }

    private static String normalizeStatus(String status) {
        String value = trimToNull(status);
        String normalized = value == null ? null : value.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized == null || !VALID_STATUSES.contains(normalized)) {
            throw new BadRequestException(INVALID_STATUS_MESSAGE);
        }
        return normalized;
    }

    /** Status filter values: comma-separated and/or repeated; empty means every status. */
    private static Set<String> parseStatuses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> statuses = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                if (trimToNull(part) != null) {
                    statuses.add(normalizeStatus(part));
                }
            }
        }
        return statuses.isEmpty() ? null : statuses;
    }

    private static Sort.Direction parseOrder(String order) {
        String value = trimToNull(order);
        if (value == null || "desc".equalsIgnoreCase(value)) {
            return Sort.Direction.DESC;
        }
        if ("asc".equalsIgnoreCase(value)) {
            return Sort.Direction.ASC;
        }
        throw new BadRequestException("Invalid value for parameter 'order'");
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Direction.DESC, "scheduledAt").and(Sort.by(Sort.Direction.DESC, "appointmentId"));
    }

    private static Sort soonestFirst() {
        return Sort.by(Sort.Direction.ASC, "scheduledAt").and(Sort.by(Sort.Direction.ASC, "appointmentId"));
    }

    /**
     * Database-side filter. Null arguments are not applied.
     *
     * @param from           inclusive lower bound on scheduledAt
     * @param to             exclusive upper bound on scheduledAt
     * @param startsAfter    exclusive lower bound on scheduledAt (upcoming lists)
     */
    private static Specification<Appointment> filter(String patientSystemId, String doctorSystemId,
                                                     Collection<String> statuses, LocalDateTime from,
                                                     LocalDateTime to, LocalDateTime startsAfter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (patientSystemId != null) {
                predicates.add(cb.equal(root.get("patientSystemId"), patientSystemId));
            }
            if (doctorSystemId != null) {
                predicates.add(cb.equal(root.get("doctorSystemId"), doctorSystemId));
            }
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("scheduledAt"), to));
            }
            if (startsAfter != null) {
                predicates.add(cb.greaterThan(root.get("scheduledAt"), startsAfter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String limitText(String value, String field) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw new BadRequestException(field + " must be at most " + MAX_TEXT_LENGTH + " characters");
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
        Specification<Appointment> specification;
        if (AuthorizationService.PATIENT.equals(role)) {
            specification = filter(userSystemId, null, Set.of(status), null, null, null);
        } else if (AuthorizationService.DOCTOR.equals(role)) {
            specification = filter(null, userSystemId, Set.of(status), null, null, null);
        } else {
            throw unsupportedRole();
        }
        return toResponses(appointmentRepository.findAll(specification, newestFirst()));
    }

    private static AuthorizationException unsupportedRole() {
        return new AuthorizationException("Only patients and doctors have their own appointment lists");
    }

    private static int durationOf(Appointment appointment) {
        return appointment.getDurationMinutes() != null ? appointment.getDurationMinutes() : DEFAULT_DURATION_MINUTES;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(schedulingTimeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Clinic wall-clock now. */
    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(zone()));
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

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Call sessions are synced BEFORE_COMMIT of this transaction (calls.listener.AppointmentCallListener). */
    private void publishCallChange(Appointment appointment, AppointmentCallRelevantChangeEvent.Kind kind) {
        if (kind != null) {
            eventPublisher.publishEvent(new AppointmentCallRelevantChangeEvent(appointment.getAppointmentId(), kind));
        }
    }
}
