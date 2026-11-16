package com.carenexus.api.billing.invoice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Native reads of the tables the invoice module does not own (consultations, appointments, users, doctors), plus
 * the per-visit lock and the "uninvoiced" list. Every statement joins the caller's transaction (the JPA transaction
 * manager exposes its JDBC connection), and none of them writes.
 */
@Repository
@RequiredArgsConstructor
public class InvoiceVisitQueries {

    /** First key of the per-visit advisory lock (ASCII "INVC"); the second key is the appointment id. */
    static final int VISIT_LOCK_NAMESPACE = 0x494E5643;

    private final NamedParameterJdbcTemplate jdbc;

    /** The fields of a consultation that invoicing needs. */
    public record ConsultationVisit(Integer consultationId, Integer appointmentId, String patientSystemId,
                                    String doctorSystemId, String status) {
    }

    /** The fields of an appointment that invoicing needs. */
    public record AppointmentVisit(Integer appointmentId, String patientSystemId, String doctorSystemId, String type,
                                   String status) {
    }

    /** One row of the uninvoiced list. */
    public record UninvoicedRow(Integer consultationId, Integer appointmentId, String appointmentType,
                                String patientSystemId, String patientName, LocalDateTime completedAt,
                                LocalDateTime scheduledAt) {
    }

    public Optional<ConsultationVisit> findConsultation(Integer consultationId) {
        List<ConsultationVisit> rows = jdbc.query(
                "SELECT consultation_id, appointment_id, patient_system_id, doctor_system_id, status "
                        + "FROM consultations WHERE consultation_id = :id",
                new MapSqlParameterSource("id", consultationId),
                (rs, i) -> new ConsultationVisit(rs.getInt("consultation_id"), nullableInt(rs, "appointment_id"),
                        rs.getString("patient_system_id"), rs.getString("doctor_system_id"), rs.getString("status")));
        return rows.stream().findFirst();
    }

    public Optional<AppointmentVisit> findAppointment(Integer appointmentId) {
        List<AppointmentVisit> rows = jdbc.query(
                "SELECT appointment_id, patient_system_id, doctor_system_id, type, status "
                        + "FROM appointments WHERE appointment_id = :id",
                new MapSqlParameterSource("id", appointmentId),
                (rs, i) -> new AppointmentVisit(rs.getInt("appointment_id"), rs.getString("patient_system_id"),
                        rs.getString("doctor_system_id"), rs.getString("type"), rs.getString("status")));
        return rows.stream().findFirst();
    }

    /** The newest COMPLETED consultation of an appointment (completion time, then id), if any. */
    public Optional<ConsultationVisit> findNewestCompletedConsultation(Integer appointmentId) {
        List<ConsultationVisit> rows = jdbc.query(
                "SELECT consultation_id, appointment_id, patient_system_id, doctor_system_id, status "
                        + "FROM consultations WHERE appointment_id = :id AND status = 'COMPLETED' "
                        + "ORDER BY completed_at DESC NULLS LAST, consultation_id DESC LIMIT 1",
                new MapSqlParameterSource("id", appointmentId),
                (rs, i) -> new ConsultationVisit(rs.getInt("consultation_id"), nullableInt(rs, "appointment_id"),
                        rs.getString("patient_system_id"), rs.getString("doctor_system_id"), rs.getString("status")));
        return rows.stream().findFirst();
    }

    /** True when the doctor profile exists and is active. */
    public boolean isDoctorActive(String doctorSystemId) {
        List<Boolean> rows = jdbc.query("SELECT is_active FROM doctors WHERE system_id = :id",
                new MapSqlParameterSource("id", doctorSystemId), (rs, i) -> rs.getBoolean("is_active"));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    /** "First Last" per system id, in one query; ids without a user row are absent from the map. */
    public Map<String, String> namesOf(Collection<String> systemIds) {
        Map<String, String> names = new HashMap<>();
        if (systemIds == null || systemIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT system_id, first_name, last_name FROM users WHERE system_id IN (:ids)",
                new MapSqlParameterSource("ids", systemIds),
                rs -> {
                    names.put(rs.getString("system_id"), fullName(rs.getString("first_name"), rs.getString("last_name")));
                });
        return names;
    }

    /**
     * Serializes invoice generation for one visit until the end of the current transaction (a PostgreSQL
     * transaction-level advisory lock keyed by the appointment id). Every consultation belongs to an appointment,
     * so all generation paths of one visit wait on the same key and two concurrent requests produce one invoice.
     */
    public void lockVisit(Integer appointmentId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:ns, :id)",
                new MapSqlParameterSource().addValue("ns", VISIT_LOCK_NAMESPACE).addValue("id", appointmentId),
                rs -> null);
    }

    /**
     * The doctor's COMPLETED consultations that have no invoice in a status other than CANCELLED, neither linked by
     * consultation nor by the consultation's appointment; newest completion first (nulls last), then id.
     */
    public List<UninvoicedRow> findUninvoiced(String doctorSystemId, int limit, long offset) {
        return jdbc.query(
                "SELECT c.consultation_id, c.appointment_id, a.type, c.patient_system_id, u.first_name, u.last_name, "
                        + "       c.completed_at, a.scheduled_at "
                        + UNINVOICED_FROM
                        + "ORDER BY c.completed_at DESC NULLS LAST, c.consultation_id DESC "
                        + "LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("doctor", doctorSystemId).addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, i) -> new UninvoicedRow(rs.getInt("consultation_id"), nullableInt(rs, "appointment_id"),
                        rs.getString("type"), rs.getString("patient_system_id"),
                        fullName(rs.getString("first_name"), rs.getString("last_name")),
                        rs.getObject("completed_at", LocalDateTime.class),
                        rs.getObject("scheduled_at", LocalDateTime.class)));
    }

    public long countUninvoiced(String doctorSystemId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + UNINVOICED_FROM,
                new MapSqlParameterSource("doctor", doctorSystemId), Long.class);
        return count == null ? 0 : count;
    }

    private static final String UNINVOICED_FROM =
            "FROM consultations c "
                    + "JOIN appointments a ON a.appointment_id = c.appointment_id "
                    + "LEFT JOIN users u ON u.system_id = c.patient_system_id "
                    + "WHERE c.doctor_system_id = :doctor AND c.status = 'COMPLETED' "
                    + "AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.status <> 'CANCELLED' "
                    + "    AND (i.consultation_id = c.consultation_id OR i.appointment_id = c.appointment_id)) ";

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static String fullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String name = (f + " " + l).trim();
        return name.isEmpty() ? null : name;
    }
}
