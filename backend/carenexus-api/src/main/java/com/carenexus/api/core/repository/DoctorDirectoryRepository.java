package com.carenexus.api.core.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

/**
 * The doctor directory: one joined query over doctors and users.
 *
 * A doctor is "listed" (shown in the directory, bookable) only when ALL of these hold:
 * users.is_active, users.role = 'DOCTOR', doctors.is_active and doctors.verification_status = 'APPROVED'.
 * Pending, rejected, suspended, deactivated and closed accounts are never listed.
 *
 * Rows are ordered by last name, first name (case-insensitive), then system id, so pages never overlap.
 */
@Repository
@RequiredArgsConstructor
public class DoctorDirectoryRepository {

    /** Public directory row: only the fields the public view exposes. */
    public record DirectoryRow(String doctorSystemId, Integer userId, String firstName, String lastName,
                               String specialization, String bio) {
    }

    static final String LISTED = "u.is_active = TRUE AND u.role = 'DOCTOR' AND d.is_active = TRUE "
            + "AND d.verification_status = 'APPROVED'";

    private static final String FROM = " FROM doctors d JOIN users u ON u.system_id = d.system_id WHERE " + LISTED;

    private static final String ORDER = " ORDER BY LOWER(u.last_name), LOWER(u.first_name), d.system_id";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * One page of listed doctors.
     *
     * @param query          case-insensitive "contains" on first name, last name, "first last" or specialization;
     *                       null or blank = no filter
     * @param specialization case-insensitive "contains" on specialization; null or blank = no filter
     */
    public List<DirectoryRow> findListed(String query, String specialization, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = filters(query, specialization, params);
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query("SELECT d.system_id, u.user_id, u.first_name, u.last_name, d.specialization, d.bio"
                        + FROM + where + ORDER + " LIMIT :limit OFFSET :offset",
                params,
                (rs, rowNum) -> new DirectoryRow(rs.getString("system_id"), rs.getInt("user_id"),
                        rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("specialization"), rs.getString("bio")));
    }

    /** Number of listed doctors matching the same filters as {@link #findListed}. */
    public long countListed(String query, String specialization) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = filters(query, specialization, params);
        Long count = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, params, Long.class);
        return count == null ? 0 : count;
    }

    /** True when the doctor is listed (see the class comment). */
    public boolean isListed(String doctorSystemId) {
        Boolean listed = jdbc.queryForObject("SELECT EXISTS (SELECT 1" + FROM + " AND d.system_id = :id)",
                new MapSqlParameterSource("id", doctorSystemId), Boolean.class);
        return Boolean.TRUE.equals(listed);
    }

    private static String filters(String query, String specialization, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        if (query != null && !query.isBlank()) {
            params.addValue("q", containsPattern(query));
            where.append(" AND (LOWER(u.first_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(u.first_name || ' ' || u.last_name) LIKE :q ESCAPE '\\'")
                    .append(" OR LOWER(COALESCE(d.specialization, '')) LIKE :q ESCAPE '\\')");
        }
        if (specialization != null && !specialization.isBlank()) {
            params.addValue("spec", containsPattern(specialization));
            where.append(" AND LOWER(COALESCE(d.specialization, '')) LIKE :spec ESCAPE '\\'");
        }
        return where.toString();
    }

    /** {@code %term%} in lower case with LIKE wildcards escaped, so % and _ match literally. */
    static String containsPattern(String term) {
        String escaped = term.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
