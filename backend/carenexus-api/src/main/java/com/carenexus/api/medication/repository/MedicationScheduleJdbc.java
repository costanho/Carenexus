package com.carenexus.api.medication.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Native SQL of the medication module.
 *
 * - The {@code prescriptions} table belongs to the prescriptions module, so it is read here with plain SQL and
 *   never through its entity.
 * - Doses are inserted with explicit column lists and {@code ON CONFLICT DO NOTHING} on the unique
 *   (prescription, day, time) constraint, so materialising twice is harmless and columns other slices add stay NULL.
 * - Every write is one statement; callers flush pending JPA changes before reading here when they depend on them.
 *
 * Concurrency: every writer of a prescription's future doses holds a lock on its {@code prescriptions} row while it
 * reads the status and plan and deletes or creates doses, so they never interleave on one prescription:
 * - a status change holds the row lock of its own UPDATE (and {@link #lockPrescription} re-takes it);
 * - PUT /api/prescriptions/{id}/schedule and the return to ACTIVE take it with {@link #lockPrescription};
 * - the sweeper reads its page with {@code FOR SHARE OF rx, p SKIP LOCKED} ({@link #findActiveWithPlan}).
 *
 * Runs on the caller's transaction (the JDBC connection of the JPA transaction).
 */
@Repository
@RequiredArgsConstructor
public class MedicationScheduleJdbc {

    /** Prescription columns the schedule needs. */
    public record PrescriptionSnapshot(int prescriptionId, String patientSystemId, String doctorSystemId,
                                       String medicationName, String dosage, String status,
                                       LocalDate prescribedDate, LocalDate expiryDate) {

        /** The medication name and dosage are clinical detail: keep them out of logs. */
        @Override
        public String toString() {
            return "PrescriptionSnapshot[prescriptionId=" + prescriptionId + ", status=" + status + "]";
        }
    }

    /** An ACTIVE prescription with its stored plan ({@code timesJson} is the JSON array text). */
    public record PlannedPrescription(PrescriptionSnapshot prescription, String timesJson) {
    }

    /** A stored plan row. */
    public record StoredPlan(int prescriptionId, String timesJson, String updatedBySystemId, LocalDateTime updatedAt) {
    }

    /** A pending dose claimed by the sweeper. */
    public record DueDose(int scheduleId, int prescriptionId, String patientSystemId, LocalDate scheduleDate,
                          LocalTime scheduledTime) {
    }

    /** Dose counts of a window. */
    public record StatusCounts(long taken, long missed, long pending) {
    }

    private static final String PRESCRIPTION_COLUMNS = "rx.prescription_id, rx.patient_system_id, rx.doctor_system_id, "
            + "rx.medication_name, rx.dosage, rx.status, rx.prescribed_date, rx.expiry_date";

    private static final RowMapper<PrescriptionSnapshot> PRESCRIPTION = (rs, i) -> new PrescriptionSnapshot(
            rs.getInt("prescription_id"), rs.getString("patient_system_id"), rs.getString("doctor_system_id"),
            rs.getString("medication_name"), rs.getString("dosage"), rs.getString("status"),
            rs.getObject("prescribed_date", LocalDate.class), rs.getObject("expiry_date", LocalDate.class));

    private final NamedParameterJdbcTemplate jdbc;

    // ==================== PRESCRIPTIONS (read only) ====================

    public Optional<PrescriptionSnapshot> findPrescription(int prescriptionId) {
        List<PrescriptionSnapshot> rows = jdbc.query(
                "SELECT " + PRESCRIPTION_COLUMNS + " FROM prescriptions rx WHERE rx.prescription_id = :id",
                new MapSqlParameterSource("id", prescriptionId), PRESCRIPTION);
        return rows.stream().findFirst();
    }

    /**
     * Reads the prescription and locks its row ({@code FOR NO KEY UPDATE}, the lock an UPDATE of the row takes)
     * until the caller's transaction ends. It waits for a concurrent status change, schedule PUT or sweeper batch on
     * the same prescription to commit and then returns the committed state, so the caller's decision (is it ACTIVE,
     * which times) and its dose writes cannot be overtaken by them. Callers must run in a read-write transaction.
     */
    public Optional<PrescriptionSnapshot> lockPrescription(int prescriptionId) {
        List<PrescriptionSnapshot> rows = jdbc.query(
                "SELECT " + PRESCRIPTION_COLUMNS + " FROM prescriptions rx WHERE rx.prescription_id = :id "
                        + "FOR NO KEY UPDATE OF rx",
                new MapSqlParameterSource("id", prescriptionId), PRESCRIPTION);
        return rows.stream().findFirst();
    }

    /**
     * ACTIVE prescriptions with a non-empty plan that have not expired before {@code today}, after
     * {@code afterPrescriptionId} in id order (keyset paging), at most {@code limit}.
     *
     * The prescription and plan rows are share-locked until the caller's transaction ends, so a status change or a
     * schedule PUT on one of them waits for the batch to commit (and then sees and removes its doses). Rows a status
     * change or PUT has already locked are skipped; they are picked up by the next run once that writer commits.
     * A row changed by a writer that committed after this statement started is re-checked against its committed
     * version (status still ACTIVE, the current times), so stale times or a stopped prescription are never used.
     * A page with fewer than {@code limit} rows ends the pass; anything left over (a row dropped by that re-check)
     * is picked up by the next run.
     */
    public List<PlannedPrescription> findActiveWithPlan(int afterPrescriptionId, LocalDate today, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("after", afterPrescriptionId)
                .addValue("today", today)
                .addValue("limit", limit);
        return jdbc.query("SELECT " + PRESCRIPTION_COLUMNS + ", p.times::text AS times_json "
                        + "FROM prescriptions rx "
                        + "JOIN medication_schedule_plans p ON p.prescription_id = rx.prescription_id "
                        + "WHERE rx.status = 'ACTIVE' AND jsonb_array_length(p.times) > 0 "
                        + "AND (rx.expiry_date IS NULL OR rx.expiry_date >= :today) "
                        + "AND rx.prescription_id > :after "
                        + "ORDER BY rx.prescription_id LIMIT :limit "
                        + "FOR SHARE OF rx, p SKIP LOCKED",
                params, (rs, i) -> new PlannedPrescription(PRESCRIPTION.mapRow(rs, i), rs.getString("times_json")));
    }

    // ==================== PLANS ====================

    public Optional<StoredPlan> findPlan(int prescriptionId) {
        List<StoredPlan> rows = jdbc.query(
                "SELECT prescription_id, times::text AS times_json, updated_by_system_id, updated_at "
                        + "FROM medication_schedule_plans WHERE prescription_id = :id",
                new MapSqlParameterSource("id", prescriptionId),
                (rs, i) -> new StoredPlan(rs.getInt("prescription_id"), rs.getString("times_json"),
                        rs.getString("updated_by_system_id"), rs.getObject("updated_at", LocalDateTime.class)));
        return rows.stream().findFirst();
    }

    /** Creates the plan or replaces its times, author and time. */
    public void upsertPlan(int prescriptionId, String timesJson, String updatedBySystemId, LocalDateTime updatedAt) {
        jdbc.update("INSERT INTO medication_schedule_plans (prescription_id, times, updated_by_system_id, updated_at) "
                        + "VALUES (:id, CAST(:times AS jsonb), :by, :at) "
                        + "ON CONFLICT (prescription_id) DO UPDATE SET times = EXCLUDED.times, "
                        + "updated_by_system_id = EXCLUDED.updated_by_system_id, updated_at = EXCLUDED.updated_at",
                new MapSqlParameterSource()
                        .addValue("id", prescriptionId)
                        .addValue("times", timesJson)
                        .addValue("by", updatedBySystemId)
                        .addValue("at", updatedAt));
    }

    // ==================== DOSES ====================

    /** Creates one PENDING dose unless the prescription already has one on that day and time. Returns 1 or 0. */
    public int insertDose(PrescriptionSnapshot rx, LocalDate day, LocalTime time, LocalDateTime createdAt) {
        return jdbc.update("INSERT INTO medication_schedules (prescription_id, patient_system_id, scheduled_time, "
                        + "medication_name, dosage, is_taken, taken_at, missed, schedule_date, created_at, "
                        + "marked_by_system_id) "
                        + "VALUES (:rx, :patient, :time, :name, :dosage, FALSE, NULL, FALSE, :day, :createdAt, NULL) "
                        + "ON CONFLICT (prescription_id, schedule_date, scheduled_time) DO NOTHING",
                new MapSqlParameterSource()
                        .addValue("rx", rx.prescriptionId())
                        .addValue("patient", rx.patientSystemId())
                        .addValue("time", time)
                        .addValue("name", rx.medicationName())
                        .addValue("dosage", rx.dosage())
                        .addValue("day", day)
                        .addValue("createdAt", createdAt));
    }

    /**
     * Deletes the PENDING doses of the prescription scheduled strictly after {@code now} (rest of today and later
     * days). Taken, missed and past pending doses are kept. Returns the number of rows deleted.
     */
    public int deleteFuturePending(int prescriptionId, LocalDateTime now) {
        return jdbc.update("DELETE FROM medication_schedules WHERE prescription_id = :rx "
                        + "AND is_taken = FALSE AND missed = FALSE "
                        + "AND (schedule_date > :today OR (schedule_date = :today AND scheduled_time > :time))",
                new MapSqlParameterSource()
                        .addValue("rx", prescriptionId)
                        .addValue("today", now.toLocalDate())
                        .addValue("time", now.toLocalTime()));
    }

    /**
     * Number of MISSED doses of the prescription directly before the given day and time (in day, time order),
     * stopping at the first earlier dose that is TAKEN or PENDING; at most {@code maxExamined} doses are looked at.
     */
    public int missedRunBefore(int prescriptionId, LocalDate day, LocalTime time, int maxExamined) {
        List<Boolean> missed = jdbc.queryForList("SELECT missed FROM medication_schedules "
                        + "WHERE prescription_id = :rx "
                        + "AND (schedule_date < :day OR (schedule_date = :day AND scheduled_time < :time)) "
                        + "ORDER BY schedule_date DESC, scheduled_time DESC LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("rx", prescriptionId)
                        .addValue("day", day)
                        .addValue("time", time)
                        .addValue("limit", maxExamined),
                Boolean.class);
        int run = 0;
        for (Boolean value : missed) {
            if (!Boolean.TRUE.equals(value)) {
                break;
            }
            run++;
        }
        return run;
    }

    /**
     * Claims (row-locks, skipping rows locked by others) up to {@code limit} PENDING doses scheduled on
     * {@code notBefore} or later whose day and time are before {@code cutoff}, oldest first.
     */
    public List<DueDose> claimPendingBefore(LocalDate notBefore, LocalDateTime cutoff, int limit) {
        return jdbc.query("SELECT schedule_id, prescription_id, patient_system_id, schedule_date, scheduled_time "
                        + "FROM medication_schedules "
                        + "WHERE is_taken = FALSE AND missed = FALSE "
                        + "AND schedule_date >= :notBefore AND schedule_date <= :cutoffDate "
                        + "AND (schedule_date + scheduled_time) < :cutoff "
                        + "ORDER BY schedule_date, scheduled_time, schedule_id LIMIT :limit "
                        + "FOR UPDATE SKIP LOCKED",
                new MapSqlParameterSource()
                        .addValue("notBefore", notBefore)
                        .addValue("cutoffDate", cutoff.toLocalDate())
                        .addValue("cutoff", cutoff)
                        .addValue("limit", limit),
                (rs, i) -> new DueDose(rs.getInt("schedule_id"), rs.getInt("prescription_id"),
                        rs.getString("patient_system_id"), rs.getObject("schedule_date", LocalDate.class),
                        rs.getObject("scheduled_time", LocalTime.class)));
    }

    /**
     * Marks every PENDING dose scheduled before {@code before} MISSED by the server, in one statement and without
     * any event: these doses lie outside the window in which anyone can still mark them (legacy rows, or a backlog
     * left by downtime), so alerting about them now would only produce stale notifications. Returns the count.
     */
    public int markStalePendingMissed(LocalDate before) {
        return jdbc.update("UPDATE medication_schedules SET missed = TRUE, is_taken = FALSE, taken_at = NULL, "
                        + "marked_by_system_id = NULL "
                        + "WHERE is_taken = FALSE AND missed = FALSE AND schedule_date < :before",
                new MapSqlParameterSource("before", before));
    }

    /** Marks a still PENDING dose MISSED by the server (no marker). Returns 1 when it changed, 0 otherwise. */
    public int markMissedBySystem(int scheduleId) {
        return jdbc.update("UPDATE medication_schedules SET missed = TRUE, is_taken = FALSE, taken_at = NULL, "
                        + "marked_by_system_id = NULL "
                        + "WHERE schedule_id = :id AND is_taken = FALSE AND missed = FALSE",
                new MapSqlParameterSource("id", scheduleId));
    }

    /** Taken, missed and pending doses of one patient between two days (inclusive). */
    public StatusCounts countByStatus(String patientSystemId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("SELECT "
                        + "COUNT(*) FILTER (WHERE is_taken) AS taken, "
                        + "COUNT(*) FILTER (WHERE missed AND NOT is_taken) AS missed, "
                        + "COUNT(*) FILTER (WHERE NOT is_taken AND NOT missed) AS pending "
                        + "FROM medication_schedules "
                        + "WHERE patient_system_id = :patient AND schedule_date BETWEEN :from AND :to",
                new MapSqlParameterSource()
                        .addValue("patient", patientSystemId)
                        .addValue("from", from)
                        .addValue("to", to),
                (rs, i) -> new StatusCounts(rs.getLong("taken"), rs.getLong("missed"), rs.getLong("pending")));
    }
}
