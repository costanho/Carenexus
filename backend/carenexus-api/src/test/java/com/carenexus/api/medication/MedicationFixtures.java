package com.carenexus.api.medication;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Test data of the medication module, written with plain SQL (the prescriptions table belongs to another module,
 * and doses are normally only created by the server).
 */
public final class MedicationFixtures {

    private MedicationFixtures() {
    }

    /** Inserts a prescription and returns its id. */
    public static int insertPrescription(JdbcTemplate jdbc, String patientSystemId, String doctorSystemId,
                                         String medicationName, String frequency, String status,
                                         LocalDate prescribedDate, LocalDate expiryDate) {
        return jdbc.queryForObject("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, "
                        + "dosage, frequency, route, refills_allowed, status, prescribed_date, expiry_date, created_at) "
                        + "VALUES (?, ?, ?, '850mg', ?, 'ORAL', 0, ?, ?, ?, ?) RETURNING prescription_id",
                Integer.class, patientSystemId, doctorSystemId, medicationName, frequency, status,
                Date.valueOf(prescribedDate), expiryDate == null ? null : Date.valueOf(expiryDate),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public static void setPrescriptionStatus(JdbcTemplate jdbc, int prescriptionId, String status) {
        jdbc.update("UPDATE prescriptions SET status = ? WHERE prescription_id = ?", status, prescriptionId);
    }

    /** Inserts a dose with the given status (PENDING, TAKEN or MISSED) and returns its id. */
    public static int insertDose(JdbcTemplate jdbc, int prescriptionId, String patientSystemId, LocalDate day,
                                 LocalTime time, String status, String markedBy) {
        boolean taken = "TAKEN".equals(status);
        boolean missed = "MISSED".equals(status);
        return jdbc.queryForObject("INSERT INTO medication_schedules (prescription_id, patient_system_id, "
                        + "scheduled_time, medication_name, dosage, is_taken, taken_at, missed, schedule_date, "
                        + "created_at, marked_by_system_id) "
                        + "VALUES (?, ?, ?, 'Metformin', '850mg', ?, ?, ?, ?, ?, ?) RETURNING schedule_id",
                Integer.class, prescriptionId, patientSystemId, Time.valueOf(time), taken,
                taken ? Timestamp.valueOf(day.atTime(time)) : null, missed, Date.valueOf(day),
                Timestamp.valueOf(LocalDateTime.now()), markedBy);
    }

    public static void storePlan(JdbcTemplate jdbc, int prescriptionId, String timesJson, String updatedBy) {
        jdbc.update("INSERT INTO medication_schedule_plans (prescription_id, times, updated_by_system_id, updated_at) "
                + "VALUES (?, CAST(? AS jsonb), ?, ?)", prescriptionId, timesJson, updatedBy,
                Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    /** "HH:mm" times of the prescription's doses on one day, ascending. */
    public static List<String> doseTimes(JdbcTemplate jdbc, int prescriptionId, LocalDate day) {
        return jdbc.queryForList("SELECT to_char(scheduled_time, 'HH24:MI') FROM medication_schedules "
                + "WHERE prescription_id = ? AND schedule_date = ? ORDER BY scheduled_time", String.class,
                prescriptionId, Date.valueOf(day));
    }

    /** PENDING, TAKEN or MISSED. */
    public static String status(JdbcTemplate jdbc, int scheduleId) {
        return jdbc.queryForObject("SELECT CASE WHEN is_taken THEN 'TAKEN' WHEN missed THEN 'MISSED' ELSE 'PENDING' END "
                + "FROM medication_schedules WHERE schedule_id = ?", String.class, scheduleId);
    }

    public static int countDoses(JdbcTemplate jdbc, int prescriptionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM medication_schedules WHERE prescription_id = ?",
                Integer.class, prescriptionId);
    }

    /** The stored plan times as JSON text, or null when there is no plan. */
    public static String planJson(JdbcTemplate jdbc, int prescriptionId) {
        List<String> rows = jdbc.queryForList("SELECT times::text FROM medication_schedule_plans "
                + "WHERE prescription_id = ?", String.class, prescriptionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int auditRows(JdbcTemplate jdbc, String action, Integer resourceId, String patientSystemId) {
        if (resourceId == null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? "
                    + "AND resource_type = 'MEDICATION_SCHEDULE' AND resource_id IS NULL AND patient_system_id = ? "
                    + "AND status = 'SUCCESS'", Integer.class, action, patientSystemId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? "
                + "AND resource_type = 'MEDICATION_SCHEDULE' AND resource_id = ? AND patient_system_id = ? "
                + "AND status = 'SUCCESS'", Integer.class, action, resourceId, patientSystemId);
    }
}
