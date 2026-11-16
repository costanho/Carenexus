package com.carenexus.api.reminders.auto;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Test data and read-backs of the automatic reminder jobs, written with plain SQL (the appointments,
 * medication_schedules and notifications tables belong to other modules).
 */
public final class ReminderJobsFixtures {

    /** The clinic zone of the test profile ({@code carenexus.scheduling.time-zone}). */
    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private ReminderJobsFixtures() {
    }

    /** The current clinic wall-clock time, whole minutes. */
    public static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC_ZONE).truncatedTo(ChronoUnit.MINUTES);
    }

    /** Inserts an appointment row directly (no booking rules) and returns its id. */
    public static int insertAppointment(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                        LocalDateTime start, int durationMinutes, String status) {
        Integer id = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'IN_PERSON', ?, LOCALTIMESTAMP, LOCALTIMESTAMP) RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId, Timestamp.valueOf(start), durationMinutes, status);
        return id == null ? -1 : id;
    }

    public static boolean reminderSent(JdbcTemplate jdbc, int appointmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT reminder_sent FROM appointments WHERE appointment_id = ?", Boolean.class, appointmentId));
    }

    public static boolean secondReminderSent(JdbcTemplate jdbc, int appointmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT reminder_1h_sent FROM appointments WHERE appointment_id = ?", Boolean.class, appointmentId));
    }

    public static LocalDateTime doseReminderSentAt(JdbcTemplate jdbc, int scheduleId) {
        return jdbc.queryForObject("SELECT reminder_sent_at FROM medication_schedules WHERE schedule_id = ?",
                LocalDateTime.class, scheduleId);
    }

    /** Notifications of one recipient and category (expired ones included). */
    public static int count(JdbcTemplate jdbc, String recipientSystemId, String category) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND category = ?",
                Integer.class, recipientSystemId, category);
        return count == null ? 0 : count;
    }

    /** Notifications of one recipient whose dedupe key starts with {@code keyPrefix}. */
    public static int countByKey(JdbcTemplate jdbc, String recipientSystemId, String keyPrefix) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? "
                + "AND dedupe_key LIKE ?", Integer.class, recipientSystemId, keyPrefix + "%");
        return count == null ? 0 : count;
    }

    /** All notifications produced by the reminder jobs (by their dedupe key prefixes). */
    public static int countAllReminders(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE dedupe_key LIKE 'APPT_REMINDER_24H:%' "
                + "OR dedupe_key LIKE 'APPT_REMINDER_1H:%' OR dedupe_key LIKE 'DOSE_REMINDER:%' "
                + "OR dedupe_key LIKE 'APPT_NEEDS_OUTCOME:%'", Integer.class);
        return count == null ? 0 : count;
    }

    /** The single notification of one recipient with this exact dedupe key, as a column map. */
    public static Map<String, Object> notification(JdbcTemplate jdbc, String recipientSystemId, String dedupeKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, category, title, message, priority, action_url, "
                        + "target, resource_type, resource_id, patient_system_id, actor_system_id, expires_at, dedupe_key "
                        + "FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                recipientSystemId, dedupeKey);
        if (rows.size() != 1) {
            throw new AssertionError("Expected one notification " + dedupeKey + " for " + recipientSystemId
                    + " but found " + rows.size());
        }
        return rows.get(0);
    }
}
