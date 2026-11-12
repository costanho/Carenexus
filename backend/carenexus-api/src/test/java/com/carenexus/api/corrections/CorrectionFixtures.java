package com.carenexus.api.corrections;

import com.carenexus.api.auth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Clinical rows to file correction requests against, inserted with plain SQL (the corrections module reads these
 * tables with native queries and owns none of them). Each method returns the id of the new row, which is the
 * {@code resourceId} of a correction request.
 */
public final class CorrectionFixtures {

    private final JdbcTemplate jdbc;

    public CorrectionFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer medicalRecord(User patient, User author) {
        return jdbc.queryForObject("INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, title) "
                        + "VALUES (?, ?, 'NOTE', 'Follow-up note') RETURNING record_id",
                Integer.class, patient.getSystemId(), author.getSystemId());
    }

    public void softDeleteMedicalRecord(Integer recordId) {
        jdbc.update("UPDATE medical_records SET is_deleted = true, deleted_at = now() WHERE record_id = ?", recordId);
    }

    public Integer labResult(User patient, User author) {
        return jdbc.queryForObject("INSERT INTO lab_results (patient_system_id, doctor_system_id, test_name, test_type, "
                        + "status, test_date) VALUES (?, ?, 'Full blood count', 'BLOOD', 'COMPLETED', CURRENT_DATE) "
                        + "RETURNING result_id",
                Integer.class, patient.getSystemId(), author.getSystemId());
    }

    public Integer imagingResult(User patient, User author) {
        return jdbc.queryForObject("INSERT INTO imaging_results (patient_system_id, doctor_system_id, imaging_type, "
                        + "body_part, status, image_date) VALUES (?, ?, 'XRAY', 'Chest', 'COMPLETED', CURRENT_DATE) "
                        + "RETURNING imaging_id",
                Integer.class, patient.getSystemId(), author.getSystemId());
    }

    public Integer prescription(User patient, User author) {
        return jdbc.queryForObject("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, "
                        + "dosage, frequency, prescribed_date) VALUES (?, ?, 'Amoxicillin', '500 mg', 'Three times a day', "
                        + "CURRENT_DATE) RETURNING prescription_id",
                Integer.class, patient.getSystemId(), author.getSystemId());
    }

    /** A completed consultation (with its completed appointment the day before). */
    public Integer consultation(User patient, User author) {
        Integer appointmentId = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, "
                        + "scheduled_at, duration_minutes, type, status) VALUES (?, ?, ?, 30, 'IN_PERSON', 'COMPLETED') "
                        + "RETURNING appointment_id",
                Integer.class, patient.getSystemId(), author.getSystemId(),
                LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.HOURS));
        return jdbc.queryForObject("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status) "
                        + "VALUES (?, ?, ?, 'COMPLETED') RETURNING consultation_id",
                Integer.class, appointmentId, patient.getSystemId(), author.getSystemId());
    }

    /** Deactivates an account (the authoring doctor leaving the clinic, for example). */
    public void deactivate(User user) {
        jdbc.update("UPDATE users SET is_active = false WHERE system_id = ?", user.getSystemId());
    }

    /** Puts {@code doctor} on {@code patient}'s care team, which makes the doctor a related doctor. */
    public void addToCareTeam(User patient, User doctor) {
        jdbc.update("INSERT INTO care_team (patient_system_id, provider_system_id, role, assigned_at, is_active) "
                + "VALUES (?, ?, 'SPECIALIST', now(), true)", patient.getSystemId(), doctor.getSystemId());
    }

    /** A request body for POST /api/correction-requests. */
    public static Map<String, Object> body(String patientSystemId, String resourceType, Integer resourceId, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("resourceType", resourceType);
        body.put("resourceId", resourceId);
        body.put("requestedText", text);
        return body;
    }

    /** A request body for POST /api/correction-requests/{id}/resolve. */
    public static Map<String, Object> decision(String decision, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("decision", decision);
        body.put("note", note);
        return body;
    }
}
