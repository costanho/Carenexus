package com.carenexus.api.account.datarights;

import com.carenexus.api.account.datarights.job.DataExportExpirySweeper;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.carenexus.api.storage.FileStorage;
import com.carenexus.api.storage.StoredFileNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Data export, closure and deletion flows with committed transactions: the export holds only the caller's rows,
 * downloads are owner-only and audited, a newer export or the expiry sweep retires the older file, closure ends
 * every session and blocks sign-in with ACCOUNT_CLOSED, the last administrator cannot close, and an approved deletion
 * anonymises the users row (freeing the email) without touching clinical rows.
 */
@RecordApplicationEvents
class AccountDataRightsIntegrationTest extends CommittedIntegrationTest {

    private static final String EXPORTS = "/api/users/me/data-export";
    private static final String CLOSE = "/api/users/me/close";
    private static final String DELETION = "/api/users/me/deletion-request";
    private static final String ADMIN_REQUESTS = "/api/admin/account-requests";
    private static final String PASSWORD = AuthApi.FIXTURE_PASSWORD;

    @Autowired
    private FileStorage fileStorage;

    @Autowired
    private DataExportExpirySweeper sweeper;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    @Autowired
    private ApplicationEvents events;

    private AuthApi api;

    @BeforeEach
    void setUpApi() {
        api = new AuthApi(mockMvc, objectMapper);
    }

    // ==================== HELPERS ====================

