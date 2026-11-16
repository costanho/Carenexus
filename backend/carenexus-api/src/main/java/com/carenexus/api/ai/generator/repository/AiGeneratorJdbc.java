package com.carenexus.api.ai.generator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only native SQL of the alert generators. Every table read here belongs to another module
 * ({@code health_metrics}, {@code prescriptions}, {@code consultations}, {@code appointments}, {@code patients},
 * {@code doctors}) or to be-ai-core ({@code ai_alerts}), so nothing goes through their entities. Alerts are written
 * only through {@code AiAlertService.raise}.
 * <p>
 * Runs on the caller's transaction (the JDBC connection of the JPA transaction).
 */
@Repository
@RequiredArgsConstructor
public class AiGeneratorJdbc {

    /** Upper bound of other active prescriptions screened against a new one. */
    public static final int MAX_ACTIVE_PRESCRIPTIONS = 200;

    private final NamedParameterJdbcTemplate jdbc;

    /** A stored health reading. */
    public record MetricRow(int metricId, String patientSystemId, String type, BigDecimal value, String severity,
                            LocalDateTime recordedAt) {

        /** The value is clinical detail: keep it out of logs. */
        @Override
        public String toString() {
            return "MetricRow[metricId=" + metricId + ", type=" + type + "]";
        }
    }

    /** Number and median of a patient's earlier readings of one type. */
    public record Baseline(int readings, BigDecimal median) {

        @Override
        public String toString() {
            return "Baseline[readings=" + readings + "]";
        }
    }

    /** The prescription columns the interaction screening needs. */
    public record PrescriptionRow(int prescriptionId, String patientSystemId, String doctorSystemId,
                                  String medicationName, String genericName) {

        /** The name screened for interactions: the generic name when it is not blank, else the medication name. */
        public String screeningName() {
            return genericName != null && !genericName.isBlank() ? genericName : medicationName;
        }

        /** Medication names are clinical detail: keep them out of logs. */
        @Override
        public String toString() {
            return "PrescriptionRow[prescriptionId=" + prescriptionId + "]";
        }
    }

    /** A completed consultation whose follow-up is overdue. */
    public record FollowUpDue(int consultationId, String patientSystemId, String doctorSystemId,
                              LocalDate followUpDate) {
    }

    // ==================== health_metrics ====================

    /** The reading, or empty when it no longer exists (health metrics are hard-deleted). */
    public Optional<MetricRow> findMetric(int metricId) {
        List<MetricRow> rows = jdbc.query("""
                        SELECT metric_id, patient_system_id, type, value, severity, recorded_at
                          FROM health_metrics
                         WHERE metric_id = :metricId
                        """,
                new MapSqlParameterSource("metricId", metricId),
                (rs, i) -> new MetricRow(rs.getInt("metric_id"), rs.getString("patient_system_id"),
                        rs.getString("type"), rs.getBigDecimal("value"), rs.getString("severity"),
                        rs.getTimestamp("recorded_at").toLocalDateTime()));
        return rows.stream().findFirst();
    }

