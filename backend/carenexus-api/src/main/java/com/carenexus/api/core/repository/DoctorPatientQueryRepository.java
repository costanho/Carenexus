package com.carenexus.api.core.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Batch queries behind the doctor patient list (native SQL, no per-row lookups).
 *
 * "A doctor's patients" is the relationship of {@code RelationshipService.getPatientsForDoctor} /
 * {@code isDoctorAssignedToPatient}, evaluated in the database so filters, ordering and paging run there:
 * an active care_team row with a doctor role ({@link CareTeamRepository#DOCTOR_ROLES}), OR an appointment with the
 * doctor whose status is not CANCELLED, OR a consultation with the doctor. Only patients that have a patients row
 * and an account row are returned.
 *
 * The health-status note columns added by V52 ({@code patients.health_status_note}, {@code health_status_updated_at},
 * {@code health_status_updated_by}) are not mapped on the Patient entity and are read and written here only.
 */
@Repository
@RequiredArgsConstructor
public class DoctorPatientQueryRepository {

    /** The search filters; null or blank values mean "no filter". */
    public record Filter(String query, String healthStatus, String gender) {
        public static final Filter NONE = new Filter(null, null, null);
    }

    /** Sort orders of the paged list. Each ends with last name, first name, patient system id. */
    public enum Sort { NAME, NEXT_APPOINTMENT, HEALTH_STATUS, LAST_VISIT }

    /** Patient and account fields of one list row. */
    public record PatientRow(String patientSystemId, String firstName, String lastName, String email, String phone,
                             LocalDate dateOfBirth, String gender, String bloodType, String allergies,
                             String chronicConditions, String healthStatus, LocalDateTime createdAt) {
    }

    /** Appointment-derived values for one patient and one doctor. */
    public record AppointmentStats(int total, int upcoming, int completed, int cancelled, int noShow, int rescheduled,
                                   LocalDateTime lastVisitAt, Integer nextAppointmentId,
                                   LocalDateTime nextAppointmentAt, String nextAppointmentType) {
        public static final AppointmentStats NONE = new AppointmentStats(0, 0, 0, 0, 0, 0, null, null, null, null);
    }

    /** Health-status counts over the related set. */
    public record StatusCounts(long total, long stable, long monitor, long critical) {
    }

    /** Id and name of a related patient. */
    public record OptionRow(String patientSystemId, String firstName, String lastName) {
    }

    /** Current health status of a patient with the latest note and its author. */
    public record HealthStatusState(String patientSystemId, String healthStatus, String note, LocalDateTime updatedAt,
                                    String updatedBySystemId, String updaterFirstName, String updaterLastName,
                                    String updaterRole) {
    }

    private static final String RELATED_CTE = "WITH related AS ("
            + " SELECT ct.patient_system_id AS pid FROM care_team ct"
            + "  WHERE ct.provider_system_id = :doctor AND ct.is_active = TRUE AND ct.role IN " + CareTeamRepository.DOCTOR_ROLES
            + " UNION SELECT a.patient_system_id FROM appointments a"
            + "  WHERE a.doctor_system_id = :doctor AND a.status <> 'CANCELLED'"
            + " UNION SELECT c.patient_system_id FROM consultations c WHERE c.doctor_system_id = :doctor"
            + ")";

    /** Next and last visit per patient for this doctor (only joined when the sort needs it). */
    private static final String VISITS_CTE = ", visits AS ("
            + " SELECT a.patient_system_id AS pid,"
            + "  MIN(a.scheduled_at) FILTER (WHERE a.status IN ('SCHEDULED','RESCHEDULED') AND a.scheduled_at >= :now) AS next_at,"
            + "  MAX(a.scheduled_at) FILTER (WHERE a.status = 'COMPLETED' AND a.scheduled_at <= :now) AS last_visit"
            + " FROM appointments a WHERE a.doctor_system_id = :doctor GROUP BY a.patient_system_id"
            + ")";

    private static final String RELATED_FROM = " FROM related r"
            + " JOIN patients p ON p.system_id = r.pid"
            + " JOIN users u ON u.system_id = p.system_id";

    private static final String PATIENT_COLUMNS = "p.system_id, u.first_name, u.last_name, u.email, u.phone,"
            + " p.date_of_birth, p.gender, p.blood_type, p.allergies::text AS allergies,"
            + " p.chronic_conditions::text AS chronic_conditions, p.health_status, p.created_at";

    private static final String NAME_ORDER = "LOWER(u.last_name), LOWER(u.first_name), p.system_id";

    private final NamedParameterJdbcTemplate jdbc;

    // ==================== RELATED SET ====================

    /**
     * One page of the doctor's related patients, filtered and ordered in the database.
     *
     * @param now clinic wall-clock now (next appointment and last visit for the NEXT_APPOINTMENT / LAST_VISIT sorts)
     */
    public List<PatientRow> findRelatedPatients(String doctorSystemId, Filter filter, Sort sort, LocalDateTime now,
                                                int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        boolean needsVisits = sort == Sort.NEXT_APPOINTMENT || sort == Sort.LAST_VISIT;
        StringBuilder sql = new StringBuilder(RELATED_CTE);
        if (needsVisits) {
            sql.append(VISITS_CTE);
            params.addValue("now", Timestamp.valueOf(now));
        }
        sql.append(" SELECT ").append(PATIENT_COLUMNS).append(RELATED_FROM);
        if (needsVisits) {
            sql.append(" LEFT JOIN visits v ON v.pid = p.system_id");
        }
        sql.append(" WHERE TRUE").append(filters(filter, params));
        sql.append(" ORDER BY ").append(orderBy(sort)).append(" LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, PATIENT_ROW);
    }

    /** Number of related patients matching the filter. */
    public long countRelatedPatients(String doctorSystemId, Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId);
        String sql = RELATED_CTE + " SELECT COUNT(*)" + RELATED_FROM + " WHERE TRUE" + filters(filter, params);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** Health-status counts over the whole related set (no filter). */
    public StatusCounts countByHealthStatus(String doctorSystemId) {
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId);
        String sql = RELATED_CTE + " SELECT"
                + " COUNT(*) AS total,"
                + " COUNT(*) FILTER (WHERE p.health_status = 'STABLE') AS stable,"
                + " COUNT(*) FILTER (WHERE p.health_status = 'MONITOR') AS monitor,"
                + " COUNT(*) FILTER (WHERE p.health_status = 'CRITICAL') AS critical"
                + RELATED_FROM;
        return jdbc.queryForObject(sql, params, (rs, rowNum) -> new StatusCounts(
                rs.getLong("total"), rs.getLong("stable"), rs.getLong("monitor"), rs.getLong("critical")));
    }

    /** Ids and names of related patients ordered by name, at most {@code limit}. */
    public List<OptionRow> findOptions(String doctorSystemId, String query, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder(RELATED_CTE)
                .append(" SELECT p.system_id, u.first_name, u.last_name").append(RELATED_FROM).append(" WHERE TRUE");
        if (query != null && !query.isBlank()) {
            params.addValue("q", containsPattern(query));
            sql.append(" AND (LOWER(u.first_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.first_name || ' ' || u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(p.system_id) LIKE :q ESCAPE '\\')");
        }
        sql.append(" ORDER BY ").append(NAME_ORDER).append(" LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new OptionRow(
                rs.getString("system_id"), rs.getString("first_name"), rs.getString("last_name")));
    }

    // ==================== ONE PATIENT ====================

    /** Patient and account fields of one patient (no relationship check: the caller authorises first). */
    public Optional<PatientRow> findPatient(String patientSystemId) {
        List<PatientRow> rows = jdbc.query("SELECT " + PATIENT_COLUMNS
                        + " FROM patients p JOIN users u ON u.system_id = p.system_id WHERE p.system_id = :pid",
                new MapSqlParameterSource("pid", patientSystemId), PATIENT_ROW);
        return rows.stream().findFirst();
    }

    // ==================== APPOINTMENTS BETWEEN THE DOCTOR AND THE PATIENTS ====================

    /**
     * Counters, last visit and next appointment per patient in one statement (a grouped query joined with the
     * earliest active future appointment of each patient). Patients without appointments are absent from the map.
     *
     * @param now clinic wall-clock now
     */
    public Map<String, AppointmentStats> appointmentStats(String doctorSystemId, Collection<String> patientSystemIds,
                                                          LocalDateTime now) {
        Map<String, AppointmentStats> stats = new HashMap<>();
        if (patientSystemIds == null || patientSystemIds.isEmpty()) {
            return stats;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId)
                .addValue("ids", patientSystemIds)
                .addValue("now", Timestamp.valueOf(now));
        String sql = "SELECT s.pid, s.total, s.upcoming, s.completed, s.cancelled, s.no_show, s.rescheduled,"
                + " s.last_visit, n.appointment_id AS next_id, n.scheduled_at AS next_at, n.type AS next_type"
                + " FROM ("
                + "  SELECT a.patient_system_id AS pid,"
                + "   COUNT(*) AS total,"
                + "   COUNT(*) FILTER (WHERE a.status IN ('SCHEDULED','RESCHEDULED') AND a.scheduled_at >= :now) AS upcoming,"
                + "   COUNT(*) FILTER (WHERE a.status = 'COMPLETED') AS completed,"
                + "   COUNT(*) FILTER (WHERE a.status = 'CANCELLED') AS cancelled,"
                + "   COUNT(*) FILTER (WHERE a.status = 'NO_SHOW') AS no_show,"
                + "   COUNT(*) FILTER (WHERE a.status = 'RESCHEDULED') AS rescheduled,"
                + "   MAX(a.scheduled_at) FILTER (WHERE a.status = 'COMPLETED' AND a.scheduled_at <= :now) AS last_visit"
                + "  FROM appointments a"
                + "  WHERE a.doctor_system_id = :doctor AND a.patient_system_id IN (:ids)"
                + "  GROUP BY a.patient_system_id"
                + " ) s LEFT JOIN ("
                + "  SELECT DISTINCT ON (a.patient_system_id) a.patient_system_id AS pid, a.appointment_id,"
                + "   a.scheduled_at, a.type"
                + "  FROM appointments a"
                + "  WHERE a.doctor_system_id = :doctor AND a.patient_system_id IN (:ids)"
                + "   AND a.status IN ('SCHEDULED','RESCHEDULED') AND a.scheduled_at >= :now"
                + "  ORDER BY a.patient_system_id, a.scheduled_at, a.appointment_id"
                + " ) n ON n.pid = s.pid";
        jdbc.query(sql, params, (RowCallbackHandler) rs -> {
            int nextId = rs.getInt("next_id");
            Integer nextAppointmentId = rs.wasNull() ? null : nextId;
            stats.put(rs.getString("pid"), new AppointmentStats(
                    rs.getInt("total"), rs.getInt("upcoming"), rs.getInt("completed"), rs.getInt("cancelled"),
                    rs.getInt("no_show"), rs.getInt("rescheduled"),
                    toLocalDateTime(rs.getTimestamp("last_visit")),
                    nextAppointmentId,
                    toLocalDateTime(rs.getTimestamp("next_at")),
                    rs.getString("next_type")));
        });
        return stats;
    }

    /**
     * Ids of the {@code perPatient} most recent appointments (any status, scheduled_at descending) between the
     * doctor and each patient.
     */
    public List<Integer> recentAppointmentIds(String doctorSystemId, Collection<String> patientSystemIds,
                                              int perPatient) {
        if (patientSystemIds == null || patientSystemIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("doctor", doctorSystemId)
                .addValue("ids", patientSystemIds)
                .addValue("per", perPatient);
        String sql = "SELECT x.appointment_id FROM ("
                + " SELECT a.appointment_id, ROW_NUMBER() OVER (PARTITION BY a.patient_system_id"
                + "   ORDER BY a.scheduled_at DESC, a.appointment_id DESC) AS rn"
                + " FROM appointments a"
                + " WHERE a.doctor_system_id = :doctor AND a.patient_system_id IN (:ids)"
                + ") x WHERE x.rn <= :per";
        return jdbc.queryForList(sql, params, Integer.class);
    }

    // ==================== HEALTH STATUS ====================

    /** Current status, note and author; {@code forUpdate} locks the patients row until the transaction ends. */
    public Optional<HealthStatusState> findHealthStatus(String patientSystemId, boolean forUpdate) {
        String sql = "SELECT p.system_id, p.health_status, p.health_status_note, p.health_status_updated_at,"
                + " p.health_status_updated_by, b.first_name AS by_first, b.last_name AS by_last, b.role AS by_role"
                + " FROM patients p LEFT JOIN users b ON b.system_id = p.health_status_updated_by"
                + " WHERE p.system_id = :pid"
                + (forUpdate ? " FOR UPDATE OF p" : "");
        List<HealthStatusState> rows = jdbc.query(sql, new MapSqlParameterSource("pid", patientSystemId),
                (rs, rowNum) -> new HealthStatusState(
                        rs.getString("system_id"),
                        rs.getString("health_status"),
                        rs.getString("health_status_note"),
                        toLocalDateTime(rs.getTimestamp("health_status_updated_at")),
                        rs.getString("health_status_updated_by"),
                        rs.getString("by_first"),
                        rs.getString("by_last"),
                        rs.getString("by_role")));
        return rows.stream().findFirst();
    }

    /** Stores status, note, time and author in one statement. Returns the number of rows changed (0 or 1). */
    public int updateHealthStatus(String patientSystemId, String healthStatus, String note, LocalDateTime updatedAt,
                                  String updatedBySystemId) {
        MapSqlParameterSource params = new MapSqlParameterSource("pid", patientSystemId)
                .addValue("status", healthStatus)
                .addValue("note", note)
                .addValue("at", Timestamp.valueOf(updatedAt))
                .addValue("by", updatedBySystemId);
        return jdbc.update("UPDATE patients SET health_status = :status, health_status_note = :note,"
                + " health_status_updated_at = :at, health_status_updated_by = :by WHERE system_id = :pid", params);
    }

    // ==================== HELPERS ====================

    private static String filters(Filter filter, MapSqlParameterSource params) {
        if (filter == null) {
            return "";
        }
        StringBuilder where = new StringBuilder();
        if (filter.query() != null && !filter.query().isBlank()) {
            params.addValue("q", containsPattern(filter.query()));
            params.addValue("qraw", "%" + escapeLike(filter.query().trim()) + "%");
            where.append(" AND (LOWER(u.first_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.first_name || ' ' || u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(COALESCE(u.email, '')) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(p.system_id) LIKE :q ESCAPE '\\'")
                    .append(" OR COALESCE(u.phone, '') LIKE :qraw ESCAPE '\\')");
        }
        if (filter.healthStatus() != null && !filter.healthStatus().isBlank()) {
            params.addValue("hs", filter.healthStatus());
            where.append(" AND p.health_status = :hs");
        }
        if (filter.gender() != null && !filter.gender().isBlank()) {
            params.addValue("gender", filter.gender());
            where.append(" AND p.gender = :gender");
        }
        return where.toString();
    }

    private static String orderBy(Sort sort) {
        return switch (sort == null ? Sort.NAME : sort) {
            case NAME -> NAME_ORDER;
            case NEXT_APPOINTMENT -> "v.next_at ASC NULLS LAST, " + NAME_ORDER;
            case HEALTH_STATUS -> "CASE p.health_status WHEN 'CRITICAL' THEN 0 WHEN 'MONITOR' THEN 1 ELSE 2 END, "
                    + NAME_ORDER;
            case LAST_VISIT -> "v.last_visit DESC NULLS LAST, " + NAME_ORDER;
        };
    }

    /** {@code %term%} in lower case with LIKE wildcards escaped, so % and _ match literally. */
    static String containsPattern(String term) {
        return "%" + escapeLike(term.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static final RowMapper<PatientRow> PATIENT_ROW = DoctorPatientQueryRepository::mapPatient;

    private static PatientRow mapPatient(ResultSet rs, int rowNum) throws SQLException {
        Date dob = rs.getDate("date_of_birth");
        return new PatientRow(
                rs.getString("system_id"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("phone"),
                dob == null ? null : dob.toLocalDate(),
                rs.getString("gender"),
                rs.getString("blood_type"),
                rs.getString("allergies"),
                rs.getString("chronic_conditions"),
                rs.getString("health_status"),
                toLocalDateTime(rs.getTimestamp("created_at")));
    }
}
