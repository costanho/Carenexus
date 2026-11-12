package com.carenexus.api.documents.repository;

import com.carenexus.api.documents.model.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * Native existence checks on tables this module does not own: the patient account an upload is for, and the item a
 * {@code linkId} names. Each check is one query that also requires the row to belong to the patient.
 */
@Repository
@RequiredArgsConstructor
public class DocumentReferenceRepository {

    /** One query per link type; the parameters are (linkId, patientSystemId). */
    private static final Map<String, String> LINK_QUERIES = Map.of(
            Document.LINK_LAB_RESULT,
            "SELECT EXISTS (SELECT 1 FROM lab_results WHERE result_id = ? AND patient_system_id = ?)",
            Document.LINK_IMAGING_RESULT,
            "SELECT EXISTS (SELECT 1 FROM imaging_results WHERE imaging_id = ? AND patient_system_id = ?)",
            Document.LINK_MEDICAL_RECORD,
            "SELECT EXISTS (SELECT 1 FROM medical_records WHERE record_id = ? AND patient_system_id = ? "
                    + "AND is_deleted = FALSE)",
            Document.LINK_CONSULTATION,
            "SELECT EXISTS (SELECT 1 FROM consultations WHERE consultation_id = ? AND patient_system_id = ?)",
            Document.LINK_INSURANCE_CLAIM,
            "SELECT EXISTS (SELECT 1 FROM insurance_claims WHERE claim_id = ? AND patient_system_id = ?)");

    private final JdbcTemplate jdbc;

    /** True when an account with this system id and role PATIENT exists. */
    public boolean patientExists(String patientSystemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM users WHERE system_id = ? AND UPPER(role) = 'PATIENT')",
                Boolean.class, patientSystemId));
    }

    /** True when the item of this link type and id exists and belongs to the patient. */
    public boolean linkTargetExists(String linkType, Integer linkId, String patientSystemId) {
        String sql = LINK_QUERIES.get(linkType);
        if (sql == null) {
            throw new IllegalArgumentException("Unknown link type: " + linkType);
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, linkId, patientSystemId));
    }
}
