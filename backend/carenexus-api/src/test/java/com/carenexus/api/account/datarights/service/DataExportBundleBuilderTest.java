package com.carenexus.api.account.datarights.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The column filter that keeps secrets and storage references out of every export section. */
class DataExportBundleBuilderTest {

    @Test
    void secretAndStorageColumns_areNeverExported() {
        for (String column : List.of("password_hash", "token_hash", "code_hash", "token", "provider_token",
                "refresh_token", "mfa_secret", "totp_secret_encrypted", "recovery_code_hashes", "result_ref",
                "storage_key", "file_key", "file_url", "PASSWORD_HASH")) {
            assertTrue(DataExportBundleBuilder.isSecret(column), column);
        }
    }

    @Test
    void ordinaryColumns_includingPasswordMetadata_areExported() {
        for (String column : List.of("system_id", "email", "password_changed_at", "must_change_password",
                "client_message_id", "dedupe_key", "title", "icd_codes", "sha256", "is_revoked", "session_id",
                "messaging_approved_by_system_id", "action_url")) {
            assertFalse(DataExportBundleBuilder.isSecret(column), column);
        }
    }

    @Test
    void patientVisibility_hidesWhatTheApiHidesFromPatients() {
        assertEquals(" AND (status IN ('COMPLETED', 'CANCELLED'))",
                DataExportBundleBuilder.patientVisibility("consultations", Set.of("patient_system_id", "status")));
        assertEquals(" AND (status <> 'DRAFT' AND (status <> 'CANCELLED' OR issued_at IS NOT NULL))",
                DataExportBundleBuilder.patientVisibility("invoices", Set.of("patient_system_id", "status", "issued_at")));
        assertEquals(" AND (recipient_system_id = patient_system_id)",
                DataExportBundleBuilder.patientVisibility("reminders", Set.of("patient_system_id", "recipient_system_id")));
        assertEquals(" AND is_deleted = FALSE",
                DataExportBundleBuilder.patientVisibility("documents", Set.of("patient_system_id", "is_deleted")));
        assertEquals("", DataExportBundleBuilder.patientVisibility("prescriptions", Set.of("patient_system_id")));
    }

    @Test
    void patientVisibility_missingRuleColumn_exportsNothingRatherThanEverything() {
        assertNull(DataExportBundleBuilder.patientVisibility("invoices", Set.of("patient_system_id", "status")));
        assertNull(DataExportBundleBuilder.patientVisibility("reminders", Set.of("patient_id")));
    }
}
