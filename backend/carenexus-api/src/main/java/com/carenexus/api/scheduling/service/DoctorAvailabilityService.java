package com.carenexus.api.scheduling.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.scheduling.config.ClinicSchedule;
import com.carenexus.api.scheduling.dto.request.CreateTimeOffRequest;
import com.carenexus.api.scheduling.dto.request.SlotLength;
import com.carenexus.api.scheduling.dto.request.UpdateDoctorAvailabilityRequest;
import com.carenexus.api.scheduling.dto.response.AvailabilityBlockResponse;
import com.carenexus.api.scheduling.dto.response.DoctorAvailabilityResponse;
import com.carenexus.api.scheduling.dto.response.DoctorSlotResponse;
import com.carenexus.api.scheduling.dto.response.DoctorSlotsResponse;
import com.carenexus.api.scheduling.dto.response.TimeOffResponse;
import com.carenexus.api.scheduling.model.DoctorAvailability;
import com.carenexus.api.scheduling.model.DoctorTimeOff;
import com.carenexus.api.scheduling.repository.DoctorAvailabilityRepository;
import com.carenexus.api.scheduling.repository.DoctorTimeOffRepository;
import com.carenexus.api.scheduling.repository.SchedulingAppointmentRepository;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Block;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Candidate;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Interval;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Doctor availability: the weekly working-hours template, time-off ranges and the free-slot computation.
 *
 * - Template and time off belong to the calling DOCTOR only (no doctor id is ever taken from the request); an
 *   ADMIN is refused here because an administrator has no hours of their own.
 * - A doctor with at least one stored block (active or not) books by the template ({@code TEMPLATE}); a doctor with
 *   none books by clinic hours ({@code CLINIC_HOURS}, 30-minute slots).
 * - Slots and the booking constraint share {@link #workingHours(String, LocalDate)}, so both apply the same windows.
 * - Nothing here changes or cancels appointments, and nothing reads or returns patient data.
 *
 * Audit (resource AVAILABILITY, actor = target = the doctor): AVAILABILITY_UPDATE, TIME_OFF_CREATE, TIME_OFF_DELETE.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DoctorAvailabilityService {

    static final String RESOURCE = "AVAILABILITY";

    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    public static final String SOURCE_CLINIC_HOURS = "CLINIC_HOURS";

    static final String ACTION_AVAILABILITY_UPDATE = "AVAILABILITY_UPDATE";
    static final String ACTION_TIME_OFF_CREATE = "TIME_OFF_CREATE";
    static final String ACTION_TIME_OFF_DELETE = "TIME_OFF_DELETE";

    static final int MAX_BLOCKS_PER_DAY = 6;
    static final int MAX_TIME_OFF_DAYS = 366;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Sort TIME_OFF_ORDER = Sort.by(Sort.Order.asc("startsAt"), Sort.Order.asc("timeOffId"));

    private final DoctorAvailabilityRepository availabilityRepository;
    private final DoctorTimeOffRepository timeOffRepository;
    private final SchedulingAppointmentRepository appointmentQueries;
    private final ClinicSchedule clinic;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** The working hours of one doctor on one clinic day. */
    public record WorkingHours(String source, int slotMinutes, List<Window> windows) {

        public boolean fromTemplate() {
            return SOURCE_TEMPLATE.equals(source);
        }
    }

    // ==================== WEEKLY TEMPLATE ====================

    /** The caller's weekly template; a doctor without stored blocks gets {@code CLINIC_HOURS} and no blocks. */
    @Transactional(readOnly = true)
    public DoctorAvailabilityResponse getMyAvailability() {
        String doctorSystemId = requireDoctor();
        return toAvailabilityResponse(doctorSystemId, loadTemplate(doctorSystemId));
    }

    /**
     * Replaces the caller's whole template with the blocks of the request (all with the request's slot length).
     * An empty list removes the template. Existing appointments are never touched. Last write wins.
     */
    public DoctorAvailabilityResponse replaceMyAvailability(UpdateDoctorAvailabilityRequest request) {
        String doctorSystemId = requireDoctor();
        if (request == null) {
            throw invalid("blocks", "is required");
        }
        if (request.getSlotMinutes() == null) {
            throw invalid("slotMinutes", "is required");
        }
        int slotMinutes = request.getSlotMinutes();
        if (!SlotLength.Validator.allowed(slotMinutes)) {
            throw invalid("slotMinutes", SlotLength.MESSAGE);
        }
        List<UpdateDoctorAvailabilityRequest.Block> requested = request.getBlocks();
        if (requested == null) {
            throw invalid("blocks", "is required");
        }
        if (requested.size() > UpdateDoctorAvailabilityRequest.MAX_BLOCKS) {
            throw invalid("blocks", "must contain at most 42 blocks");
        }

        List<ParsedBlock> blocks = new ArrayList<>(requested.size());
        for (int i = 0; i < requested.size(); i++) {
            blocks.add(validateBlock(i, requested.get(i), slotMinutes));
        }
        validateDays(blocks);

        availabilityRepository.lockTemplate(doctorSystemId);
        availabilityRepository.deleteAllOfDoctor(doctorSystemId);
        LocalDateTime now = clinic.now().truncatedTo(ChronoUnit.MICROS);
        List<DoctorAvailability> rows = blocks.stream()
                .map(b -> DoctorAvailability.builder()
                        .doctorSystemId(doctorSystemId)
                        .dayOfWeek(b.dayOfWeek())
                        .startTime(b.start())
                        .endTime(b.end())
                        .slotMinutes(slotMinutes)
                        .isActive(b.active())
                        .createdAt(now)
                        .updatedAt(now)
                        .build())
                .toList();
        availabilityRepository.saveAll(rows);
        availabilityRepository.flush();

        auditService.logAccountEvent(doctorSystemId, ACTION_AVAILABILITY_UPDATE, RESOURCE, doctorSystemId, "SUCCESS");
        return toAvailabilityResponse(doctorSystemId, loadTemplate(doctorSystemId));
    }

    private record ParsedBlock(int index, int dayOfWeek, LocalTime start, LocalTime end, boolean active) {

        int startMinute() {
            return AvailabilityCalculator.minuteOfDay(start);
        }

        int endMinute() {
            return AvailabilityCalculator.minuteOfDay(end);
        }
    }

    /** Service rules 1 to 6 for one block, in the contract's order. */
    private ParsedBlock validateBlock(int i, UpdateDoctorAvailabilityRequest.Block block, int slotMinutes) {
        String prefix = "blocks[" + i + "]";
        if (block == null) {
            throw invalid(prefix, "is required");
        }
        if (block.getDayOfWeek() == null) {
            throw invalid(prefix + ".dayOfWeek", "is required");
        }
        int day = block.getDayOfWeek();
        if (day < 1 || day > 7) {
            throw invalid(prefix + ".dayOfWeek", "must be between 1 (Monday) and 7 (Sunday)");
        }
        LocalTime start = parseTime(prefix + ".startTime", block.getStartTime());
        LocalTime end = parseTime(prefix + ".endTime", block.getEndTime());

        // Rule 1: 5-minute boundaries.
        if (start.getMinute() % 5 != 0) {
            throw invalid(prefix + ".startTime", "must be on a 5-minute boundary");
        }
        if (end.getMinute() % 5 != 0) {
            throw invalid(prefix + ".endTime", "must be on a 5-minute boundary");
        }
        // Rule 2: end after start (a block cannot cross midnight).
        if (!end.isAfter(start)) {
            throw invalid(prefix + ".endTime", "must be after startTime");
        }
        int startMinute = AvailabilityCalculator.minuteOfDay(start);
        int endMinute = AvailabilityCalculator.minuteOfDay(end);
        // Rule 3: at least one slot long.
        if (endMinute - startMinute < slotMinutes) {
            throw invalid(prefix + ".endTime", "block must be at least as long as the slot length");
        }
        // Rule 4: weekends closed.
        if (!clinic.isOpenOn(DayOfWeek.of(day))) {
            throw invalid(prefix + ".dayOfWeek", "the clinic is closed on weekends");
        }
        // Rule 5: not before opening.
        if (startMinute < clinic.openMinute()) {
            throw invalid(prefix + ".startTime",
                    String.format("must not be before the clinic opens at %02d:00", clinic.openHour()));
        }
        // Rule 6: not after closing.
        if (endMinute > clinic.closeMinute()) {
            throw invalid(prefix + ".endTime",
                    String.format("must not be after the clinic closes at %02d:00", clinic.closeHour()));
        }
        boolean active = block.getIsActive() == null || block.getIsActive();
        return new ParsedBlock(i, day, start, end, active);
    }

    /** Rule 7 (at most 6 blocks per weekday), then rule 8 (no overlap on one weekday, whatever isActive says). */
    private static void validateDays(List<ParsedBlock> blocks) {
        Map<Integer, Integer> perDay = new HashMap<>();
        for (ParsedBlock b : blocks) {
            if (perDay.merge(b.dayOfWeek(), 1, Integer::sum) > MAX_BLOCKS_PER_DAY) {
                throw invalid("blocks", "at most 6 blocks per day");
            }
        }
        for (int j = 1; j < blocks.size(); j++) {
            ParsedBlock later = blocks.get(j);
            for (int i = 0; i < j; i++) {
                ParsedBlock earlier = blocks.get(i);
                if (earlier.dayOfWeek() == later.dayOfWeek()
                        && earlier.startMinute() < later.endMinute()
                        && later.startMinute() < earlier.endMinute()) {
                    throw invalid("blocks[" + j + "].startTime", "overlaps another block on the same day");
                }
            }
        }
    }

    private static LocalTime parseTime(String field, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "is required");
        }
        if (!value.matches(com.carenexus.api.common.dto.ValidationPatterns.TIME_HH_MM)) {
            throw invalid(field, "must be a time in HH:mm format");
        }
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException e) {
            throw invalid(field, "must be a time in HH:mm format");
        }
    }

    private List<DoctorAvailability> loadTemplate(String doctorSystemId) {
        return availabilityRepository.findByDoctorSystemIdOrderByDayOfWeekAscStartTimeAscAvailabilityIdAsc(doctorSystemId);
    }

    private DoctorAvailabilityResponse toAvailabilityResponse(String doctorSystemId, List<DoctorAvailability> rows) {
        if (rows.isEmpty()) {
            return new DoctorAvailabilityResponse(doctorSystemId, clinic.zone().getId(), SOURCE_CLINIC_HOURS,
                    ClinicSchedule.CLINIC_HOURS_SLOT_MINUTES, List.of());
        }
        List<AvailabilityBlockResponse> blocks = rows.stream()
                .map(r -> new AvailabilityBlockResponse(r.getDayOfWeek(), r.getStartTime().format(HH_MM),
                        r.getEndTime().format(HH_MM), Boolean.TRUE.equals(r.getIsActive())))
                .toList();
        return new DoctorAvailabilityResponse(doctorSystemId, clinic.zone().getId(), SOURCE_TEMPLATE,
                templateSlotMinutes(rows), blocks);
    }

    /** All rows of one doctor carry the same slot length (PUT writes it to each); read it from the first. */
    private static int templateSlotMinutes(List<DoctorAvailability> rows) {
        Integer value = rows.get(0).getSlotMinutes();
        return value != null && SlotLength.Validator.allowed(value) ? value : ClinicSchedule.CLINIC_HOURS_SLOT_MINUTES;
    }

    // ==================== TIME OFF ====================

    /**
     * The caller's time-off ranges, soonest first ({@code startsAt}, then id). Without {@code includePast} only
     * ranges that have not ended yet are returned. {@code conflictingAppointments} is counted for the page in one query.
     */
    @Transactional(readOnly = true)
    public PageResponse<TimeOffResponse> listMyTimeOff(boolean includePast, Integer page, Integer size) {
        String doctorSystemId = requireDoctor();
        Pageable pageable = PageParams.of(page, size, TIME_OFF_ORDER);
        Page<DoctorTimeOff> rows = includePast
                ? timeOffRepository.findByDoctorSystemId(doctorSystemId, pageable)
                : timeOffRepository.findByDoctorSystemIdAndEndsAtAfter(doctorSystemId, clinic.now(), pageable);
        Map<Integer, Integer> conflicts = conflictCounts(rows.getContent().stream().map(DoctorTimeOff::getTimeOffId).toList());
        return PageResponse.of(rows, t -> toTimeOffResponse(t, conflicts.getOrDefault(t.getTimeOffId(), 0)));
    }

    /**
     * Adds a time-off range for the caller. Seconds are dropped; the range must end after it starts, end in the
     * future and last at most 366 days. Appointments inside it stay booked (reported in conflictingAppointments).
     */
    public TimeOffResponse createTimeOff(CreateTimeOffRequest request) {
        String doctorSystemId = requireDoctor();
        if (request == null || request.getStartsAt() == null) {
            throw invalid("startsAt", "is required");
        }
        if (request.getEndsAt() == null) {
            throw invalid("endsAt", "is required");
        }
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (reason != null && reason.isEmpty()) {
            reason = null;
        }
        if (reason != null && reason.length() > CreateTimeOffRequest.MAX_REASON_LENGTH) {
            throw invalid("reason", "must be at most 200 characters");
        }
        LocalDateTime startsAt = request.getStartsAt().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime endsAt = request.getEndsAt().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime now = clinic.now();
        if (!endsAt.isAfter(startsAt)) {
            throw invalid("endsAt", "must be after startsAt");
        }
        if (!endsAt.isAfter(now)) {
            throw invalid("endsAt", "must be in the future");
        }
        if (Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(MAX_TIME_OFF_DAYS)) > 0) {
            throw invalid("endsAt", "time off can be at most 366 days long");
        }

        DoctorTimeOff saved = timeOffRepository.saveAndFlush(DoctorTimeOff.builder()
                .doctorSystemId(doctorSystemId)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .reason(reason)
                .createdAt(now.truncatedTo(ChronoUnit.MICROS))
                .build());
        auditService.logAccountEvent(doctorSystemId, ACTION_TIME_OFF_CREATE, RESOURCE, doctorSystemId, "SUCCESS");
        int conflicts = conflictCounts(List.of(saved.getTimeOffId())).getOrDefault(saved.getTimeOffId(), 0);
        return toTimeOffResponse(saved, conflicts);
    }

    /** Removes one of the caller's ranges (hard delete). Another doctor's row is reported as not found. */
    public void deleteTimeOff(Integer timeOffId) {
        String doctorSystemId = requireDoctor();
        DoctorTimeOff row = timeOffId == null ? null
                : timeOffRepository.findByTimeOffIdAndDoctorSystemId(timeOffId, doctorSystemId).orElse(null);
        if (row == null) {
            throw new ResourceNotFoundException("Time off not found");
        }
        timeOffRepository.delete(row);
        timeOffRepository.flush();
        auditService.logAccountEvent(doctorSystemId, ACTION_TIME_OFF_DELETE, RESOURCE, doctorSystemId, "SUCCESS");
    }

    private Map<Integer, Integer> conflictCounts(List<Integer> timeOffIds) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (timeOffIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : appointmentQueries.countConflictingAppointments(timeOffIds)) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    private static TimeOffResponse toTimeOffResponse(DoctorTimeOff t, int conflicts) {
        return new TimeOffResponse(t.getTimeOffId(), t.getDoctorSystemId(), t.getStartsAt(), t.getEndsAt(),
                t.getReason(), conflicts, t.getCreatedAt());
    }

    // ==================== SLOTS ====================

    /**
     * Start times of a doctor on one clinic day, each flagged available or not (section 3.2 of the contract).
     * Any signed-in user may ask; the answer is the same for every role and discloses no appointment or time-off
     * detail. {@code excludeAppointmentId} is honoured only when that appointment belongs to this doctor and the
     * caller may manage its patient's appointments; otherwise it is silently ignored.
     */
    @Transactional(readOnly = true)
    public DoctorSlotsResponse getSlots(String doctorSystemId, LocalDate date, Integer durationMinutes,
                                        Integer excludeAppointmentId) {
        authorizationService.currentSystemId();
        if (date == null) {
            throw new BadRequestException("Missing required parameter 'date'");
        }
        if (durationMinutes != null && !SlotLength.Validator.allowed(durationMinutes)) {
            throw invalid("durationMinutes", SlotLength.MESSAGE);
        }
        String doctor = doctorSystemId == null ? null : doctorSystemId.trim();
        if (doctor == null || doctor.isEmpty() || appointmentQueries.countDoctors(doctor) == 0) {
            throw new ResourceNotFoundException("Doctor not found");
        }

        WorkingHours hours = workingHours(doctor, date);
        int duration = durationMinutes != null ? durationMinutes : hours.slotMinutes();

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        // An appointment or time off that starts the day before can still reach into this day, and a slot at the
        // end of the day can reach into the next one: look one day around the asked day.
        LocalDateTime from = dayStart.minusDays(1);
        LocalDateTime to = dayEnd.plusDays(1);

        List<Interval> timeOff = timeOffRepository.findOverlapping(doctor, from, to).stream()
                .map(t -> new Interval(t.getStartsAt(), t.getEndsAt()))
                .toList();
        int exclude = honouredExclusion(doctor, excludeAppointmentId);
        List<Interval> busy = new ArrayList<>();
        for (Object[] row : appointmentQueries.findActiveIntervals(doctor, from, to, exclude)) {
            LocalDateTime start = toLocalDateTime(row[0]);
            int minutes = ((Number) row[1]).intValue();
            busy.add(new Interval(start, start.plusMinutes(minutes)));
        }

        LocalDateTime now = clinic.now();
        List<Candidate> candidates = AvailabilityCalculator.slots(date, hours.windows(), hours.slotMinutes(), duration,
                now, now.plusDays(clinic.maxAdvanceDays()), timeOff, busy);
        List<DoctorSlotResponse> slots = candidates.stream()
                .map(c -> new DoctorSlotResponse(c.start(), c.available()))
                .toList();
        return new DoctorSlotsResponse(doctor, date, clinic.zone().getId(), hours.source(), hours.slotMinutes(),
                duration, slots);
    }

    /**
     * Working windows of the doctor on {@code date}: the active template blocks of that weekday (touching blocks
     * merged, clipped to clinic hours), or the clinic window when the doctor has no template. No window on a closed
     * weekend day. Used by the slots endpoint and by {@link AvailabilityBookingConstraint}.
     */
    @Transactional(readOnly = true)
    public WorkingHours workingHours(String doctorSystemId, LocalDate date) {
        List<DoctorAvailability> rows = loadTemplate(doctorSystemId);
        boolean open = clinic.isOpenOn(date);
        if (rows.isEmpty()) {
            return new WorkingHours(SOURCE_CLINIC_HOURS, ClinicSchedule.CLINIC_HOURS_SLOT_MINUTES,
                    AvailabilityCalculator.clinicWindows(clinic.openMinute(), clinic.closeMinute(), open));
        }
        List<Block> blocks = rows.stream()
                .map(r -> new Block(r.getDayOfWeek(), AvailabilityCalculator.minuteOfDay(r.getStartTime()),
                        AvailabilityCalculator.minuteOfDay(r.getEndTime()), Boolean.TRUE.equals(r.getIsActive())))
                .toList();
        return new WorkingHours(SOURCE_TEMPLATE, templateSlotMinutes(rows),
                AvailabilityCalculator.templateWindows(blocks, date, clinic.openMinute(), clinic.closeMinute(), open));
    }

    /** The appointment id to leave out of the taken times, or -1 when the parameter is absent or not honoured. */
    private int honouredExclusion(String doctorSystemId, Integer excludeAppointmentId) {
        if (excludeAppointmentId == null) {
            return -1;
        }
        List<Object[]> participants = appointmentQueries.findParticipants(excludeAppointmentId);
        if (participants.isEmpty()) {
            return -1;
        }
        String appointmentDoctor = (String) participants.get(0)[0];
        String patientSystemId = (String) participants.get(0)[1];
        if (!doctorSystemId.equals(appointmentDoctor)) {
            return -1;
        }
        return authorizationService.canAccessPatient(patientSystemId, PatientPermission.MANAGE_APPOINTMENTS)
                ? excludeAppointmentId : -1;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime();
        }
        throw new IllegalStateException("Unexpected timestamp type " + (value == null ? "null" : value.getClass().getName()));
    }

    // ==================== HELPERS ====================

    /** The caller must be a DOCTOR; returns their system id. ADMIN and every other role get 403. */
    private String requireDoctor() {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        return authorizationService.currentSystemId();
    }

    private static BadRequestException invalid(String field, String message) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, field + ": " + message);
    }
}