    private static Map<String, Object> body(String password, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("password", password);
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private JsonNode exportOk(User as) throws Exception {
        MvcResult result = doPost(EXPORTS, as, body(PASSWORD, null));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private MvcResult download(User as, int requestId) throws Exception {
        return doGet(EXPORTS + "/" + requestId + "/download", as);
    }

    private JsonNode downloadBundle(User as, int requestId) throws Exception {
        MvcResult result = download(as, requestId);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String resultRef(int requestId) {
        return jdbc.queryForObject("SELECT result_ref FROM account_requests WHERE request_id = ?", String.class, requestId);
    }

    private int auditRows(String systemId, String action, String status) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE system_id IS NOT DISTINCT FROM ? "
                + "AND action = ? AND status = ?", Integer.class, systemId, action, status);
        return count == null ? 0 : count;
    }

    private int outboundEmails(String template, String to) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages WHERE template_id = ? "
                + "AND recipient_address = ?", Integer.class, template, to);
        return count == null ? 0 : count;
    }

    private void seedOwnData() {
        jdbc.update("INSERT INTO emergency_contacts (patient_system_id, name, phone) VALUES (?, 'Alice Contact', '+254700000001')",
                patient.getSystemId());
        jdbc.update("INSERT INTO emergency_contacts (patient_system_id, name, phone) VALUES (?, 'Bob Contact', '+254700000002')",
                otherPatient.getSystemId());
        jdbc.update("INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, title, icd_codes) "
                + "VALUES (?, ?, 'NOTE', 'Own record', CAST('[\"I10\"]' AS jsonb))", patient.getSystemId(), doctor.getSystemId());
        jdbc.update("INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, title) "
                + "VALUES (?, ?, 'NOTE', 'Someone else record')", otherPatient.getSystemId(), otherDoctor.getSystemId());
        jdbc.update("INSERT INTO health_metrics (patient_id, patient_system_id, type, value, unit) "
                + "SELECT patient_id, system_id, 'HEART_RATE', 72, 'bpm' FROM patients WHERE system_id = ?", patient.getSystemId());
        jdbc.update("INSERT INTO health_metrics (patient_id, patient_system_id, type, value, unit) "
                + "SELECT patient_id, system_id, 'HEART_RATE', 99, 'bpm' FROM patients WHERE system_id = ?", otherPatient.getSystemId());
        jdbc.update("INSERT INTO notifications (system_id, type, title, message) VALUES (?, 'SYSTEM', 'Own note', 'Hello')",
                patient.getSystemId());
        jdbc.update("INSERT INTO notifications (system_id, type, title, message) VALUES (?, 'SYSTEM', 'Other note', 'Hi')",
                otherPatient.getSystemId());
        jdbc.update("INSERT INTO audit_log (system_id, action, status, timestamp) VALUES (?, 'LOGIN', 'SUCCESS', now())",
                otherPatient.getSystemId());
        jdbc.update("INSERT INTO audit_log (system_id, action, status, timestamp) VALUES (?, 'LOGIN', 'SUCCESS', now())",
                patient.getSystemId());
    }

    // ==================== EXPORT ====================

    @Test
    void export_containsOnlyTheCallersRows_andNoSecrets() throws Exception {
        seedOwnData();
        grantApprovedAccess(caregiver, patient);

        JsonNode export = exportOk(patient);
        assertEquals("EXPORT", export.path("type").asText());
        assertEquals("READY", export.path("status").asText());
        assertTrue(export.path("downloadable").asBoolean());
        assertTrue(export.path("reason").isNull());
        assertTrue(export.path("decisionReason").isNull());
        assertTrue(export.path("fileName").asText().matches("carenexus-data-export-PAT-00001-\\d{8}\\.json"));
        assertTrue(export.path("requestedAt").asText().endsWith("Z"));
        assertFalse(export.has("resultRef"), "the storage key is never serialized");

        int id = export.path("requestId").asInt();
        MvcResult file = download(patient, id);
        assertEquals(200, file.getResponse().getStatus());
        assertTrue(file.getResponse().getContentType().startsWith("application/json"));
        assertEquals("attachment; filename=\"" + export.path("fileName").asText() + "\"",
                file.getResponse().getHeader("Content-Disposition"));
        assertEquals("nosniff", file.getResponse().getHeader("X-Content-Type-Options"));
        assertTrue(file.getResponse().getHeader("Cache-Control").contains("no-store"));

        String raw = file.getResponse().getContentAsString();
        assertFalse(raw.contains("PAT-00002"), "no row of another patient");
        assertFalse(raw.contains("Bob Contact"));
        assertFalse(raw.contains("Someone else record"));
        assertFalse(raw.contains("password_hash"));
        assertFalse(raw.contains("token_hash"));

        JsonNode bundle = objectMapper.readTree(raw);
        assertEquals(1, bundle.path("exportVersion").asInt());
        assertEquals("PAT-00001", bundle.path("systemId").asText());
        assertEquals("PATIENT", bundle.path("role").asText());
        JsonNode sections = bundle.path("sections");
        for (String key : List.of("user", "profile", "settings", "emergencyContacts", "appointments", "consultations",
                "prescriptions", "medicalRecords", "labResults", "imagingResults", "caregiverGrants", "messagesSent",
                "notifications", "auditLog")) {
            assertTrue(sections.has(key), "section " + key + " is always present");
        }
        assertEquals("PAT-00001", sections.path("user").path("system_id").asText());
        assertEquals("thomas@example.com", sections.path("user").path("email").asText());
        assertFalse(sections.path("user").has("password_hash"));
        assertEquals("PAT-00001", sections.path("profile").path("system_id").asText());
        assertTrue(sections.path("settings").isNull(), "no user_settings row yet");
        assertEquals(1, sections.path("emergencyContacts").size());
        assertEquals("Alice Contact", sections.path("emergencyContacts").get(0).path("name").asText());
        assertEquals(1, sections.path("medicalRecords").size());
        assertEquals("Own record", sections.path("medicalRecords").get(0).path("title").asText());
        assertEquals("I10", sections.path("medicalRecords").get(0).path("icd_codes").get(0).asText(),
                "JSON columns are embedded as JSON");
        assertEquals(1, sections.path("caregiverGrants").size());
        assertEquals("CGV-00001", sections.path("caregiverGrants").get(0).path("caregiver_system_id").asText());
        assertEquals(1, sections.path("notifications").size());
        assertEquals("Own note", sections.path("notifications").get(0).path("title").asText());
        for (JsonNode row : sections.path("auditLog")) {
            assertEquals("PAT-00001", row.path("system_id").asText());
        }
        assertTrue(sections.path("auditLog").size() >= 1);
        // Legacy patient_id table, filtered through the caller's patients row
        assertEquals(1, sections.path("healthMetrics").size());
        assertEquals(72, sections.path("healthMetrics").get(0).path("value").asInt());
        // A table of another slice (be-account-legal, merged in the same wave) is exported with the caller's rows only
        assertTrue(sections.path("legalAcceptances").isArray());
        for (JsonNode row : sections.path("legalAcceptances")) {
            assertEquals("PAT-00001", row.path("system_id").asText());
        }

        // Side effects: audit, email, SYSTEM notification with the security link
        assertEquals(1, auditRows("PAT-00001", "DATA_EXPORT_REQUEST", "SUCCESS"));
        assertEquals(1, auditRows("PAT-00001", "DATA_EXPORT_DOWNLOAD", "SUCCESS"));
        assertEquals(1, outboundEmails("account-export-ready", "thomas@example.com"));
        Map<String, Object> notification = jdbc.queryForMap("SELECT category, target, action_url, dedupe_key, expires_at "
                + "FROM notifications WHERE system_id = 'PAT-00001' AND title = 'Your data export is ready'");
        assertEquals("SYSTEM", notification.get("category"));
        assertEquals("ACCOUNT_SECURITY", notification.get("target"));
        assertEquals("/dashboard/patient?section=profile-settings&tab=security", notification.get("action_url"));
        assertEquals("DATA_EXPORT_READY:" + id, notification.get("dedupe_key"));
        assertNotNull(notification.get("expires_at"));
    }

    @Test
    void export_ofADoctor_neverContainsPatientsRecords() throws Exception {
        seedOwnData();
        JsonNode bundle = downloadBundle(doctor, exportOk(doctor).path("requestId").asInt());
        JsonNode sections = bundle.path("sections");
        assertEquals("DOCTOR", bundle.path("role").asText());
        assertEquals("DOC-00001", sections.path("profile").path("system_id").asText());
        for (String key : List.of("emergencyContacts", "appointments", "consultations", "prescriptions",
                "medicalRecords", "labResults", "imagingResults", "healthMetrics", "documents", "referrals")) {
            assertTrue(sections.path(key).isArray(), key);
            assertEquals(0, sections.path(key).size(), key + " is empty for a clinician");
        }
        assertFalse(bundle.toString().contains("Own record"), "the record the doctor wrote is the patient's data");
    }

    private int appointmentDaysAgo(int days) {
        Integer id = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, created_at, updated_at) VALUES (?, ?, "
                        + "date_trunc('hour', LOCALTIMESTAMP) - make_interval(days => ?), 30, 'IN_PERSON', 'COMPLETED', "
                        + "LOCALTIMESTAMP, LOCALTIMESTAMP) RETURNING appointment_id",
                Integer.class, patient.getSystemId(), doctor.getSystemId(), days);
        return id == null ? -1 : id;
    }

    private void consultation(int appointmentId, String status, String diagnosis) {
        jdbc.update("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status, diagnosis, "
                + "follow_up_required, created_at) VALUES (?, ?, ?, ?, ?, FALSE, LOCALTIMESTAMP)",
                appointmentId, patient.getSystemId(), doctor.getSystemId(), status, diagnosis);
    }

    private void invoice(String invoiceNo, String status, boolean issued) {
        jdbc.update("INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, invoice_no, "
                        + "line_items, subtotal, tax, discount, total, amount_paid, currency, status, issued_at, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, CAST('[]' AS jsonb), 100.00, 0.00, 0.00, 100.00, 0.00, 'KES', ?, "
                        + (issued ? "LOCALTIMESTAMP" : "NULL") + ", LOCALTIMESTAMP, LOCALTIMESTAMP)",
                patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), invoiceNo, status);
    }

    private void reminder(User recipient, String title) {
        jdbc.update("INSERT INTO reminders (patient_system_id, recipient_system_id, type, title, message, reminder_time, "
                        + "frequency, start_date, is_active) VALUES (?, ?, 'LAB_FOLLOWUP', ?, 'Message', '09:00', 'ONCE', "
                        + "CURRENT_DATE, FALSE)",
                patient.getSystemId(), recipient.getSystemId(), title);
    }

    private void document(String title, boolean deleted) {
        jdbc.update("INSERT INTO documents (patient_system_id, uploaded_by_system_id, document_type, title, file_url, "
                        + "is_deleted, deleted_at, uploaded_at) VALUES (?, ?, 'LAB_REPORT', ?, 'local://test', ?, "
                        + (deleted ? "LOCALTIMESTAMP" : "NULL") + ", LOCALTIMESTAMP)",
                patient.getSystemId(), doctor.getSystemId(), title, deleted);
    }

    private static List<String> values(JsonNode rows, String column) {
        List<String> values = new java.util.ArrayList<>();
        rows.forEach(row -> values.add(row.path(column).asText()));
        values.sort(null);
        return values;
    }

    @Test
    void export_ofAPatient_holdsOnlyTheRowsTheApiShowsThatPatient() throws Exception {
        int first = appointmentDaysAgo(3);
        consultation(first, "COMPLETED", "Final diagnosis");
        consultation(first, "CANCELLED", "Cancelled diagnosis");
        consultation(appointmentDaysAgo(2), "DRAFT", "Draft working note");
        consultation(appointmentDaysAgo(1), "IN_PROGRESS", "Provisional working note");
        invoice("INV-2026-990001", "SENT", true);
        invoice("INV-2026-990002", "DRAFT", false);
        invoice("INV-2026-990003", "CANCELLED", false);
        invoice("INV-2026-990004", "CANCELLED", true);
        reminder(patient, "Own reminder");
        reminder(doctor, "Doctor review reminder");
        document("Live document", false);
        document("Deleted document", true);

        JsonNode sections = downloadBundle(patient, exportOk(patient).path("requestId").asInt()).path("sections");

        assertEquals(List.of("Cancelled diagnosis", "Final diagnosis"),
                values(sections.path("consultations"), "diagnosis"), "unfinished consultations are the doctor's notes");
        assertEquals(List.of("INV-2026-990001", "INV-2026-990004"),
                values(sections.path("invoices"), "invoice_no"), "never-issued invoices are not the patient's yet");
        assertEquals(List.of("Own reminder"), values(sections.path("reminders"), "title"));
        assertEquals(List.of("Live document"), values(sections.path("documents"), "title"));
        String raw = sections.toString();
        assertFalse(raw.contains("working note"));
        assertFalse(raw.contains("Doctor review reminder"));
        assertFalse(raw.contains("Deleted document"));
    }

    @Test
    void export_ofACaregiver_andAnAdmin_holdOnlyTheirOwnAccount() throws Exception {
        grantApprovedAccess(caregiver, patient);
        JsonNode caregiverBundle = downloadBundle(caregiver, exportOk(caregiver).path("requestId").asInt());
        assertEquals("CGV-00001", caregiverBundle.path("sections").path("profile").path("system_id").asText());
        assertEquals(1, caregiverBundle.path("sections").path("caregiverGrants").size(), "own grant as caregiver");

        JsonNode otherCaregiverBundle = downloadBundle(otherCaregiver, exportOk(otherCaregiver).path("requestId").asInt());
        assertEquals(0, otherCaregiverBundle.path("sections").path("caregiverGrants").size());

        JsonNode adminBundle = downloadBundle(admin, exportOk(admin).path("requestId").asInt());
        assertTrue(adminBundle.path("sections").path("profile").isNull(), "administrators have no profile row");
        assertEquals("ADM-00001", adminBundle.path("sections").path("user").path("system_id").asText());
    }

    @Test
    void download_isOwnerOnly_forEveryOtherRole() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int id = exportOk(patient).path("requestId").asInt();
        for (User other : List.of(otherPatient, doctor, caregiver, otherCaregiver, admin)) {
            MvcResult result = download(other, id);
            assertEquals(404, result.getResponse().getStatus(), other.getSystemId());
            assertEquals("Export not found", message(result));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'DATA_EXPORT_DOWNLOAD'",
                Integer.class), "a refused download is not audited as a download");
        assertEquals(404, download(patient, id + 1000).getResponse().getStatus());
        assertEquals(400, doGet(EXPORTS + "/abc/download", patient).getResponse().getStatus());
        assertEquals("Invalid value for parameter 'id'", message(doGet(EXPORTS + "/abc/download", patient)));

        // Repeatable, each download audited
        assertEquals(200, download(patient, id).getResponse().getStatus());
        assertEquals(200, download(patient, id).getResponse().getStatus());
        assertEquals(2, auditRows("PAT-00001", "DATA_EXPORT_DOWNLOAD", "SUCCESS"));
    }

    @Test
    void export_wrongPassword_is400PasswordIncorrect_auditedAndCreatesNothing() throws Exception {
        MvcResult result = doPost(EXPORTS, patient, body("not-the-password", null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("Password is incorrect", message(result));
        assertEquals("PASSWORD_INCORRECT", json(result).path("code").asText());
        assertEquals(1, auditRows("PAT-00001", "DATA_EXPORT_REQUEST", "FAILURE"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_requests", Integer.class));
    }

    @Test
    void newerExport_retiresTheOlderOne_andItsFile() throws Exception {
        int first = exportOk(patient).path("requestId").asInt();
        String firstKey = resultRef(first);
        assertTrue(firstKey.startsWith("exports/"));
        int second = exportOk(patient).path("requestId").asInt();

        MvcResult old = download(patient, first);
        assertEquals(409, old.getResponse().getStatus());
        assertEquals("This export is no longer available. Request a new one.", message(old));
        assertThrows(StoredFileNotFoundException.class, () -> fileStorage.open(firstKey));

        JsonNode list = json(doGet(EXPORTS, patient));
        assertEquals(2, list.size());
        assertEquals(second, list.get(0).path("requestId").asInt());
        assertTrue(list.get(0).path("downloadable").asBoolean());
        assertEquals("COMPLETED", list.get(1).path("status").asText());
        assertFalse(list.get(1).path("downloadable").asBoolean());
        assertEquals(0, json(doGet(EXPORTS, otherPatient)).size(), "another user's list is empty");
    }

    @Test
    void expiredExport_isNotDownloadable_andTheSweeperDeletesItsFile() throws Exception {
        int id = exportOk(patient).path("requestId").asInt();
        String key = resultRef(id);
        jdbc.update("UPDATE account_requests SET expires_at = ? WHERE request_id = ?",
                LocalDateTime.now().minusMinutes(1), id);

        JsonNode row = json(doGet(EXPORTS, patient)).get(0);
        assertEquals("READY", row.path("status").asText());
        assertFalse(row.path("downloadable").asBoolean(), "the server decides, before the sweep");
        assertEquals(409, download(patient, id).getResponse().getStatus());

        assertEquals(1, sweeper.sweepOnce());
        assertEquals(0, sweeper.sweepOnce(), "idempotent");
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM account_requests WHERE request_id = ?",
                String.class, id));
        assertThrows(StoredFileNotFoundException.class, () -> fileStorage.open(key));
    }

    @Test
    void passwordConfirmations_shareThePasswordChangeBucket() throws Exception {
        rateLimitProperties.setEnabled(true);
        try {
            for (int i = 0; i < 5; i++) {
                assertEquals(400, doPost(DELETION, otherCaregiver, body("wrong", null)).getResponse().getStatus());
            }
            MvcResult limited = doPost(EXPORTS, otherCaregiver, body(PASSWORD, null));
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));
        } finally {
            rateLimitProperties.setEnabled(false);
            rateLimitGuard.reset("password-change", otherCaregiver.getSystemId());
        }
    }

    // ==================== CLOSURE ====================

    @Test
    void closure_endsEverySession_blocksSignIn_andRetainsClinicalRows() throws Exception {
        seedOwnData();
        JsonNode session = api.json(api.login("thomas@example.com", PASSWORD));
        String refreshToken = session.path("tokens").path("refreshToken").asText();
        String accessToken = session.path("tokens").path("accessToken").asText();
        assertFalse(refreshToken.isBlank());

        MvcResult result = doPost(CLOSE, patient, body(PASSWORD, "  Moving to another clinic  "));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode closed = json(result);
        assertTrue(closed.path("clinicalRecordsRetained").asBoolean());
        assertEquals("Your account is closed. Your clinical records are retained as required for medical record "
                + "keeping and were not deleted.", closed.path("message").asText());
        assertTrue(closed.path("closedAt").asText().endsWith("Z"));

        Map<String, Object> user = jdbc.queryForMap("SELECT is_active, closed_at, closure_reason FROM users WHERE system_id = 'PAT-00001'");
        assertEquals(false, user.get("is_active"));
        assertNotNull(user.get("closed_at"));
        assertEquals("Moving to another clinic", user.get("closure_reason"));
        Map<String, Object> row = jdbc.queryForMap("SELECT type, status, reason, processed_by_system_id FROM account_requests "
                + "WHERE request_id = ?", closed.path("requestId").asInt());
        assertEquals("CLOSURE", row.get("type"));
        assertEquals("COMPLETED", row.get("status"));
        assertEquals("PAT-00001", row.get("processed_by_system_id"));

        // Sessions: the access token is dead at once, the refresh token is revoked, sign-in says ACCOUNT_CLOSED
        assertEquals(401, api.me(accessToken).getResponse().getStatus());
        assertEquals(401, api.refresh(refreshToken).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' "
                + "AND is_revoked = FALSE", Integer.class));
        MvcResult login = api.login("thomas@example.com", PASSWORD);
        assertEquals(403, login.getResponse().getStatus());
        assertEquals("ACCOUNT_CLOSED", api.json(login).path("code").asText());

        // Clinical rows retained
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM medical_records WHERE patient_system_id = 'PAT-00001'",
                Integer.class));
        assertEquals(1, auditRows("PAT-00001", "ACCOUNT_CLOSE", "SUCCESS"));
        assertEquals(1, outboundEmails("account-closed", "thomas@example.com"));
        assertEquals(1, events.stream(UserDeactivatedEvent.class)
                .filter(e -> e.systemId().equals("PAT-00001") && e.actorSystemId().equals("PAT-00001")
                        && UserDeactivatedEvent.CLOSURE.equals(e.cause()) && "PATIENT".equals(e.role()))
                .count());
        assertTrue(events.stream(UserLoggedOutEvent.class).findAny().isPresent());
    }

    @Test
    void closure_ofADoctor_takesTheDoctorOutOfTheDirectory() throws Exception {
        assertEquals(200, doPost(CLOSE, doctor, body(PASSWORD, null)).getResponse().getStatus());
        Map<String, Object> profile = jdbc.queryForMap("SELECT is_active, verification_status FROM doctors WHERE system_id = 'DOC-00001'");
        assertEquals(false, profile.get("is_active"));
        assertEquals("APPROVED", profile.get("verification_status"), "verification status is not touched");
        assertNull(jdbc.queryForObject("SELECT closure_reason FROM users WHERE system_id = 'DOC-00001'", String.class),
                "absent reason is stored as null");
    }

    @Test
    void lastActiveAdmin_cannotClose_butOneOfTwoCan() throws Exception {
        MvcResult refused = doPost(CLOSE, admin, body(PASSWORD, null));
        assertEquals(409, refused.getResponse().getStatus());
        assertEquals("The last active administrator cannot close their account", message(refused));
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM users WHERE system_id = 'ADM-00001'", Boolean.class));

        User secondAdmin = createAndSaveUser(8, "ADM-00002", "admin2@example.com", "ADMIN");
        assertEquals(200, doPost(CLOSE, admin, body(PASSWORD, null)).getResponse().getStatus());
        MvcResult nowLast = doPost(CLOSE, secondAdmin, body(PASSWORD, null));
        assertEquals(409, nowLast.getResponse().getStatus());
    }

    @Test
    void closure_wrongPassword_changesNothing() throws Exception {
        MvcResult result = doPost(CLOSE, patient, body("wrong-password", null));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("PASSWORD_INCORRECT", json(result).path("code").asText());
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM users WHERE system_id = 'PAT-00001'", Boolean.class));
        assertEquals(1, auditRows("PAT-00001", "ACCOUNT_CLOSE", "FAILURE"));
    }

    // ==================== DELETION ====================

    @Test
    void deletionRequest_lifecycle_requestCancelRejectAndDuplicates() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "admin2@example.com", "ADMIN");

        MvcResult created = doPost(DELETION, patient, body(PASSWORD, "  I no longer use the service "));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode request = json(created);
        int id = request.path("requestId").asInt();
        assertEquals("DELETION", request.path("type").asText());
        assertEquals("REQUESTED", request.path("status").asText());
        assertEquals("I no longer use the service", request.path("reason").asText());
        assertTrue(request.path("processedAt").isNull());
        assertTrue(request.path("expiresAt").isNull());
        assertTrue(request.path("fileName").isNull());
        assertFalse(request.path("downloadable").asBoolean());
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM users WHERE system_id = 'PAT-00001'", Boolean.class),
                "nothing is deactivated by a request");

        // Both administrators are notified with the deep link
        for (String adminId : List.of("ADM-00001", "ADM-00002")) {
            Map<String, Object> n = jdbc.queryForMap("SELECT target, action_url, message FROM notifications "
                    + "WHERE system_id = ? AND dedupe_key = ?", adminId, "ACCOUNT_DELETION_REQUEST:" + id);
            assertEquals("ADMIN_ACCOUNT_REQUESTS", n.get("target"));
            assertEquals("/dashboard/admin?section=account-requests&id=" + id, n.get("action_url"));
            assertEquals("A user (PAT-00001) asked for their account to be deleted.", n.get("message"));
        }

        MvcResult duplicate = doPost(DELETION, patient, body(PASSWORD, null));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("You already have a deletion request awaiting review", message(duplicate));

        // Cancel: owner only, idempotent
        MvcResult foreign = doPost(DELETION + "/" + id + "/cancel", otherPatient, null);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals("Deletion request not found", message(foreign));
        JsonNode cancelled = json(doPost(DELETION + "/" + id + "/cancel", patient, null));
        assertEquals("CANCELLED", cancelled.path("status").asText());
        assertFalse(cancelled.path("processedAt").isNull());
        assertEquals(200, doPost(DELETION + "/" + id + "/cancel", patient, null).getResponse().getStatus());
        assertEquals(1, auditRows("PAT-00001", "ACCOUNT_DELETION_CANCEL", "SUCCESS"), "a repeat writes no audit row");
        MvcResult approveCancelled = doPost(ADMIN_REQUESTS + "/" + id + "/approve", admin, null);
        assertEquals(409, approveCancelled.getResponse().getStatus());
        assertEquals("This request was cancelled by the user", message(approveCancelled));

        // A new request is allowed after a cancel; reject it
        int second = json(doPost(DELETION, patient, body(PASSWORD, null))).path("requestId").asInt();
        MvcResult rejected = doPost(ADMIN_REQUESTS + "/" + second + "/reject", secondAdmin,
                Map.of("reason", "  An invoice is still open.  "));
        assertEquals(200, rejected.getResponse().getStatus(), rejected.getResponse().getContentAsString());
        JsonNode rejectedBody = json(rejected);
        assertEquals("REJECTED", rejectedBody.path("status").asText());
        assertEquals("An invoice is still open.", rejectedBody.path("decisionReason").asText());
        assertEquals("ADM-00002", rejectedBody.path("processedBySystemId").asText());
        assertTrue(rejectedBody.path("isActive").asBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = 'PAT-00001' "
                + "AND dedupe_key = ?", Integer.class, "ACCOUNT_DELETION_REJECTED:" + second));

        // Same-status reject keeps the first reason; other transitions are 409
        JsonNode again = json(doPost(ADMIN_REQUESTS + "/" + second + "/reject", admin, Map.of("reason", "Other")));
        assertEquals("An invoice is still open.", again.path("decisionReason").asText());
        assertEquals(1, auditRows("ADM-00002", "ACCOUNT_DELETION_REJECT", "SUCCESS")
                + auditRows("ADM-00001", "ACCOUNT_DELETION_REJECT", "SUCCESS"));
        MvcResult approveRejected = doPost(ADMIN_REQUESTS + "/" + second + "/approve", admin, null);
        assertEquals(409, approveRejected.getResponse().getStatus());
        assertEquals("This request was already rejected", message(approveRejected));
        MvcResult cancelRejected = doPost(DELETION + "/" + second + "/cancel", patient, null);
        assertEquals(409, cancelRejected.getResponse().getStatus());
        assertEquals("This request has already been decided", message(cancelRejected));

        // The user's own list: newest first, decision reason visible
        JsonNode own = json(doGet(DELETION, patient));
        assertEquals(2, own.size());
        assertEquals("REJECTED", own.get(0).path("status").asText());
        assertEquals("An invoice is still open.", own.get(0).path("decisionReason").asText());
        assertEquals("CANCELLED", own.get(1).path("status").asText());
    }

    @Test
    void approvedDeletion_anonymisesTheAccount_freesTheEmail_andKeepsClinicalRows() throws Exception {
        seedOwnData();
        JsonNode session = api.json(api.login("thomas@example.com", PASSWORD));
        String refreshToken = session.path("tokens").path("refreshToken").asText();
        int id = json(doPost(DELETION, patient, body(PASSWORD, null))).path("requestId").asInt();

        MvcResult result = doPost(ADMIN_REQUESTS + "/" + id + "/approve", admin, null);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode approved = json(result);
        assertEquals("COMPLETED", approved.path("status").asText());
        assertEquals("Deleted", approved.path("firstName").asText());
        assertEquals("User", approved.path("lastName").asText());
        assertTrue(approved.path("email").isNull());
        assertFalse(approved.path("isActive").asBoolean());
        assertEquals("PAT-00001", approved.path("systemId").asText());
        assertEquals("ADM-00001", approved.path("processedBySystemId").asText());

        Map<String, Object> user = jdbc.queryForMap("SELECT first_name, last_name, email, phone, password_hash, is_active, "
                + "closed_at, closure_reason FROM users WHERE system_id = 'PAT-00001'");
        assertEquals("Deleted", user.get("first_name"));
        assertEquals("User", user.get("last_name"));
        assertNull(user.get("email"));
        assertNull(user.get("phone"));
        assertNull(user.get("password_hash"));
        assertEquals(false, user.get("is_active"));
        assertNotNull(user.get("closed_at"));
        assertEquals("Account deleted on request", user.get("closure_reason"));

        // Nothing else changed: profile and clinical rows still point at the same system id
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM patients WHERE system_id = 'PAT-00001'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM medical_records WHERE patient_system_id = 'PAT-00001'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM emergency_contacts WHERE patient_system_id = 'PAT-00001'",
                Integer.class));

        // Sessions ended, old credentials are an ordinary invalid sign-in, goodbye email to the former address
        assertEquals(401, api.refresh(refreshToken).getResponse().getStatus());
        assertEquals(401, api.login("thomas@example.com", PASSWORD).getResponse().getStatus());
        assertEquals(1, outboundEmails("account-closed-deletion", "thomas@example.com"));
        assertEquals(1, auditRows("ADM-00001", "ACCOUNT_DELETION_APPROVE", "SUCCESS"));
        assertEquals("PAT-00001", jdbc.queryForObject("SELECT target_system_id FROM audit_log "
                + "WHERE action = 'ACCOUNT_DELETION_APPROVE'", String.class));
        assertEquals(1, events.stream(UserDeactivatedEvent.class)
                .filter(e -> e.systemId().equals("PAT-00001") && e.actorSystemId().equals("ADM-00001")
                        && UserDeactivatedEvent.CLOSURE.equals(e.cause()))
                .count());

        // The email and the phone are free for a new registration
        User reused = createAndSaveUser(1, "PAT-00009", "thomas@example.com", "PATIENT");
        assertNotNull(reused.getUserId());

        // Same-status approve is a no-op
        JsonNode again = json(doPost(ADMIN_REQUESTS + "/" + id + "/approve", admin, null));
        assertEquals("COMPLETED", again.path("status").asText());
        assertEquals(1, auditRows("ADM-00001", "ACCOUNT_DELETION_APPROVE", "SUCCESS"));
        MvcResult rejectApproved = doPost(ADMIN_REQUESTS + "/" + id + "/reject", admin, Map.of("reason", "Too late"));
        assertEquals(409, rejectApproved.getResponse().getStatus());
        assertEquals("This request was already approved", message(rejectApproved));
    }

    @Test
    void approvingAnAlreadyClosedAccount_publishesNoDeactivationEvent_andAnonymisesADoctor() throws Exception {
        int id = json(doPost(DELETION, doctor, body(PASSWORD, null))).path("requestId").asInt();
        assertEquals(200, doPost(CLOSE, doctor, body(PASSWORD, "Retiring")).getResponse().getStatus());
        LocalDateTime closedAt = jdbc.queryForObject("SELECT closed_at FROM users WHERE system_id = 'DOC-00001'",
                LocalDateTime.class);

        JsonNode adminView = json(doGet(ADMIN_REQUESTS + "/" + id, admin));
        assertEquals("REQUESTED", adminView.path("status").asText(), "closing does not change a pending request");
        assertFalse(adminView.path("isActive").asBoolean());

        assertEquals(200, doPost(ADMIN_REQUESTS + "/" + id + "/approve", admin, null).getResponse().getStatus());
        assertEquals(0, events.stream(UserDeactivatedEvent.class)
                .filter(e -> e.systemId().equals("DOC-00001") && "ADM-00001".equals(e.actorSystemId()))
                .count(), "no event when the account was already inactive");
        assertEquals(closedAt, jdbc.queryForObject("SELECT closed_at FROM users WHERE system_id = 'DOC-00001'",
                LocalDateTime.class), "closed_at is kept when already set");
        assertEquals(false, jdbc.queryForObject("SELECT is_active FROM doctors WHERE system_id = 'DOC-00001'", Boolean.class));
    }

    @Test
    void anAdministrator_cannotDecideTheirOwnRequest() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "admin2@example.com", "ADMIN");
        int id = json(doPost(DELETION, secondAdmin, body(PASSWORD, null))).path("requestId").asInt();
        // The requester is not notified about their own request
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = 'ADM-00002'", Integer.class));

        MvcResult own = doPost(ADMIN_REQUESTS + "/" + id + "/approve", secondAdmin, null);
        assertEquals(409, own.getResponse().getStatus());
        assertEquals("You cannot decide your own request", message(own));
        MvcResult ownReject = doPost(ADMIN_REQUESTS + "/" + id + "/reject", secondAdmin, Map.of("reason", "No"));
        assertEquals(409, ownReject.getResponse().getStatus());
        assertEquals("You cannot decide your own request", message(ownReject));

        assertEquals(200, doPost(ADMIN_REQUESTS + "/" + id + "/approve", admin, null).getResponse().getStatus());
    }

    @Test
    void exportsAndClosures_cannotBeApprovedOrRejected() throws Exception {
        int exportId = exportOk(patient).path("requestId").asInt();
        MvcResult approve = doPost(ADMIN_REQUESTS + "/" + exportId + "/approve", admin, null);
        assertEquals(409, approve.getResponse().getStatus());
        assertEquals("Only deletion requests can be approved or rejected", message(approve));
        MvcResult reject = doPost(ADMIN_REQUESTS + "/" + exportId + "/reject", admin, Map.of("reason", "No"));
        assertEquals(409, reject.getResponse().getStatus());
        assertEquals("Only deletion requests can be approved or rejected", message(reject));
        assertEquals(404, doPost(ADMIN_REQUESTS + "/99999/approve", admin, null).getResponse().getStatus());
        assertEquals("Account request not found", message(doPost(ADMIN_REQUESTS + "/99999/approve", admin, null)));
    }
}
