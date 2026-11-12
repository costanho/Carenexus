package com.carenexus.api.core.results;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Test data for lab and imaging results: request bodies and rows of tables this slice only reads (documents,
 * care_team). Documents are inserted with native SQL that works before and after be-documents adds
 * {@code storage_key} (V41): when the column exists, a stored file key is filled in, as the documents API does.
 */
public final class ClinicalResultFixtures {

    private final JdbcTemplate jdbc;

    public ClinicalResultFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A complete, valid lab result body for the patient (mutable, so a test can change one field). */
    public static Map<String, Object> labBody(String patientSystemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("testName", "Full Blood Count (FBC)");
        body.put("testType", "BLOOD");
        body.put("resultData", "{\"haemoglobin\":{\"value\":10.8,\"unit\":\"g/dL\",\"reference\":\"12.0-16.0 g/dL\",\"flag\":\"LOW\"}}");
        return body;
    }

    /** A complete, valid imaging result body for the patient (mutable). */
    public static Map<String, Object> imagingBody(String patientSystemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("imagingType", "XRAY");
        body.put("bodyPart", "Chest");
        body.put("findings", "Lungs clear.");
        body.put("impression", "Normal chest.");
        return body;
    }

    /** Inserts a stored, not deleted document of the patient and returns its id. */
    public int document(String patientSystemId, String uploadedBySystemId) {
        boolean hasStorageKey = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'documents' "
                        + "AND column_name = 'storage_key')", Boolean.class));
        if (hasStorageKey) {
            return jdbc.queryForObject(
                    "INSERT INTO documents (patient_system_id, uploaded_by_system_id, document_type, title, file_url, "
                            + "is_deleted, uploaded_at, storage_key) VALUES (?, ?, 'LAB_REPORT', 'Lab report', "
                            + "'local://test', FALSE, now(), ?) RETURNING document_id",
                    Integer.class, patientSystemId, uploadedBySystemId, "test/" + UUID.randomUUID());
        }
        return jdbc.queryForObject(
                "INSERT INTO documents (patient_system_id, uploaded_by_system_id, document_type, title, file_url, "
                        + "is_deleted, uploaded_at) VALUES (?, ?, 'LAB_REPORT', 'Lab report', 'local://test', FALSE, "
                        + "now()) RETURNING document_id",
                Integer.class, patientSystemId, uploadedBySystemId);
    }

    /** Soft-deletes a document the way DELETE /api/documents/{id} does. */
    public void softDeleteDocument(int documentId) {
        jdbc.update("UPDATE documents SET is_deleted = TRUE WHERE document_id = ?", documentId);
    }

    /** Adds an active care-team assignment of the doctor to the patient. */
    public void careTeam(String patientSystemId, String doctorSystemId) {
        jdbc.update("INSERT INTO care_team (patient_system_id, provider_system_id, role, joined_date, is_primary, "
                + "assigned_at, is_active, created_at) VALUES (?, ?, 'DOCTOR', CURRENT_DATE, FALSE, now(), TRUE, now())",
                patientSystemId, doctorSystemId);
    }

    /** Ends every care-team assignment of the doctor to the patient. */
    public void endCareTeam(String patientSystemId, String doctorSystemId) {
        jdbc.update("UPDATE care_team SET is_active = FALSE, unassigned_at = now() "
                + "WHERE patient_system_id = ? AND provider_system_id = ?", patientSystemId, doctorSystemId);
    }

    /** Revokes the caregiver's grant for the patient. */
    public void revokeGrant(String caregiverSystemId, String patientSystemId) {
        jdbc.update("UPDATE dependent_access SET is_active = FALSE, revoked_at = now() "
                + "WHERE caregiver_system_id = ? AND patient_system_id = ?", caregiverSystemId, patientSystemId);
    }

    /** Number of audit rows with this action and resource type for the patient. */
    public int auditRows(String action, String resourceType, String patientSystemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? "
                + "AND patient_system_id = ? AND status = 'SUCCESS'", Integer.class, action, resourceType, patientSystemId);
        return count == null ? 0 : count;
    }
}
