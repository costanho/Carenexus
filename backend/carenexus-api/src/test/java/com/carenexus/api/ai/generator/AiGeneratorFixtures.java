package com.carenexus.api.ai.generator;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Test data of the alert generators, written with plain SQL: every table the generators read belongs to another
 * module, and alerts are only ever created by the code under test.
 */
public final class AiGeneratorFixtures {

    private final JdbcTemplate jdbc;

    public AiGeneratorFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== health_metrics ====================

    /** Stores one reading with the given severity and returns its id. */
    public int metric(String patientSystemId, String type, String value, String unit, String severity,
                      LocalDateTime recordedAt) {
        return jdbc.queryForObject("INSERT INTO health_metrics (patient_system_id, recorded_by_system_id, type, value, "
                        + "unit, source, is_abnormal, severity, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?) RETURNING metric_id",
                Integer.class, patientSystemId, patientSystemId, type, new BigDecimal(value), unit,
                !"NORMAL".equals(severity), severity, Timestamp.valueOf(recordedAt));
    }

    /** Id of the patient's newest stored reading of the given type. */
    public int metricId(String patientSystemId, String type) {
        return jdbc.queryForObject("SELECT metric_id FROM health_metrics WHERE patient_system_id = ? AND type = ? "
                + "ORDER BY recorded_at DESC, metric_id DESC LIMIT 1", Integer.class, patientSystemId, type);
    }

    /** A NORMAL heart-rate reading (bpm). */
    public int heartRate(String patientSystemId, String value, LocalDateTime recordedAt) {
        return metric(patientSystemId, "HEART_RATE", value, "bpm", "NORMAL", recordedAt);
    }

    // ==================== prescriptions ====================

    /** Stores a prescription and returns its id. */
    public int prescription(String patientSystemId, String doctorSystemId, String medicationName, String genericName,
                            String status, LocalDate prescribedDate, LocalDate expiryDate) {
        return jdbc.queryForObject("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, "
                        + "generic_name, dosage, frequency, route, refills_allowed, status, prescribed_date, "
                        + "expiry_date, created_at) "
                        + "VALUES (?, ?, ?, ?, '5 mg', 'Once daily', 'ORAL', 0, ?, ?, ?, ?) RETURNING prescription_id",
                Integer.class, patientSystemId, doctorSystemId, medicationName, genericName, status,
                Date.valueOf(prescribedDate), expiryDate == null ? null : Date.valueOf(expiryDate),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    // ==================== appointments and consultations ====================

    /** Stores an appointment and returns its id. */
    public int appointment(String patientSystemId, String doctorSystemId, String status, LocalDateTime scheduledAt) {
        return jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at) VALUES (?, ?, ?, 30, 'IN_PERSON', ?, ?) "
                        + "RETURNING appointment_id",
                Integer.class, patientSystemId, doctorSystemId, Timestamp.valueOf(scheduledAt), status,
                Timestamp.valueOf(scheduledAt.minusDays(1)));
    }

    /**
     * Stores a consultation for its own COMPLETED appointment one hour before {@code completedAt} and returns its id.
     */
    public int consultation(String patientSystemId, String doctorSystemId, String status, boolean followUpRequired,
                            LocalDate followUpDate, LocalDateTime completedAt) {
        int appointmentId = appointment(patientSystemId, doctorSystemId, "COMPLETED", completedAt.minusHours(1));
        return jdbc.queryForObject("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, "
                        + "chief_complaint, diagnosis, follow_up_required, follow_up_date, status, started_at, "
                        + "completed_at, created_at) VALUES (?, ?, ?, 'Persistent cough', 'Post-viral cough', ?, ?, ?, ?, ?, ?) "
                        + "RETURNING consultation_id",
                Integer.class, appointmentId, patientSystemId, doctorSystemId, followUpRequired,
                followUpDate == null ? null : Date.valueOf(followUpDate), status,
                Timestamp.valueOf(completedAt.minusMinutes(30)),
                "COMPLETED".equals(status) ? Timestamp.valueOf(completedAt) : null,
                Timestamp.valueOf(completedAt.minusMinutes(45)));
    }

    // ==================== ai_alerts and notifications ====================

    /** Every stored alert of the patient, oldest first. */
    public List<Map<String, Object>> alerts(String patientSystemId) {
        return jdbc.queryForList("SELECT * FROM ai_alerts WHERE patient_system_id = ? ORDER BY alert_id",
                patientSystemId);
    }

    /** Stored alerts of one type for the patient, oldest first. */
    public List<Map<String, Object>> alerts(String patientSystemId, String alertType) {
        return jdbc.queryForList("SELECT * FROM ai_alerts WHERE patient_system_id = ? AND alert_type = ? "
                + "ORDER BY alert_id", patientSystemId, alertType);
    }

    /** Every stored alert whose source is the given row, oldest first. */
    public List<Map<String, Object>> alertsFromSource(String sourceType, int sourceId) {
        return jdbc.queryForList("SELECT * FROM ai_alerts WHERE source_type = ? AND source_id = ? ORDER BY alert_id",
                sourceType, sourceId);
    }

    public int alertCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_alerts", Integer.class);
    }

    /** Number of AI_ALERT notifications stored for the recipient. */
    public int aiAlertNotifications(String recipientSystemId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT' AND system_id = ?",
                Integer.class, recipientSystemId);
    }

    /** Number of AI_ALERT notifications stored for anyone. */
    public int aiAlertNotifications() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'AI_ALERT'", Integer.class);
    }

    /** The AI_ALERT notification about one alert, for every recipient. */
    public List<Map<String, Object>> notificationsAbout(int alertId) {
        return jdbc.queryForList("SELECT system_id, priority, title, resource_type, resource_id, dedupe_key "
                + "FROM notifications WHERE type = 'AI_ALERT' AND resource_id = ? ORDER BY notification_id", alertId);
    }
}
