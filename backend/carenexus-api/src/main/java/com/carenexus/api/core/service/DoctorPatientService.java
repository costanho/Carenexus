package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.calls.service.CallInfoService;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
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
import com.carenexus.api.core.dto.request.UpdateHealthStatusRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.DoctorPatientHealthStatusResponse;
import com.carenexus.api.core.dto.response.DoctorPatientOptionResponse;
import com.carenexus.api.core.dto.response.DoctorPatientPageResponse;
import com.carenexus.api.core.dto.response.DoctorPatientResponse;
import com.carenexus.api.core.dto.response.DoctorPatientSummaryResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository.AppointmentStats;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository.Filter;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository.HealthStatusState;
import com.carenexus.api.core.repository.DoctorPatientQueryRepository.PatientRow;
import com.carenexus.api.core.repository.DoctorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A doctor's patients.
 *
 * "A doctor's patients" is exactly the relationship of {@link RelationshipService#getPatientsForDoctor}: an active
 * care-team row with a doctor role, OR an appointment with the doctor that is not CANCELLED, OR a consultation with
 * the doctor. The lists evaluate it in the database ({@link DoctorPatientQueryRepository}) so filters, ordering and
 * paging run there; the detail authorises with {@code requirePatientAccess(patientSystemId, PROFILE)}, which uses
 * the same relationship. Every value derived from appointments only counts this doctor's appointments.
 *
 * The lists and the detail are for DOCTOR callers only (an administrator has no patient list); the health-status
 * change also accepts an ADMIN. Identity always comes from the JWT.
 *
 * Audit: list reads write one READ / PROFILE row per patient returned; the detail one READ / PROFILE row; a stored
 * health-status change one UPDATE / PROFILE row. The options list (ids and names only) is not audited.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DoctorPatientService {

    public static final String RESOURCE = "PROFILE";

    /** Rows of the deprecated array endpoint. */
    public static final int LEGACY_LIST_CAP = 200;
    /** Rows of the options endpoint. */
    public static final int OPTIONS_CAP = 500;
    /** Recent appointments per patient in the legacy list and the detail. */
    public static final int RECENT_APPOINTMENTS = 5;

    public static final String STABLE = "STABLE";
    public static final String MONITOR = "MONITOR";
    public static final String CRITICAL = "CRITICAL";
    private static final Set<String> HEALTH_STATUSES = Set.of(STABLE, MONITOR, CRITICAL);
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");

    private static final DateTimeFormatter DEDUPE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DoctorPatientQueryRepository queryRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;
    private final CallInfoService callInfoService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    // ==================== LISTS ====================

    /**
     * The calling doctor's patients as a bare list (deprecated endpoint): ordered by last name, first name, id;
     * at most {@link #LEGACY_LIST_CAP} rows; recent appointments filled.
     */
    @Transactional
    public List<DoctorPatientResponse> getMyPatients() {
        return getDoctorPatients(authorizationService.currentSystemId());
    }

    /**
     * Same as {@link #getMyPatients()}; {@code doctorSystemId} must be the calling doctor.
     */
    @Transactional
    public List<DoctorPatientResponse> getDoctorPatients(String doctorSystemId) {
        String doctor = requireCallingDoctor(doctorSystemId);
        LocalDateTime now = clinicNow();
        List<PatientRow> rows = queryRepository.findRelatedPatients(doctor, Filter.NONE,
                DoctorPatientQueryRepository.Sort.NAME, now, LEGACY_LIST_CAP, 0);
        List<DoctorPatientResponse> result = buildRows(doctor, rows, now, true);
        auditService.logListRead(RESOURCE, result, DoctorPatientResponse::getPatientSystemId);
        return result;
    }

    /**
     * One page of the calling doctor's patients, filtered and ordered in the database, plus the health-status
     * counts of the doctor's whole patient set.
     *
     * @param query        case-insensitive "contains" on names, email and patient id; plain "contains" on phone
     * @param healthStatus STABLE, MONITOR, CRITICAL; blank or ALL = no filter
     * @param gender       MALE, FEMALE, OTHER; blank or ALL = no filter
     * @param sort         name (default), nextAppointment, healthStatus or lastVisit
     */
    @Transactional
    public DoctorPatientPageResponse searchMyPatients(String query, String healthStatus, String gender, String sort,
                                                      int page, int size) {
        String doctor = requireCallingDoctor(null);
        Filter filter = new Filter(blankToNull(query), parseHealthStatusFilter(healthStatus), parseGenderFilter(gender));
        DoctorPatientQueryRepository.Sort order = parseSort(sort);
        Pageable pageable = PageParams.of(page, size, null);
        LocalDateTime now = clinicNow();

        long total = queryRepository.countRelatedPatients(doctor, filter);
        List<PatientRow> rows = total == 0 ? List.of()
                : queryRepository.findRelatedPatients(doctor, filter, order, now, pageable.getPageSize(),
                pageable.getOffset());
        List<DoctorPatientResponse> content = buildRows(doctor, rows, now, false);
        DoctorPatientQueryRepository.StatusCounts counts = queryRepository.countByHealthStatus(doctor);
        int totalPages = (int) ((total + pageable.getPageSize() - 1) / pageable.getPageSize());

        auditService.logListRead(RESOURCE, content, DoctorPatientResponse::getPatientSystemId);
        return new DoctorPatientPageResponse(content, pageable.getPageNumber(), pageable.getPageSize(), total,
                totalPages, new DoctorPatientSummaryResponse(counts.total(), counts.stable(), counts.monitor(),
                counts.critical()));
    }

    /**
     * Ids and names of the calling doctor's patients for pickers and name lookups, ordered by name, at most
     * {@link #OPTIONS_CAP} rows. Not audited (no clinical field).
     */
    public List<DoctorPatientOptionResponse> getMyPatientOptions(String query) {
        String doctor = requireCallingDoctor(null);
        return queryRepository.findOptions(doctor, blankToNull(query), OPTIONS_CAP).stream()
                .map(row -> DoctorPatientOptionResponse.of(row.patientSystemId(), row.firstName(), row.lastName()))
                .toList();
    }

    // ==================== DETAIL ====================

    /** One patient of the calling doctor, with recent appointments and the latest health-status note. */
    @Transactional
    public DoctorPatientResponse getMyPatientDetail(String patientSystemId) {
        return getDoctorPatientDetail(authorizationService.currentSystemId(), patientSystemId);
    }

    /**
     * Same as {@link #getMyPatientDetail}; {@code doctorSystemId} must be the calling doctor. A doctor who is not
     * related to the patient (including one linked only by a cancelled appointment, and any unknown patient id)
     * gets 403 with an ACCESS_DENIED audit row.
     */
    @Transactional
    public DoctorPatientResponse getDoctorPatientDetail(String doctorSystemId, String patientSystemId) {
        String doctor = requireCallingDoctor(doctorSystemId);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE);

        PatientRow row = queryRepository.findPatient(patientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        LocalDateTime now = clinicNow();
        DoctorPatientResponse response = buildRows(doctor, List.of(row), now, true).get(0);
        queryRepository.findHealthStatus(patientSystemId, false).ifPresent(state -> {
            response.setHealthStatusNote(state.note());
            response.setHealthStatusUpdatedAt(state.updatedAt());
            response.setHealthStatusUpdatedBySystemId(state.updatedBySystemId());
            response.setHealthStatusUpdatedByName(updaterName(state.updatedBySystemId(), state.updaterFirstName(),
                    state.updaterLastName(), state.updaterRole()));
        });

        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return response;
    }

    // ==================== HEALTH STATUS ====================

    /**
     * Sets a patient's health status (and an optional note). Caller: a DOCTOR related to the patient, or an ADMIN.
     *
     * - status differs: stored with the note (or none), audited UPDATE / PROFILE; a change INTO CRITICAL notifies
     *   the patient and every caregiver holding RECORDS (category CRITICAL, priority HIGH, no clinical wording);
     * - same status with a note: the note is replaced and audited, nobody is notified;
     * - same status without a note: nothing happens.
     */
    @Transactional
    public DoctorPatientHealthStatusResponse updateHealthStatus(String patientSystemId, UpdateHealthStatusRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.PROFILE);
        String caller = authorizationService.currentSystemId();

        String newStatus = request == null ? null : request.normalizedHealthStatus();
        if (newStatus == null || !HEALTH_STATUSES.contains(newStatus)) {
            // Bean Validation rejects these first; kept for direct service callers.
            throw new BadRequestException("Health status must be STABLE, MONITOR or CRITICAL");
        }
        String note = request.getNote();

        HealthStatusState current = queryRepository.findHealthStatus(patientSystemId, true)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
        String previous = current.healthStatus();
        boolean changed = !newStatus.equals(previous);

        if (!changed && note == null) {
            return new DoctorPatientHealthStatusResponse(patientSystemId, previous, previous, false, current.note(),
                    current.updatedAt(), current.updatedBySystemId(), updaterName(current.updatedBySystemId(),
                    current.updaterFirstName(), current.updaterLastName(), current.updaterRole()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        queryRepository.updateHealthStatus(patientSystemId, newStatus, note, now, caller);
        auditService.logClinicalAccess("UPDATE", RESOURCE, null, patientSystemId);

        if (changed && CRITICAL.equals(newStatus)) {
            notifyPatientCircleOfCriticalStatus(patientSystemId, caller, now);
        }

        User updater = userRepository.findBySystemId(caller).orElse(null);
        String updaterName = updater == null ? null
                : updaterName(caller, updater.getFirstName(), updater.getLastName(), updater.getRole());
        return new DoctorPatientHealthStatusResponse(patientSystemId, newStatus, previous, changed, note, now, caller,
                updaterName);
    }

    private void notifyPatientCircleOfCriticalStatus(String patientSystemId, String actorSystemId, LocalDateTime changedAt) {
        String dedupeKey = "HEALTH_STATUS_CRITICAL:" + patientSystemId + ":" + changedAt.format(DEDUPE_TIME);
        NotificationCommand.Builder template = NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.CRITICAL)
                .priority(NotificationPriority.HIGH)
                .title("Health status update")
                .target(NotificationTarget.CARE_TEAM)
                .resourceType(RESOURCE)
                .resourceId(null)
                .patientSystemId(patientSystemId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey);

        notificationPublisher.publish(template
                .recipientSystemId(patientSystemId)
                .message("Your care team updated your health status. Please contact your care team.")
                .build());

        List<String> caregivers = authorizationService.caregiversWithPermission(patientSystemId, PatientPermission.RECORDS);
        if (caregivers.isEmpty()) {
            return;
        }
        String patientName = userRepository.findBySystemId(patientSystemId)
                .map(user -> fullName(user.getFirstName(), user.getLastName()))
                .orElse("your dependent");
        String caregiverText = "The care team updated the health status of " + patientName
                + ". Please contact the care team.";
        for (String caregiver : caregivers) {
            notificationPublisher.publish(template
                    .recipientSystemId(caregiver)
                    .message(caregiverText)
                    .build());
        }
    }

    // ==================== ROW BUILDING (batch) ====================

    private List<DoctorPatientResponse> buildRows(String doctorSystemId, List<PatientRow> rows, LocalDateTime now,
                                                  boolean withRecentAppointments) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> ids = rows.stream().map(PatientRow::patientSystemId).toList();
        Map<String, AppointmentStats> stats = queryRepository.appointmentStats(doctorSystemId, ids, now);
        Map<String, List<AppointmentResponse>> recent = withRecentAppointments
                ? recentAppointments(doctorSystemId, rows) : Map.of();

        List<DoctorPatientResponse> result = new ArrayList<>(rows.size());
        for (PatientRow row : rows) {
            AppointmentStats s = stats.getOrDefault(row.patientSystemId(), AppointmentStats.NONE);
            result.add(DoctorPatientResponse.builder()
                    .patientSystemId(row.patientSystemId())
                    .userSystemId(row.patientSystemId())
                    .firstName(row.firstName())
                    .lastName(row.lastName())
                    .email(row.email())
                    .phone(row.phone())
                    .dateOfBirth(row.dateOfBirth())
                    .gender(row.gender())
                    .bloodType(row.bloodType())
                    .allergies(row.allergies())
                    .chronicConditions(row.chronicConditions())
                    .healthStatus(row.healthStatus())
                    .totalAppointments(s.total())
                    .upcomingAppointmentCount(s.upcoming())
                    .completedAppointmentCount(s.completed())
                    .cancelledAppointmentCount(s.cancelled())
                    .noShowCount(s.noShow())
                    .rescheduledCount(s.rescheduled())
                    .completedVisits(s.completed())
                    .nextAppointmentAt(s.nextAppointmentAt())
                    .nextAppointmentId(s.nextAppointmentId())
                    .nextAppointmentType(s.nextAppointmentType())
                    .lastVisitAt(s.lastVisitAt())
                    .lastAppointmentDate(s.lastVisitAt())
                    .recentAppointments(withRecentAppointments
                            ? recent.getOrDefault(row.patientSystemId(), new ArrayList<>()) : null)
                    .patientCreatedAt(row.createdAt())
                    .build());
        }
        return result;
    }

    /** Up to five most recent appointments per patient, from one window query and one IN load. */
    private Map<String, List<AppointmentResponse>> recentAppointments(String doctorSystemId, List<PatientRow> rows) {
        Map<String, String> names = new HashMap<>();
        rows.forEach(row -> names.put(row.patientSystemId(), fullName(row.firstName(), row.lastName())));

        List<Integer> ids = queryRepository.recentAppointmentIds(doctorSystemId, names.keySet(), RECENT_APPOINTMENTS);
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Appointment> appointments = new ArrayList<>(appointmentRepository.findAllById(ids));
        appointments.sort(Comparator.comparing(Appointment::getScheduledAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Appointment::getAppointmentId, Comparator.reverseOrder()));
        Map<Integer, CallInfoService.CallInfo> calls = callInfoService.describeAppointments(appointments);

        Map<String, List<AppointmentResponse>> byPatient = new HashMap<>();
        for (Appointment appointment : appointments) {
            byPatient.computeIfAbsent(appointment.getPatientSystemId(), key -> new ArrayList<>())
                    .add(toAppointmentResponse(appointment, names.get(appointment.getPatientSystemId()),
                            calls.getOrDefault(appointment.getAppointmentId(), CallInfoService.CallInfo.NONE)));
        }
        return byPatient;
    }

    /**
     * Appointment row of this slice's own mapper: doctorName, facilityName, facilityAddress and contactPhone are not
     * filled here (see the HTTP contract); patientName is.
     */
    private AppointmentResponse toAppointmentResponse(Appointment appointment, String patientName,
                                                      CallInfoService.CallInfo call) {
        return AppointmentResponse.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(appointment.getPatientSystemId())
                .patientName(patientName)
                .doctorSystemId(appointment.getDoctorSystemId())
                .facilityId(appointment.getFacilityId())
                .scheduledAt(appointment.getScheduledAt())
                .durationMinutes(appointment.getDurationMinutes())
                .type(appointment.getType())
                .status(appointment.getStatus())
                .reasonForVisit(appointment.getReasonForVisit())
                // Legacy meeting links are never returned; calls use callJoinRoute.
                .videoConsultationLink(null)
                .reminderSent(appointment.getReminderSent())
                .notes(appointment.getNotes())
                .createdAt(appointment.getCreatedAt())
                .updatedAt(appointment.getUpdatedAt())
                .callStatus(call.callStatus())
                .callJoinRoute(call.callJoinRoute())
                .joinWindowOpensAt(call.joinWindowOpensAt())
                .joinWindowClosesAt(call.joinWindowClosesAt())
                .build();
    }

    // ==================== HELPERS ====================

    /**
     * The caller must be a DOCTOR (403 otherwise, ADMIN included) with a clinician row (404 "Doctor not found").
     * When {@code doctorSystemId} is given it must be the caller.
     */
    private String requireCallingDoctor(String doctorSystemId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        if (doctorSystemId != null && !doctorSystemId.equals(caller)) {
            throw new AuthorizationException("You can only access your own doctor data");
        }
        if (doctorRepository.findBySystemId(caller).isEmpty()) {
            throw new ResourceNotFoundException("Doctor not found");
        }
        return caller;
    }

    private LocalDateTime clinicNow() {
        return LocalDateTime.now(clock);
    }

    private static String parseHealthStatusFilter(String value) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            return null;
        }
        if (!HEALTH_STATUSES.contains(normalized)) {
            throw new BadRequestException("Invalid health status. Must be STABLE, MONITOR or CRITICAL");
        }
        return normalized;
    }

    private static String parseGenderFilter(String value) {
        String normalized = normalizeFilter(value);
        if (normalized == null) {
            return null;
        }
        if (!GENDERS.contains(normalized)) {
            throw new BadRequestException("Invalid gender. Must be MALE, FEMALE or OTHER");
        }
        return normalized;
    }

    /** Upper-cased trimmed value; null for blank or ALL. */
    private static String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "ALL".equals(normalized) ? null : normalized;
    }

    private static DoctorPatientQueryRepository.Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DoctorPatientQueryRepository.Sort.NAME;
        }
        return switch (sort.trim()) {
            case "name" -> DoctorPatientQueryRepository.Sort.NAME;
            case "nextAppointment" -> DoctorPatientQueryRepository.Sort.NEXT_APPOINTMENT;
            case "healthStatus" -> DoctorPatientQueryRepository.Sort.HEALTH_STATUS;
            case "lastVisit" -> DoctorPatientQueryRepository.Sort.LAST_VISIT;
            default -> throw new BadRequestException(
                    "Invalid sort. Must be name, nextAppointment, healthStatus or lastVisit");
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String fullName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        return (first + " " + last).trim();
    }

    /** "Dr. First Last" for a doctor (no doubled title), "First Last" for anyone else; null when unknown. */
    static String updaterName(String systemId, String firstName, String lastName, String role) {
        if (systemId == null || (firstName == null && lastName == null)) {
            return null;
        }
        String name = fullName(firstName, lastName);
        if (AuthorizationService.DOCTOR.equalsIgnoreCase(role)) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean titled = lower.startsWith("dr.") || lower.startsWith("dr ");
            return titled ? name : "Dr. " + name;
        }
        return name;
    }
}
