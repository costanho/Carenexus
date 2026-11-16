package com.carenexus.api.reminders.auto.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Native SQL of the automatic reminder jobs. The tables belong to other modules ({@code appointments},
 * {@code medication_schedules}, {@code users}, {@code user_settings}); this slice only reads them and writes its own
 * claim columns ({@code appointments.reminder_sent}, {@code appointments.reminder_1h_sent},
 * {@code medication_schedules.reminder_sent_at}).
 *
 * Every claim is one conditional {@code UPDATE ... RETURNING} over a {@code FOR UPDATE SKIP LOCKED} sub-select, so
 * several API instances, and a user editing the same row at the same moment, never produce a double claim. The
 * outer statement repeats the "not yet claimed" condition, so a row whose flag was set by a concurrent claim
 * between the sub-select and the update is not returned twice.
 */
@Repository
@RequiredArgsConstructor
public class ReminderJobsJdbc {

    /** Appointment statuses that can still be reminded or nudged. */
    private static final String ACTIVE_STATUSES = "('SCHEDULED', 'RESCHEDULED')";

    private static final String APPOINTMENT_COLUMNS =
            "appointment_id, patient_system_id, doctor_system_id, scheduled_at, duration_minutes";

    private final NamedParameterJdbcTemplate jdbc;

    /** An appointment claimed for a reminder, or found for the needs-outcome nudge (clinic wall-clock start). */
    public record ClaimedAppointment(int appointmentId, String patientSystemId, String doctorSystemId,
                                     LocalDateTime scheduledAt, int durationMinutes) {

        public LocalDateTime endsAt() {
            return scheduledAt.plusMinutes(durationMinutes);
        }
    }

    /** A dose row claimed for its reminder. The medication name is clinical detail: keep it out of logs. */
    public record ClaimedDose(int scheduleId, String patientSystemId, LocalDate scheduleDate,
                              LocalTime scheduledTime, String medicationName) {

        public LocalDateTime dueAt() {
            return scheduleDate.atTime(scheduledTime);
        }

        @Override
        public String toString() {
            return "ClaimedDose[scheduleId=" + scheduleId + ", patientSystemId=" + patientSystemId + "]";
        }
    }

    /** First and last name of a user; either may be null. */
    public record PersonName(String firstName, String lastName) {

        /** "First Last", trimmed; null when both parts are blank. */
        public String fullName() {
            String first = firstName == null ? "" : firstName.trim();
            String last = lastName == null ? "" : lastName.trim();
            String full = (first + " " + last).trim();
            return full.isEmpty() ? null : full;
        }
    }

    // ==================== APPOINTMENTS ====================

    /**
     * Second reminder: claims active appointments starting in {@code (now, windowEnd]} whose second reminder was not
     * yet claimed, setting {@code reminder_1h_sent} and {@code reminder_sent}. Ordered by start, then id.
     */
    public List<ClaimedAppointment> claimSecondReminders(LocalDateTime now, LocalDateTime windowEnd, int limit) {
        List<ClaimedAppointment> rows = jdbc.query(
                "UPDATE appointments SET reminder_1h_sent = TRUE, reminder_sent = TRUE "
                        + "WHERE appointment_id IN (SELECT appointment_id FROM appointments "
                        + "    WHERE status IN " + ACTIVE_STATUSES + " AND reminder_1h_sent = FALSE "
                        + "      AND scheduled_at > :from AND scheduled_at <= :to "
                        + "    ORDER BY scheduled_at, appointment_id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "  AND reminder_1h_sent = FALSE AND status IN " + ACTIVE_STATUSES + " "
                        + "RETURNING " + APPOINTMENT_COLUMNS,
                window(now, windowEnd, limit), ReminderJobsJdbc::appointment);
        return sortedAppointments(rows);
    }

    /**
     * First reminder: claims active appointments starting in {@code (windowStart, windowEnd]} whose first reminder
     * was not yet claimed, setting {@code reminder_sent}. Ordered by start, then id. An empty window (start not
     * before end) claims nothing.
     */
    public List<ClaimedAppointment> claimFirstReminders(LocalDateTime windowStart, LocalDateTime windowEnd, int limit) {
        if (!windowStart.isBefore(windowEnd)) {
            return List.of();
        }
        List<ClaimedAppointment> rows = jdbc.query(
                "UPDATE appointments SET reminder_sent = TRUE "
                        + "WHERE appointment_id IN (SELECT appointment_id FROM appointments "
                        + "    WHERE status IN " + ACTIVE_STATUSES + " AND reminder_sent = FALSE "
                        + "      AND scheduled_at > :from AND scheduled_at <= :to "
                        + "    ORDER BY scheduled_at, appointment_id LIMIT :limit FOR UPDATE SKIP LOCKED) "
                        + "  AND reminder_sent = FALSE AND status IN " + ACTIVE_STATUSES + " "
                        + "RETURNING " + APPOINTMENT_COLUMNS,
                window(windowStart, windowEnd, limit), ReminderJobsJdbc::appointment);
        return sortedAppointments(rows);
    }

    /** Upper bound of {@code duration_minutes} (V13 CHECK), which turns the end window into a start window. */
    static final int MAX_DURATION_MINUTES = 1440;

