package com.carenexus.api.releasegates.crossflow2;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Read-backs and small inserts shared by the wave 5 cross-slice gates (billing, consent, channels, support, account
 * tokens, settings). Plain SQL against committed data, because every gate spans tables of several modules.
 */
final class CrossFlow2Fixtures {

    /** The clinic zone of the test profile ({@code carenexus.scheduling.time-zone}). */
    static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private CrossFlow2Fixtures() {
    }

    static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC_ZONE).truncatedTo(ChronoUnit.MINUTES);
    }

    static LocalDate clinicToday() {
        return LocalDate.now(CLINIC_ZONE);
    }

    static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** Inserts an appointment row directly (no booking rules) and returns its id. */
    static int insertAppointment(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                 LocalDateTime start, int durationMinutes, String type, String status) {
        Integer id = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, LOCALTIMESTAMP, LOCALTIMESTAMP) RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId, Timestamp.valueOf(start), durationMinutes, type, status);
        return id == null ? -1 : id;
    }

    // ==================== NOTIFICATIONS ====================

    /** Notifications of one recipient whose dedupe key equals {@code dedupeKey}. */
    static int withKey(JdbcTemplate jdbc, String recipientSystemId, String dedupeKey) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                recipientSystemId, dedupeKey);
    }

    /** Notifications of one recipient whose dedupe key starts with {@code prefix}. */
    static int withKeyPrefix(JdbcTemplate jdbc, String recipientSystemId, String prefix) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key LIKE ?",
                recipientSystemId, prefix + "%");
    }

    static int ofType(JdbcTemplate jdbc, String recipientSystemId, String type) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND type = ?",
                recipientSystemId, type);
    }

    static int ofCategory(JdbcTemplate jdbc, String recipientSystemId, String category) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND category = ?",
                recipientSystemId, category);
    }

    static int allOf(JdbcTemplate jdbc, String recipientSystemId) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ?", recipientSystemId);
    }

    /** The one notification of a recipient with this dedupe key, as a column map. */
    static Map<String, Object> onlyWithKey(JdbcTemplate jdbc, String recipientSystemId, String dedupeKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT notification_id, type, category, title, message, "
                + "priority, action_url, actor_system_id, patient_system_id, dedupe_key FROM notifications "
                + "WHERE system_id = ? AND dedupe_key = ?", recipientSystemId, dedupeKey);
        if (rows.size() != 1) {
            throw new AssertionError("Expected one notification " + dedupeKey + " for " + recipientSystemId
                    + ", found " + rows);
        }
        return rows.get(0);
    }

    // ==================== AUDIT ====================

    static int auditRows(JdbcTemplate jdbc, String action, String resourceType) {
        return count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ?", action,
                resourceType);
    }
}
