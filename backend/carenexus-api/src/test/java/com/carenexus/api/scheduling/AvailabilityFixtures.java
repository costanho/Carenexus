package com.carenexus.api.scheduling;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test helpers of the scheduling module (bodies, dates and direct inserts). */
final class AvailabilityFixtures {

    static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private AvailabilityFixtures() {
    }

    /** Today in the clinic zone. */
    static LocalDate clinicToday() {
        return LocalDate.now(CLINIC_ZONE);
    }

    /** Now in the clinic zone. */
    static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC_ZONE);
    }

    /** The first {@code day} at least a week from today (always in the future and well inside the horizon). */
    static LocalDate futureDay(DayOfWeek day) {
        return clinicToday().plusDays(7).with(TemporalAdjusters.nextOrSame(day));
    }

    static Map<String, Object> block(int dayOfWeek, String start, String end) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("dayOfWeek", dayOfWeek);
        block.put("startTime", start);
        block.put("endTime", end);
        return block;
    }

    static Map<String, Object> block(int dayOfWeek, String start, String end, boolean active) {
        Map<String, Object> block = block(dayOfWeek, start, end);
        block.put("isActive", active);
        return block;
    }

    static Map<String, Object> template(Integer slotMinutes, List<?> blocks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotMinutes", slotMinutes);
        body.put("blocks", blocks);
        return body;
    }

    static Map<String, Object> timeOff(String startsAt, String endsAt, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startsAt", startsAt);
        body.put("endsAt", endsAt);
        body.put("reason", reason);
        return body;
    }

    /** Inserts an appointment row directly (no booking rules) and returns its id. */
    static int insertAppointment(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                 LocalDateTime start, int durationMinutes, String status) {
        Integer id = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'IN_PERSON', ?, LOCALTIMESTAMP, LOCALTIMESTAMP) RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId, Timestamp.valueOf(start), durationMinutes, status);
        return id == null ? -1 : id;
    }

    /** Inserts a time-off row directly (also in the past, which the API refuses) and returns its id. */
    static int insertTimeOff(JdbcTemplate jdbc, String doctorSystemId, LocalDateTime startsAt, LocalDateTime endsAt,
                             String reason) {
        Integer id = jdbc.queryForObject("INSERT INTO doctor_time_off (doctor_system_id, starts_at, ends_at, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, LOCALTIMESTAMP) RETURNING time_off_id",
                Integer.class, doctorSystemId, Timestamp.valueOf(startsAt), Timestamp.valueOf(endsAt), reason);
        return id == null ? -1 : id;
    }
}
