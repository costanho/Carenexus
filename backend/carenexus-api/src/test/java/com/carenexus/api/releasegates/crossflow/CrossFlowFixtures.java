package com.carenexus.api.releasegates.crossflow;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Read-backs and small inserts shared by the cross-slice flow gates. Everything is plain SQL against committed data,
 * because the gates span tables of several modules.
 */
final class CrossFlowFixtures {

    /** The clinic zone of the test profile ({@code carenexus.scheduling.time-zone}). */
    static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private CrossFlowFixtures() {
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

    /** Every notification of one recipient, oldest first, as column maps. */
    static List<Map<String, Object>> notificationsOf(JdbcTemplate jdbc, String recipientSystemId) {
        return jdbc.queryForList("SELECT notification_id, type, category, title, message, priority, action_url, "
                + "resource_type, resource_id, patient_system_id, actor_system_id, dedupe_key "
                + "FROM notifications WHERE system_id = ? ORDER BY notification_id", recipientSystemId);
    }

    /** Notifications of one recipient whose dedupe key equals {@code dedupeKey}. */
    static int notificationsWithKey(JdbcTemplate jdbc, String recipientSystemId, String dedupeKey) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                recipientSystemId, dedupeKey);
    }

    /** Notifications of one recipient whose dedupe key starts with {@code prefix}. */
    static int notificationsWithKeyPrefix(JdbcTemplate jdbc, String recipientSystemId, String prefix) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key LIKE ?",
                recipientSystemId, prefix + "%");
    }

    static int notificationsOfType(JdbcTemplate jdbc, String recipientSystemId, String type) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ? AND type = ?",
                recipientSystemId, type);
    }

    static int allNotifications(JdbcTemplate jdbc, String recipientSystemId) {
        return count(jdbc, "SELECT COUNT(*) FROM notifications WHERE system_id = ?", recipientSystemId);
    }

    static String appointmentStatus(JdbcTemplate jdbc, int appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?", String.class,
                appointmentId);
    }

    static String consultationStatus(JdbcTemplate jdbc, int consultationId) {
        return jdbc.queryForObject("SELECT status FROM consultations WHERE consultation_id = ?", String.class,
                consultationId);
    }

    /** Live (not soft-deleted) CONSULT records of the consultation. */
    static int consultRecords(JdbcTemplate jdbc, int consultationId) {
        return count(jdbc, "SELECT COUNT(*) FROM medical_records WHERE consultation_id = ? AND record_type = 'CONSULT' "
                + "AND is_deleted = FALSE", consultationId);
    }

    /** Inserts an appointment row directly (no booking rules) and returns its id. */
    static int insertAppointment(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                 LocalDateTime start, String type, String status) {
        Integer id = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 30, ?, ?, LOCALTIMESTAMP, LOCALTIMESTAMP) RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId, java.sql.Timestamp.valueOf(start), type, status);
        return id == null ? -1 : id;
    }

    /** Audit rows with this action, resource type and resource id. */
    static int auditRows(JdbcTemplate jdbc, String action, String resourceType, int resourceId) {
        return count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? AND resource_id = ?",
                action, resourceType, resourceId);
    }
}
