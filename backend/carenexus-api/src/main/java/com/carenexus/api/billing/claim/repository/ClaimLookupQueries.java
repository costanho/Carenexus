package com.carenexus.api.billing.claim.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native reads of the tables the claim module does not own ({@code users}, {@code documents}). Every statement
 * joins the caller's transaction and none of them writes.
 * <p>
 * A document is usable as a claim attachment when {@code is_deleted = FALSE AND storage_key IS NOT NULL} (the table
 * contract of be-documents after V41): a soft-deleted document, or a pre-V41 row without a stored file, answers 404
 * on download, so it is neither accepted nor listed.
 */
@Repository
@RequiredArgsConstructor
public class ClaimLookupQueries {

    /** At most this many administrators are notified of one claim event. */
    public static final int MAX_ADMIN_RECIPIENTS = 200;

    private final NamedParameterJdbcTemplate jdbc;

    /** A usable document: its owner and title. */
    public record DocumentRow(Integer documentId, String patientSystemId, String title) {
    }

    /** "First Last" per system id; ids without a user row are absent. */
    public Map<String, String> namesOf(Collection<String> systemIds) {
        Map<String, String> names = new HashMap<>();
        if (systemIds == null || systemIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT system_id, first_name, last_name FROM users WHERE system_id IN (:ids)",
                new MapSqlParameterSource("ids", systemIds),
                rs -> {
                    names.put(rs.getString("system_id"), fullName(rs.getString("first_name"),
                            rs.getString("last_name")));
                });
        return names;
    }

    /**
     * Number of the given ids that are usable documents of {@code patientSystemId}. The ids are compared as a set,
     * so the caller passes distinct ids and compares the count with their number.
     */
    public int countUsableDocuments(String patientSystemId, Set<Integer> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return 0;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE document_id IN (:ids) AND patient_system_id = :patient "
                        + "AND is_deleted = FALSE AND storage_key IS NOT NULL",
                new MapSqlParameterSource().addValue("ids", documentIds).addValue("patient", patientSystemId),
                Integer.class);
        return count == null ? 0 : count;
    }

    /** The usable documents among the given ids (any patient; the caller matches the owner), by id. */
    public Map<Integer, DocumentRow> usableDocuments(Collection<Integer> documentIds) {
        Map<Integer, DocumentRow> rows = new HashMap<>();
        if (documentIds == null || documentIds.isEmpty()) {
            return rows;
        }
        jdbc.query("SELECT document_id, patient_system_id, title FROM documents WHERE document_id IN (:ids) "
                        + "AND is_deleted = FALSE AND storage_key IS NOT NULL",
                new MapSqlParameterSource("ids", documentIds),
                rs -> {
                    int id = rs.getInt("document_id");
                    rows.put(id, new DocumentRow(id, rs.getString("patient_system_id"), rs.getString("title")));
                });
        return rows;
    }

    /**
     * System ids of active administrators (account active and not closed), oldest account first, at most
     * {@link #MAX_ADMIN_RECIPIENTS}, leaving out {@code exceptSystemId}.
     */
    public List<String> activeAdminsExcept(String exceptSystemId) {
        return jdbc.queryForList(
                "SELECT system_id FROM users WHERE role = 'ADMIN' AND is_active = TRUE AND closed_at IS NULL "
                        + "AND system_id IS NOT NULL AND (CAST(:except AS VARCHAR) IS NULL OR system_id <> :except) "
                        + "ORDER BY user_id LIMIT :limit",
                new MapSqlParameterSource().addValue("except", exceptSystemId)
                        .addValue("limit", MAX_ADMIN_RECIPIENTS),
                String.class);
    }

    private static String fullName(String first, String last) {
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? null : name;
    }
}
