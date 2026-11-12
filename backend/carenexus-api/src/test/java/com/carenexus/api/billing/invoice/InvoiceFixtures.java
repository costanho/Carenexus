package com.carenexus.api.billing.invoice;

import com.carenexus.api.auth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Visit and fee fixtures for the invoice tests, written with plain SQL so they do not depend on the appointment and
 * consultation services. Every appointment gets its own start time, so the slot indexes never collide.
 */
public final class InvoiceFixtures {

    /** Clinic default fees shipped by V49 (reference data). */
    public static final BigDecimal DEFAULT_VIDEO = new BigDecimal("1500.00");
    public static final BigDecimal DEFAULT_IN_PERSON = new BigDecimal("2000.00");
    public static final BigDecimal DEFAULT_PHONE = new BigDecimal("1000.00");

    private final JdbcTemplate jdbc;
    private final AtomicInteger slot = new AtomicInteger();

    public InvoiceFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An appointment of the patient with the doctor, a few days in the past. */
    public Integer appointment(User patient, User doctor, String type, String status) {
        LocalDateTime start = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.HOURS)
                .plusMinutes(30L * slot.incrementAndGet());
        return jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status) VALUES (?, ?, ?, 30, ?, ?) RETURNING appointment_id",
                Integer.class, patient.getSystemId(), doctor.getSystemId(), start, type, status);
    }

    /** A consultation of an appointment; {@code completedAt} is set only for COMPLETED. */
    public Integer consultation(Integer appointmentId, User patient, User doctor, String status, LocalDateTime completedAt) {
        return jdbc.queryForObject("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, "
                        + "status, started_at, completed_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING consultation_id",
                Integer.class, appointmentId, patient.getSystemId(), doctor.getSystemId(), status,
                completedAt == null ? null : completedAt.minusMinutes(20), completedAt);
    }

    /** A COMPLETED appointment with a COMPLETED consultation; returns {consultationId, appointmentId}. */
    public Visit completedVisit(User patient, User doctor, String type) {
        Integer appointmentId = appointment(patient, doctor, type, "COMPLETED");
        Integer consultationId = consultation(appointmentId, patient, doctor, "COMPLETED",
                LocalDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS).minusMinutes(slot.get()));
        return new Visit(consultationId, appointmentId);
    }

    /** Puts the three clinic default fees back to the V49 values (active) and removes every doctor override. */
    public void restoreDefaultFees() {
        jdbc.update("DELETE FROM billing_fee_schedule WHERE doctor_system_id IS NOT NULL");
        restoreDefault("VIDEO", DEFAULT_VIDEO);
        restoreDefault("IN_PERSON", DEFAULT_IN_PERSON);
        restoreDefault("PHONE", DEFAULT_PHONE);
    }

    private void restoreDefault(String type, BigDecimal amount) {
        int updated = jdbc.update("UPDATE billing_fee_schedule SET amount = ?, currency = 'KES', is_active = TRUE "
                + "WHERE doctor_system_id IS NULL AND appointment_type = ?", amount, type);
        if (updated == 0) {
            jdbc.update("INSERT INTO billing_fee_schedule (doctor_system_id, appointment_type, amount, currency, is_active) "
                    + "VALUES (NULL, ?, ?, 'KES', TRUE)", type, amount);
        }
    }

    public record Visit(Integer consultationId, Integer appointmentId) {
    }
}