    /**
     * Count and median ({@code percentile_cont(0.5)}) of the patient's readings of {@code type} recorded at or after
     * {@code since} and strictly before {@code before}.
     */
    public Baseline baseline(String patientSystemId, String type, LocalDateTime since, LocalDateTime before) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("patient", patientSystemId)
                .addValue("type", type)
                .addValue("since", Timestamp.valueOf(since))
                .addValue("before", Timestamp.valueOf(before));
        return jdbc.queryForObject("""
                        SELECT COUNT(*) AS readings,
                               percentile_cont(0.5) WITHIN GROUP (ORDER BY value) AS median
                          FROM health_metrics
                         WHERE patient_system_id = :patient
                           AND type = :type
                           AND recorded_at >= :since
                           AND recorded_at < :before
                        """,
                params,
                (rs, i) -> {
                    double median = rs.getDouble("median");
                    return new Baseline(rs.getInt("readings"), rs.wasNull() ? null : BigDecimal.valueOf(median));
                });
    }

    // ==================== prescriptions ====================

    public Optional<PrescriptionRow> findPrescription(int prescriptionId) {
        List<PrescriptionRow> rows = jdbc.query("""
                        SELECT prescription_id, patient_system_id, doctor_system_id, medication_name, generic_name
                          FROM prescriptions
                         WHERE prescription_id = :id
                        """,
                new MapSqlParameterSource("id", prescriptionId), this::prescription);
        return rows.stream().findFirst();
    }

    /**
     * The patient's other prescriptions that are in force on {@code clinicToday}: stored status ACTIVE and an
     * expiry date that is null or not before today (a prescription is valid through its expiry date; the hourly
     * expiry job may not have flipped it yet). Oldest first, at most {@link #MAX_ACTIVE_PRESCRIPTIONS}.
     */
    public List<PrescriptionRow> findOtherActivePrescriptions(String patientSystemId, int excludePrescriptionId,
                                                              LocalDate clinicToday) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("patient", patientSystemId)
                .addValue("exclude", excludePrescriptionId)
                .addValue("today", clinicToday)
                .addValue("limit", MAX_ACTIVE_PRESCRIPTIONS);
        return jdbc.query("""
                        SELECT prescription_id, patient_system_id, doctor_system_id, medication_name, generic_name
                          FROM prescriptions
                         WHERE patient_system_id = :patient
                           AND status = 'ACTIVE'
                           AND (expiry_date IS NULL OR expiry_date >= :today)
                           AND prescription_id <> :exclude
                         ORDER BY prescription_id
                         LIMIT :limit
                        """,
                params, this::prescription);
    }

    private PrescriptionRow prescription(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PrescriptionRow(rs.getInt("prescription_id"), rs.getString("patient_system_id"),
                rs.getString("doctor_system_id"), rs.getString("medication_name"), rs.getString("generic_name"));
    }

    // ==================== consultations ====================

    /**
     * Completed consultations with an overdue follow-up (api/be-ai-generators.md 5.5), in id order after
     * {@code afterConsultationId}: follow-up required, follow-up date before {@code clinicToday} and not before
     * {@code earliest}, no later SCHEDULED, RESCHEDULED or COMPLETED appointment of the same patient with the same
     * doctor after the consultation ended ({@code completed_at}, else {@code created_at}), and no FOLLOW_UP_DUE alert
     * yet. Patient and doctor must still have their profile rows (an alert cannot be addressed otherwise).
     */
    public List<FollowUpDue> findFollowUpsDue(LocalDate clinicToday, LocalDate earliest, int afterConsultationId,
                                              int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("today", clinicToday)
                .addValue("earliest", earliest)
                .addValue("after", afterConsultationId)
                .addValue("limit", limit);
        return jdbc.query("""
                        SELECT c.consultation_id, c.patient_system_id, c.doctor_system_id, c.follow_up_date
                          FROM consultations c
                          JOIN patients p ON p.system_id = c.patient_system_id
                          JOIN doctors d ON d.system_id = c.doctor_system_id
                         WHERE c.status = 'COMPLETED'
                           AND c.follow_up_required
                           AND c.follow_up_date < :today
                           AND c.follow_up_date >= :earliest
                           AND c.consultation_id > :after
                           AND NOT EXISTS (
                                 SELECT 1 FROM appointments a
                                  WHERE a.patient_system_id = c.patient_system_id
                                    AND a.doctor_system_id = c.doctor_system_id
                                    AND a.status IN ('SCHEDULED', 'RESCHEDULED', 'COMPLETED')
                                    AND a.scheduled_at > COALESCE(c.completed_at, c.created_at))
                           AND NOT EXISTS (
                                 SELECT 1 FROM ai_alerts al
                                  WHERE al.dedupe_key = 'FOLLOW_UP_DUE:' || c.consultation_id)
                         ORDER BY c.consultation_id
                         LIMIT :limit
                        """,
                params,
                (rs, i) -> new FollowUpDue(rs.getInt("consultation_id"), rs.getString("patient_system_id"),
                        rs.getString("doctor_system_id"), rs.getObject("follow_up_date", LocalDate.class)));
    }
}