    /**
     * Needs-outcome candidates: active appointments whose end ({@code scheduled_at + duration_minutes}) lies in
     * {@code (endAfter, endNotAfter]}, keyset-paged on {@code appointment_id > afterId}, ascending. The start is
     * bounded on both sides ({@code endAfter - 1440 minutes < scheduled_at <= endNotAfter}, exact because no
     * appointment lasts longer), so the scan stays inside the window instead of reading every older appointment.
     */
    public List<ClaimedAppointment> findWithoutOutcome(LocalDateTime endAfter, LocalDateTime endNotAfter,
                                                       int afterId, int limit) {
        return jdbc.query("SELECT " + APPOINTMENT_COLUMNS + " FROM appointments "
                        + "WHERE status IN " + ACTIVE_STATUSES + " AND appointment_id > :afterId "
                        + "  AND scheduled_at <= :endNotAfter AND scheduled_at > :startAfter "
                        + "  AND scheduled_at + duration_minutes * INTERVAL '1 minute' <= :endNotAfter "
                        + "  AND scheduled_at + duration_minutes * INTERVAL '1 minute' > :endAfter "
                        + "ORDER BY appointment_id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("afterId", afterId)
                        .addValue("endAfter", endAfter)
                        .addValue("endNotAfter", endNotAfter)
                        .addValue("startAfter", endAfter.minusMinutes(MAX_DURATION_MINUTES))
                        .addValue("limit", limit),
                ReminderJobsJdbc::appointment);
    }

    /**
     * Clears both reminder flags of one appointment, so the new start time earns its own reminders.
     *
     * @return rows updated (0 for an unknown id)
     */
    public int rearm(int appointmentId) {
        return jdbc.update("UPDATE appointments SET reminder_sent = FALSE, reminder_1h_sent = FALSE "
                        + "WHERE appointment_id = :id",
                new MapSqlParameterSource("id", appointmentId));
    }

    // ==================== DOSES ====================

    /**
     * Claims pending, not yet reminded doses whose time {@code schedule_date + scheduled_time} lies in
     * {@code (from, now]}, stamping {@code reminder_sent_at = now}. Ordered by date, time, then id.
     */
    public List<ClaimedDose> claimDueDoses(LocalDateTime from, LocalDateTime now, int limit) {
        List<ClaimedDose> rows = jdbc.query(
                "UPDATE medication_schedules SET reminder_sent_at = :now "
                        + "WHERE schedule_id IN (SELECT schedule_id FROM medication_schedules "
                        + "    WHERE is_taken = FALSE AND missed = FALSE AND reminder_sent_at IS NULL "
                        + "      AND schedule_date >= :fromDate AND schedule_date <= :nowDate "
                        + "      AND (schedule_date + scheduled_time) > :from "
                        + "      AND (schedule_date + scheduled_time) <= :now "
                        + "    ORDER BY schedule_date, scheduled_time, schedule_id LIMIT :limit "
                        + "    FOR UPDATE SKIP LOCKED) "
                        + "  AND reminder_sent_at IS NULL AND is_taken = FALSE AND missed = FALSE "
                        + "RETURNING schedule_id, patient_system_id, schedule_date, scheduled_time, medication_name",
                new MapSqlParameterSource()
                        .addValue("from", from)
                        .addValue("now", now)
                        .addValue("fromDate", from.toLocalDate())
                        .addValue("nowDate", now.toLocalDate())
                        .addValue("limit", limit),
                (rs, i) -> new ClaimedDose(rs.getInt("schedule_id"), rs.getString("patient_system_id"),
                        rs.getObject("schedule_date", LocalDate.class),
                        rs.getObject("scheduled_time", LocalTime.class), rs.getString("medication_name")));
        return rows.stream()
                .sorted(Comparator.comparing(ClaimedDose::scheduleDate)
                        .thenComparing(ClaimedDose::scheduledTime)
                        .thenComparingInt(ClaimedDose::scheduleId))
                .toList();
    }

    // ==================== PEOPLE ====================

    /** Names of the given users, keyed by system id; unknown ids are absent. */
    public Map<String, PersonName> names(Collection<String> systemIds) {
        Set<String> ids = nonNull(systemIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, PersonName> names = new HashMap<>();
        jdbc.query("SELECT system_id, first_name, last_name FROM users WHERE system_id IN (:ids)",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    names.put(rs.getString("system_id"),
                            new PersonName(rs.getString("first_name"), rs.getString("last_name")));
                });
        return names;
    }

    /** The stored {@code user_settings.timezone} of the given users (raw text, may be blank or invalid). */
    public Map<String, String> timeZones(Collection<String> systemIds) {
        Set<String> ids = nonNull(systemIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> zones = new HashMap<>();
        jdbc.query("SELECT system_id, timezone FROM user_settings WHERE system_id IN (:ids) AND timezone IS NOT NULL",
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    zones.put(rs.getString("system_id"), rs.getString("timezone"));
                });
        return zones;
    }

    // ==================== HELPERS ====================

    private static MapSqlParameterSource window(LocalDateTime from, LocalDateTime to, int limit) {
        return new MapSqlParameterSource()
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", limit);
    }

    private static ClaimedAppointment appointment(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedAppointment(rs.getInt("appointment_id"), rs.getString("patient_system_id"),
                rs.getString("doctor_system_id"), rs.getObject("scheduled_at", LocalDateTime.class),
                rs.getInt("duration_minutes"));
    }

    /** {@code UPDATE ... RETURNING} has no defined order: restore the claim order. */
    private static List<ClaimedAppointment> sortedAppointments(List<ClaimedAppointment> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(ClaimedAppointment::scheduledAt)
                        .thenComparingInt(ClaimedAppointment::appointmentId))
                .toList();
    }

    private static Set<String> nonNull(Collection<String> systemIds) {
        if (systemIds == null) {
            return Set.of();
        }
        return systemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
