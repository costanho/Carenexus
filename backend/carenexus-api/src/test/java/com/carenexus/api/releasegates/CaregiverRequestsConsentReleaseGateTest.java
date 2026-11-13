package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.caregiveraccess.dto.response.AccessRequestAcceptedResponse;
import com.carenexus.api.caregiveraccess.job.AccessExpiryNoticeJob;
import com.carenexus.api.caregiveraccess.job.AccessRequestExpiryJob;
import com.carenexus.api.communications.job.CaregiverAccessExpirySweeper;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.model.LabResult;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.LabResultRepository;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gates for be-caregiver-requests-consent: caregiver access requests (request, approve with the patient's
 * permissions, decline, cancel, duplicate reuse, unmatched emails, expiry), the consent ledger written for every
 * grant step by the real CaregiverService, the access-change notifications and their toggle, and the patient's view
 * of a caregiver's activity. Committed transactions (CommittedIntegrationTest), so listeners run as in production.
 *
 * Care circle: patient Thomas PAT-00001 (thomas@example.com), caregiver Sarah CGV-00001, other patient PAT-00002,
 * other caregiver CGV-00002, doctor DOC-00001 (care team of Thomas), admin ADM-00001.
 */
class CaregiverRequestsConsentReleaseGateTest extends CommittedIntegrationTest {

    private static final String CG_REQUESTS = "/api/caregiver/access-requests";
    private static final String PT_REQUESTS = "/api/patients/me/caregiver-access/requests";
    private static final String GRANTS = "/api/patients/me/caregiver-access";
    private static final String CONSENTS = "/api/patients/me/consents";

    @Autowired
    private LabResultRepository labResultRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private CaregiverAccessExpirySweeper caregiverAccessExpirySweeper;

    @Autowired
    private AccessRequestExpiryJob accessRequestExpiryJob;

    @Autowired
    private AccessExpiryNoticeJob accessExpiryNoticeJob;

    // ==================== HELPERS ====================

    private Map<String, Object> requestBody(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientEmail", email);
        body.put("relationship", " daughter ");
        body.put("accessLevel", "custom");
        body.put("canViewLabResults", true);
        body.put("canViewImaging", true);
        body.put("canScheduleAppointments", true);
        body.put("canMessageCareTeam", true);
        body.put("message", "  Dad asked me to help with his appointments.  ");
        return body;
    }

    private void assertAccepted(MvcResult result) throws Exception {
        assertEquals(202, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(AccessRequestAcceptedResponse.TEXT, message(result));
        assertEquals(1, json(result).size(), "the 202 body carries only the message");
    }

    private void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** Caregiver asks Thomas; returns the request id as the patient sees it. */
    private int requestForThomas(User asCaregiver) throws Exception {
        assertAccepted(doPost(CG_REQUESTS, asCaregiver, requestBody(patient.getEmail())));
        return jdbc.queryForObject("SELECT request_id FROM caregiver_access_requests WHERE caregiver_system_id = ? "
                + "AND status = 'PENDING' ORDER BY request_id DESC LIMIT 1", Integer.class, asCaregiver.getSystemId());
    }

    private Map<String, Object> approveBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("accessLevel", "CUSTOM");
        body.put("canViewLabResults", true);
        body.put("canMessageCareTeam", true);
        return body;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int notifications(String recipient, String dedupeLike) {
        return count("SELECT count(*) FROM notifications WHERE system_id = ? AND dedupe_key LIKE ?", recipient, dedupeLike);
    }

    private List<String> ledgerKinds(int accessId) {
        return jdbc.queryForList("SELECT event_kind FROM consent_records WHERE access_id = ? ORDER BY consent_id",
                String.class, accessId);
    }

    private void switchOffAccessChangeAlerts(User user) {
        UserSettings settings = userSettingsRepository.findBySystemId(user.getSystemId())
                .orElseGet(() -> UserSettings.builder().systemId(user.getSystemId()).build());
        settings.setAccessChangeAlerts(false);
        userSettingsRepository.saveAndFlush(settings);
    }

    private LabResult labResult(User forPatient) {
        return labResultRepository.saveAndFlush(LabResult.builder()
                .patientSystemId(forPatient.getSystemId())
                .doctorSystemId(doctor.getSystemId())
                .testName("Full blood count")
                .testType("BLOOD")
                .status("COMPLETED")
                .isCritical(false)
                .testDate(LocalDate.now())
                .build());
    }

    // ==================== REQUEST, APPROVE, SCOPED READS ====================

    @Test
    void requestThenApprove_caregiverReadsOnlyTheGrantedFlags() throws Exception {
        int requestId = requestForThomas(caregiver);

        // The patient sees the request with the caregiver's name and email, and was notified once.
        JsonNode pending = json(doGet(PT_REQUESTS + "?status=pending", patient));
        assertEquals(1, pending.path("totalElements").asInt());
        JsonNode row = pending.path("content").get(0);
        assertEquals(requestId, row.path("requestId").asInt());
        assertEquals("PENDING", row.path("status").asText());
        assertEquals("CAREGIVER User3", row.path("caregiverName").asText());
        assertEquals(caregiver.getEmail(), row.path("caregiverEmail").asText());
        assertEquals(patient.getSystemId(), row.path("patientSystemId").asText());
        assertTrue(row.path("patientName").isNull());
        assertEquals("daughter", row.path("relationship").asText());
        assertEquals("CUSTOM", row.path("accessLevel").asText());
        assertTrue(row.path("canViewImaging").asBoolean());
        assertTrue(row.path("canMessageCareTeam").asBoolean());
        assertEquals("Dad asked me to help with his appointments.", row.path("message").asText());
        assertTrue(row.path("accessId").isNull());
        assertTrue(row.path("respondedAt").isNull());
        assertEquals(LocalDateTime.parse(row.path("createdAt").asText()).plusDays(30),
                LocalDateTime.parse(row.path("requestExpiresAt").asText()));
        Map<String, Object> n1 = jdbc.queryForMap("SELECT category, type, action_url, title, actor_system_id "
                + "FROM notifications WHERE system_id = ? AND dedupe_key = ?", patient.getSystemId(),
                "ACCESS_REQUEST_NEW:" + requestId);
        assertEquals("SYSTEM", n1.get("category"));
        assertEquals("Caregiver access request", n1.get("title"));
        assertEquals("/dashboard/patient?section=my-proxies&id=" + requestId + "&tab=requests", n1.get("action_url"));
        assertEquals(caregiver.getSystemId(), n1.get("actor_system_id"));

        // Before approval the caregiver's row never names the patient.
        JsonNode mine = json(doGet(CG_REQUESTS, caregiver)).path("content").get(0);
        assertTrue(mine.path("patientSystemId").isNull());
        assertTrue(mine.path("patientName").isNull());
        assertTrue(mine.path("caregiverName").isNull());
        assertEquals(patient.getEmail(), mine.path("patientEmail").asText());

        // The patient approves lab results and messaging only (not the requested imaging or scheduling).
        MvcResult approved = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody());
        assertStatus(200, approved);
        JsonNode body = json(approved);
        assertEquals("APPROVED", body.path("status").asText());
        assertTrue(body.path("canViewImaging").asBoolean(), "the request still shows what was requested");
        assertFalse(body.path("respondedAt").isNull());
        int accessId = body.path("accessId").asInt();
        DependentAccess grant = dependentAccessRepository.findById(accessId).orElseThrow();
        assertEquals("daughter", grant.getRelationship());
        assertTrue(grant.getCanViewLabResults());
        assertFalse(grant.getCanViewImaging());
        assertFalse(grant.getCanScheduleAppointments());
        assertNotNull(grant.getMessagingApprovedAt(), "a communication flag sent true is approved by the patient");
        assertEquals(patient.getSystemId(), grant.getAuthorizedBySystemId());

        // The caregiver reads what was granted and nothing else.
        LabResult lab = labResult(patient);
        assertStatus(200, doGet("/api/lab-results/" + lab.getResultId(), caregiver));
        assertStatus(403, doGet("/api/imaging-results?patientSystemId=" + patient.getSystemId(), caregiver));

        // Now the caregiver's row names the patient and carries the grant id.
        mine = json(doGet(CG_REQUESTS + "?status=APPROVED", caregiver)).path("content").get(0);
        assertEquals(patient.getSystemId(), mine.path("patientSystemId").asText());
        assertEquals("PATIENT User1", mine.path("patientName").asText());
        assertEquals(accessId, mine.path("accessId").asInt());

        // One GRANTED ledger row and exactly one caregiver notification (the SYSTEM approval, no "Access granted").
        assertEquals(List.of("GRANTED"), ledgerKinds(accessId));
        assertEquals(1, notifications(caregiver.getSystemId(), "ACCESS_REQUEST_APPROVED:" + requestId));
        assertEquals(0, notifications(caregiver.getSystemId(), "ACCESS_CHANGE:%"));
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE system_id = ?", caregiver.getSystemId()));
        assertEquals("/dashboard/proxy?section=access-requests&id=" + requestId,
                jdbc.queryForObject("SELECT action_url FROM notifications WHERE system_id = ?", String.class,
                        caregiver.getSystemId()));

        // Audit trail of the request.
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CREATE' AND resource_type = 'ACCESS_REQUEST' "
                + "AND resource_id = ? AND system_id = ? AND patient_system_id = ?", requestId, caregiver.getSystemId(),
                patient.getSystemId()));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'ACCESS_REQUEST' "
                + "AND resource_id = ? AND system_id = ?", requestId, patient.getSystemId()));

        // Approving again is a no-op answering the current state.
        MvcResult again = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, Map.of("accessLevel", "FULL_ACCESS"));
        assertStatus(200, again);
        assertEquals(accessId, json(again).path("accessId").asInt());
        assertEquals(List.of("GRANTED"), ledgerKinds(accessId));
        assertFalse(dependentAccessRepository.findById(accessId).orElseThrow().getCanViewImaging());
    }

    @Test
    void approvalNotification_isSystemAndNotSuppressedByTheAccessChangeToggle() throws Exception {
        switchOffAccessChangeAlerts(caregiver);
        int requestId = requestForThomas(caregiver);
        assertStatus(200, doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody()));
        assertEquals(1, notifications(caregiver.getSystemId(), "ACCESS_REQUEST_APPROVED:" + requestId));
    }

    @Test
    void approvingReusesARevokedGrant_andRefusesAnUnrevokedOne() throws Exception {
        DependentAccess old = grantAccess(caregiver, patient, a -> {
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusDays(3));
        });
        int requestId = requestForThomas(caregiver);
        MvcResult approved = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody());
        assertStatus(200, approved);
        assertEquals(old.getAccessId().intValue(), json(approved).path("accessId").asInt());

        // A request from another caregiver who already holds a live grant cannot be approved: it stays PENDING.
        grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));
        jdbc.update("INSERT INTO caregiver_access_requests (caregiver_system_id, patient_system_id, patient_email, "
                + "relationship, access_level) VALUES (?, ?, ?, 'son', 'VIEW_ONLY')", otherCaregiver.getSystemId(),
                patient.getSystemId(), patient.getEmail());
        int second = jdbc.queryForObject("SELECT max(request_id) FROM caregiver_access_requests", Integer.class);
        MvcResult conflict = doPost(PT_REQUESTS + "/" + second + "/approve", patient, approveBody());
        assertStatus(409, conflict);
        assertEquals("This caregiver already has access. Change their permissions instead.", message(conflict));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, second));
    }

    @Test
    void approve_badExpiryAndInactiveCaregiver_leaveTheRequestPending() throws Exception {
        int requestId = requestForThomas(caregiver);
        Map<String, Object> body = approveBody();
        body.put("expiresAt", "next week");
        MvcResult bad = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, body);
        assertStatus(400, bad);
        assertEquals("Access expiry must be a date in the format YYYY-MM-DD", message(bad));
        body.put("expiresAt", LocalDate.now().minusDays(1).toString());
        MvcResult past = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, body);
        assertStatus(400, past);
        assertEquals("Access expiry must be in the future", message(past));

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", caregiver.getSystemId());
        MvcResult inactive = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody());
        assertStatus(409, inactive);
        assertEquals("This caregiver's account is no longer active. Decline the request instead.", message(inactive));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, requestId));
        assertEquals(0, count("SELECT count(*) FROM dependent_access"));

        // With an expiry the grant ends 23:59:59 of that clinic day.
        jdbc.update("UPDATE users SET is_active = TRUE WHERE system_id = ?", caregiver.getSystemId());
        String day = LocalDate.now().plusDays(30).toString();
        body.put("expiresAt", day);
        MvcResult ok = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, body);
        assertStatus(200, ok);
        assertEquals(LocalDate.parse(day).atTime(23, 59, 59),
                dependentAccessRepository.findById(json(ok).path("accessId").asInt()).orElseThrow().getExpiresAt());
    }

    // ==================== DECLINE, CANCEL, DUPLICATES ====================

    @Test
    void decline_notifiesTheCaregiver_keepsTheFirstReason_andAllowsANewRequest() throws Exception {
        int requestId = requestForThomas(caregiver);
        MvcResult declined = doPost(PT_REQUESTS + "/" + requestId + "/decline", patient, Map.of("reason", "  Not now  "));
        assertStatus(200, declined);
        assertEquals("DECLINED", json(declined).path("status").asText());
        assertEquals("Not now", json(declined).path("declineReason").asText());

        JsonNode mine = json(doGet(CG_REQUESTS, caregiver)).path("content").get(0);
        assertEquals("DECLINED", mine.path("status").asText());
        assertEquals("Not now", mine.path("declineReason").asText());
        assertTrue(mine.path("patientSystemId").isNull(), "a declined request never names the patient");
        Map<String, Object> n3 = jdbc.queryForMap("SELECT category, message, action_url FROM notifications "
                + "WHERE system_id = ? AND dedupe_key = ?", caregiver.getSystemId(), "ACCESS_REQUEST_DECLINED:" + requestId);
        assertEquals("SYSTEM", n3.get("category"));
        assertEquals("Your access request for thomas@example.com was declined", n3.get("message"));
        assertEquals("/dashboard/proxy?section=access-requests&id=" + requestId, n3.get("action_url"));

        // Declining again keeps the first reason; no body is fine.
        MvcResult again = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(PT_REQUESTS + "/" + requestId + "/decline"), patient, null);
        assertStatus(200, again);
        assertEquals("Not now", json(again).path("declineReason").asText());

        MvcResult approve = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody());
        assertStatus(409, approve);
        assertEquals("This request has already been declined", message(approve));
        MvcResult cancel = doDelete(CG_REQUESTS + "/" + requestId, caregiver);
        assertStatus(409, cancel);
        assertEquals("Only a pending request can be cancelled", message(cancel));

        // A declined caregiver may ask again: a new row.
        int second = requestForThomas(caregiver);
        assertNotEquals(requestId, second);
        assertEquals(2, count("SELECT count(*) FROM caregiver_access_requests"));
    }

    @Test
    void duplicatePendingRequest_isReusedUnchanged() throws Exception {
        int first = requestForThomas(caregiver);
        Map<String, Object> changed = requestBody("  THOMAS@Example.com ");
        changed.put("accessLevel", "FULL_ACCESS");
        assertAccepted(doPost(CG_REQUESTS, caregiver, changed));
        assertEquals(1, count("SELECT count(*) FROM caregiver_access_requests"));
        assertEquals("CUSTOM", jdbc.queryForObject(
                "SELECT access_level FROM caregiver_access_requests WHERE request_id = ?", String.class, first));
        assertEquals(1, notifications(patient.getSystemId(), "ACCESS_REQUEST_NEW:%"));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'CREATE' AND resource_type = 'ACCESS_REQUEST'"));

        // Another caregiver's request for the same patient is its own row.
        requestForThomas(otherCaregiver);
        assertEquals(2, count("SELECT count(*) FROM caregiver_access_requests"));
    }

    @Test
    void pendingRequestWhoseEmailMovedToAnotherPatient_isCancelled_andTheNewHolderIsAsked() throws Exception {
        int stale = requestForThomas(caregiver);
        assertEquals(1, notifications(patient.getSystemId(), "ACCESS_REQUEST_NEW:%"));
        // Thomas changes his address and Mary's account takes over the old one
        jdbc.update("UPDATE users SET email = 'thomas.new@example.com' WHERE system_id = ?", patient.getSystemId());
        jdbc.update("UPDATE users SET email = 'thomas@example.com' WHERE system_id = ?", otherPatient.getSystemId());

        assertAccepted(doPost(CG_REQUESTS, caregiver, requestBody("thomas@example.com")));

        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, stale));
        int fresh = jdbc.queryForObject("SELECT request_id FROM caregiver_access_requests WHERE caregiver_system_id = ? "
                + "AND status = 'PENDING'", Integer.class, caregiver.getSystemId());
        assertEquals(otherPatient.getSystemId(), jdbc.queryForObject(
                "SELECT patient_system_id FROM caregiver_access_requests WHERE request_id = ?", String.class, fresh));
        assertEquals(1, notifications(otherPatient.getSystemId(), "ACCESS_REQUEST_NEW:" + fresh));
        assertEquals(1, notifications(patient.getSystemId(), "ACCESS_REQUEST_NEW:%"), "Thomas is not asked again");

        // Thomas can no longer approve the request that was addressed to the old email
        assertStatus(409, doPost(PT_REQUESTS + "/" + stale + "/approve", patient, approveBody()));
        assertEquals(0, json(doGet(PT_REQUESTS + "?status=PENDING", patient)).path("totalElements").asInt());
        assertEquals(1, json(doGet(PT_REQUESTS + "?status=PENDING", otherPatient)).path("totalElements").asInt());
        // Sending the same request again changes nothing
        assertAccepted(doPost(CG_REQUESTS, caregiver, requestBody("thomas@example.com")));
        assertEquals(2, count("SELECT count(*) FROM caregiver_access_requests WHERE caregiver_system_id = ?",
                caregiver.getSystemId()));
    }

    @Test
    void unknownEmail_looksLikeAnUnansweredRequest_andNobodyIsNotified() throws Exception {
        assertAccepted(doPost(CG_REQUESTS, caregiver, requestBody("nobody@example.com")));
        assertAccepted(doPost(CG_REQUESTS, caregiver, requestBody("nobody@example.com")));
        assertEquals(1, count("SELECT count(*) FROM caregiver_access_requests WHERE patient_email = 'nobody@example.com' "
                + "AND patient_system_id IS NULL AND status = 'PENDING'"));
        assertEquals(0, count("SELECT count(*) FROM notifications"));

        // Stored like a request for a real patient.
        JsonNode row = json(doGet(CG_REQUESTS, caregiver)).path("content").get(0);
        assertEquals("PENDING", row.path("status").asText());
        assertTrue(row.path("patientSystemId").isNull());
        assertTrue(row.path("canMessageCareTeam").asBoolean());

        // Nobody can approve it.
        int requestId = row.path("requestId").asInt();
        assertStatus(403, doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody()));
    }

    @Test
    void requestNormalisesFlagsToTheLevel() throws Exception {
        Map<String, Object> body = requestBody(patient.getEmail());
        body.put("accessLevel", "VIEW_ONLY");
        body.put("canManageBilling", true);
        assertAccepted(doPost(CG_REQUESTS, caregiver, body));
        JsonNode row = json(doGet(PT_REQUESTS, patient)).path("content").get(0);
        assertEquals("VIEW_ONLY", row.path("accessLevel").asText());
        assertFalse(row.path("canScheduleAppointments").asBoolean());
        assertFalse(row.path("canManageBilling").asBoolean());
        assertTrue(row.path("canViewLabResults").asBoolean());

        assertStatus(204, doDelete(CG_REQUESTS + "/" + row.path("requestId").asInt(), caregiver));
        body.put("accessLevel", "FULL_ACCESS");
        assertAccepted(doPost(CG_REQUESTS, caregiver, body));
        row = json(doGet(PT_REQUESTS + "?status=PENDING", patient)).path("content").get(0);
        for (String flag : List.of("canViewRecords", "canViewAppointments", "canViewMedications", "canViewLabResults",
                "canViewImaging", "canViewConsultations", "canViewBilling", "canScheduleAppointments",
                "canManageCarePlan", "canManageBilling")) {
            assertTrue(row.path(flag).asBoolean(), flag);
        }
        assertFalse(row.path("canJoinCalls").asBoolean(), "the level never touches the communication flags");
    }

    @Test
    void alreadyLinkedCaregiver_gets409_evenWithAnExpiredGrant() throws Exception {
        grantAccess(caregiver, patient, a -> a.setExpiresAt(LocalDateTime.now().minusDays(1)));
        MvcResult linked = doPost(CG_REQUESTS, caregiver, requestBody(patient.getEmail()));
        assertStatus(409, linked);
        assertEquals("You are already linked to this patient. Ask them to change or extend your access.", message(linked));
        assertEquals(0, count("SELECT count(*) FROM caregiver_access_requests"));
    }

    @Test
    void cancel_isIdempotent_ownerOnly_andNotifiesNobody() throws Exception {
        int requestId = requestForThomas(caregiver);
        MvcResult foreign = doDelete(CG_REQUESTS + "/" + requestId, otherCaregiver);
        assertStatus(403, foreign);
        assertEquals("Not authorized to act on this access request", message(foreign));
        assertStatus(404, doDelete(CG_REQUESTS + "/999999", caregiver));

        assertStatus(204, doDelete(CG_REQUESTS + "/" + requestId, caregiver));
        assertStatus(204, doDelete(CG_REQUESTS + "/" + requestId, caregiver));
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'DELETE' AND resource_type = 'ACCESS_REQUEST'"));
        assertEquals(0, notifications(caregiver.getSystemId(), "%"));

        MvcResult approve = doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody());
        assertStatus(409, approve);
        assertEquals("This request was cancelled by the caregiver", message(approve));
        MvcResult decline = doPost(PT_REQUESTS + "/" + requestId + "/decline", patient, Map.of());
        assertStatus(409, decline);
        assertEquals("This request was cancelled by the caregiver", message(decline));
    }

    @Test
    void approvedRequest_cannotBeDeclinedOrCancelled() throws Exception {
        int requestId = requestForThomas(caregiver);
        assertStatus(200, doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, approveBody()));
        MvcResult decline = doPost(PT_REQUESTS + "/" + requestId + "/decline", patient, Map.of());
        assertStatus(409, decline);
        assertEquals("This request has already been approved. Remove the caregiver's access instead.", message(decline));
        assertStatus(409, doDelete(CG_REQUESTS + "/" + requestId, caregiver));
    }

    // ==================== WHO MAY ACT ====================

    @Test
    void onlyTheAddressedPatientCanDecide() throws Exception {
        int requestId = requestForThomas(caregiver);

        MvcResult other = doPost(PT_REQUESTS + "/" + requestId + "/approve", otherPatient, approveBody());
        assertStatus(403, other);
        assertEquals("Not authorized to act on this access request", message(other));
        assertStatus(403, doPost(PT_REQUESTS + "/" + requestId + "/decline", otherPatient, Map.of()));
        assertStatus(404, doPost(PT_REQUESTS + "/999999/approve", patient, approveBody()));

        // A caregiver is refused at the route, an admin by the role check.
        assertStatus(403, doPost(PT_REQUESTS + "/" + requestId + "/approve", caregiver, approveBody()));
        MvcResult admin = doPost(PT_REQUESTS + "/" + requestId + "/approve", this.admin, approveBody());
        assertStatus(403, admin);
        assertEquals("Your role is not authorized to perform this action", message(admin));
        assertStatus(403, doGet(PT_REQUESTS, this.admin));

        // Patients and doctors cannot use the caregiver side.
        assertStatus(403, doPost(CG_REQUESTS, patient, requestBody(otherPatient.getEmail())));
        assertStatus(403, doGet(CG_REQUESTS, doctor));

        // The other patient sees nothing; the request is untouched.
        assertEquals(0, json(doGet(PT_REQUESTS, otherPatient)).path("totalElements").asInt());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, requestId));
        assertEquals(0, count("SELECT count(*) FROM dependent_access"));
    }

    @Test
    void validationAndFilters() throws Exception {
        Map<String, Object> body = requestBody("   ");
        MvcResult blank = doPost(CG_REQUESTS, caregiver, body);
        assertStatus(400, blank);
        assertEquals("patientEmail: Patient email is required", message(blank));
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());

        body = requestBody("not-an-email");
        assertEquals("patientEmail: Please enter a valid email address", message(doPost(CG_REQUESTS, caregiver, body)));
        body = requestBody(patient.getEmail());
        body.put("relationship", "x".repeat(81));
        assertEquals("relationship: Relationship must be at most 80 characters",
                message(doPost(CG_REQUESTS, caregiver, body)));
        body = requestBody(patient.getEmail());
        body.put("accessLevel", "ALL");
        assertEquals("accessLevel: Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM",
                message(doPost(CG_REQUESTS, caregiver, body)));
        body = requestBody(patient.getEmail());
        body.put("message", "m".repeat(501));
        assertEquals("message: Message must be at most 500 characters", message(doPost(CG_REQUESTS, caregiver, body)));
        MvcResult noBody = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(CG_REQUESTS),
                caregiver, null);
        assertStatus(400, noBody);
        assertEquals("Malformed request body", message(noBody));
        assertEquals(0, count("SELECT count(*) FROM caregiver_access_requests"));

        int requestId = requestForThomas(caregiver);
        assertEquals("accessLevel: Access level is required",
                message(doPost(PT_REQUESTS + "/" + requestId + "/approve", patient, Map.of("canViewRecords", true))));
        assertEquals("reason: Reason must be at most 500 characters",
                message(doPost(PT_REQUESTS + "/" + requestId + "/decline", patient, Map.of("reason", "r".repeat(501)))));
        assertEquals("Invalid value for parameter 'requestId'",
                message(doPost(PT_REQUESTS + "/abc/approve", patient, approveBody())));

        MvcResult badStatus = doGet(PT_REQUESTS + "?status=OPEN", patient);
        assertStatus(400, badStatus);
        assertEquals("Invalid status. Must be PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED", message(badStatus));
        assertEquals(0, json(doGet(PT_REQUESTS + "?status=DECLINED", patient)).path("totalElements").asInt());
        JsonNode page = json(doGet(CG_REQUESTS + "?page=-3&size=500", caregiver));
        assertEquals(0, page.path("page").asInt());
        assertEquals(100, page.path("size").asInt());
    }

    @Test
    void accountCleanUp_statusOnlyCancellation_isValid() throws Exception {
        int requestId = requestForThomas(caregiver);
        // The statement be-account-lifecycle runs on a closure.
        assertEquals(1, jdbc.update("UPDATE caregiver_access_requests SET status = 'CANCELLED' "
                + "WHERE status = 'PENDING' AND patient_system_id = ?", patient.getSystemId()));
        JsonNode row = json(doGet(CG_REQUESTS, caregiver)).path("content").get(0);
        assertEquals(requestId, row.path("requestId").asInt());
        assertEquals("CANCELLED", row.path("status").asText());
        assertTrue(row.path("respondedAt").isNull());
    }

    // ==================== CONSENT LEDGER ====================

    @Test
    void oneConsentRowPerLifecycleStep_andAccessChangeNotifications() throws Exception {
        Map<String, Object> grant = new HashMap<>();
        grant.put("caregiverEmail", caregiver.getEmail());
        grant.put("relationship", "daughter");
        grant.put("accessLevel", "CUSTOM");
        grant.put("canViewRecords", true);
        grant.put("canViewLabResults", true);
        grant.put("expiresAt", LocalDate.now().plusDays(10).toString());
        MvcResult created = doPost(GRANTS, patient, grant);
        assertStatus(201, created);
        int accessId = json(created).path("accessId").asInt();

        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canViewImaging", true)));
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canMessageCareTeam", true)));
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient,
                Map.of("expiresAt", LocalDate.now().plusDays(20).toString())));
        assertStatus(200, doPatch("/api/caregiver/dependents/" + accessId, caregiver, Map.of("canViewImaging", false)));
        // A PATCH that changes nothing records nothing.
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canViewRecords", true)));
        assertStatus(204, doDelete(GRANTS + "/" + accessId, patient));
        assertStatus(204, doDelete(GRANTS + "/" + accessId, patient));

        assertEquals(List.of("GRANTED", "PERMISSIONS_CHANGED", "COMMUNICATION_APPROVED", "EXTENDED", "NARROWED", "REVOKED"),
                ledgerKinds(accessId));
        assertEquals(6, count("SELECT count(*) FROM consent_records WHERE consent_type = 'DATA_ACCESS' "
                + "AND patient_system_id = ? AND caregiver_system_id = ?", patient.getSystemId(), caregiver.getSystemId()));
        // Ledger writes are not audited as CONSENT (only reads are).
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE resource_type = 'CONSENT'"));

        JsonNode ledger = json(doGet(CONSENTS, patient));
        assertEquals(6, ledger.path("totalElements").asInt());
        List<String> newestFirst = new ArrayList<>();
        ledger.path("content").forEach(r -> newestFirst.add(r.path("eventKind").asText()));
        assertEquals(List.of("REVOKED", "NARROWED", "EXTENDED", "COMMUNICATION_APPROVED", "PERMISSIONS_CHANGED", "GRANTED"),
                newestFirst);
        JsonNode revoked = ledger.path("content").get(0);
        assertEquals(accessId, revoked.path("accessId").asInt());
        assertEquals(patient.getSystemId(), revoked.path("grantedBySystemId").asText());
        assertEquals("CAREGIVER User3", revoked.path("caregiverName").asText());
        assertFalse(revoked.path("scope").path("messagingApproved").asBoolean());
        assertFalse(revoked.path("recordedAt").isNull());
        JsonNode narrowed = ledger.path("content").get(1);
        assertEquals(caregiver.getSystemId(), narrowed.path("grantedBySystemId").asText());
        assertFalse(narrowed.path("scope").path("canViewImaging").asBoolean());
        JsonNode extended = ledger.path("content").get(2);
        assertEquals(LocalDate.now().plusDays(20).atTime(23, 59, 59).toString(),
                extended.path("scope").path("expiresAt").asText());
        JsonNode approvedComm = ledger.path("content").get(3);
        assertTrue(approvedComm.path("scope").path("canMessageCareTeam").asBoolean());
        assertTrue(approvedComm.path("scope").path("messagingApproved").asBoolean());
        JsonNode granted = ledger.path("content").get(5);
        assertEquals("CUSTOM", granted.path("scope").path("accessLevel").asText());
        assertTrue(granted.path("scope").path("canViewLabResults").asBoolean());
        assertFalse(granted.path("scope").path("canViewImaging").asBoolean());
        assertFalse(granted.path("scope").path("guardianAttested").asBoolean());
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'CONSENT' "
                + "AND patient_system_id = ?", patient.getSystemId()));

        // Filter by grant; another patient's or an unknown grant id gives an empty page.
        assertEquals(6, json(doGet(CONSENTS + "?accessId=" + accessId, patient)).path("totalElements").asInt());
        assertEquals(0, json(doGet(CONSENTS + "?accessId=" + accessId, otherPatient)).path("totalElements").asInt());
        assertEquals(0, json(doGet(CONSENTS + "?accessId=999999", patient)).path("totalElements").asInt());
        for (User refused : List.of(caregiver, doctor, admin)) {
            MvcResult r = doGet(CONSENTS, refused);
            assertStatus(403, r);
            assertEquals("Your role is not authorized to perform this action", message(r));
        }

        // The caregiver was told about every change except their own narrowing.
        List<String> titles = jdbc.queryForList("SELECT title FROM notifications WHERE system_id = ? "
                + "AND category = 'ACCESS_CHANGE' ORDER BY notification_id", String.class, caregiver.getSystemId());
        assertEquals(List.of("Access granted", "Your access was changed", "Communication approved",
                "Your access was extended", "Your access was removed"), titles);
        assertEquals("/dashboard/proxy?section=profile-settings&patient=" + patient.getSystemId(),
                jdbc.queryForObject("SELECT action_url FROM notifications WHERE system_id = ? AND title = 'Your access was removed'",
                        String.class, caregiver.getSystemId()));
        assertEquals("Your access to PATIENT User1's information has been removed",
                jdbc.queryForObject("SELECT message FROM notifications WHERE system_id = ? AND title = 'Your access was removed'",
                        String.class, caregiver.getSystemId()));
    }

    @Test
    void revoke_notifiesTheCaregiver_unlessTheToggleIsOff() throws Exception {
        DependentAccess first = grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(204, doDelete(GRANTS + "/" + first.getAccessId(), patient));
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE system_id = ? AND category = 'ACCESS_CHANGE' "
                + "AND title = 'Your access was removed'", caregiver.getSystemId()));

        switchOffAccessChangeAlerts(otherCaregiver);
        DependentAccess second = grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(204, doDelete(GRANTS + "/" + second.getAccessId(), patient));
        assertEquals(List.of("REVOKED"), ledgerKinds(second.getAccessId()), "the ledger row is written regardless");
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE system_id = ?", otherCaregiver.getSystemId()));
    }

    @Test
    void adminRevoke_notifiesTheCaregiver_withTheAdminAsActor() throws Exception {
        DependentAccess grant = grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(204, doDelete("/api/patients/me/caregivers/" + grant.getAccessId(), admin));
        assertEquals(admin.getSystemId(), jdbc.queryForObject(
                "SELECT granted_by_system_id FROM consent_records WHERE access_id = ?", String.class, grant.getAccessId()));
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE system_id = ? AND actor_system_id = ? "
                + "AND category = 'ACCESS_CHANGE'", caregiver.getSystemId(), admin.getSystemId()));
    }

    @Test
    void expiryStep_isRecordedByTheSweeper_andNotifiesWithoutActor() throws Exception {
        DependentAccess grant = grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        });
        assertEquals(1, caregiverAccessExpirySweeper.sweepOnce(LocalDateTime.now()));
        Map<String, Object> row = jdbc.queryForMap("SELECT event_kind, granted_by_system_id, is_active "
                + "FROM consent_records WHERE access_id = ?", grant.getAccessId());
        assertEquals("EXPIRED", row.get("event_kind"));
        assertNull(row.get("granted_by_system_id"));
        assertEquals(Boolean.FALSE, row.get("is_active"));
        Map<String, Object> n9 = jdbc.queryForMap("SELECT title, actor_system_id FROM notifications WHERE system_id = ? "
                + "AND category = 'ACCESS_CHANGE'", caregiver.getSystemId());
        assertEquals("Your access has expired", n9.get("title"));
        assertNull(n9.get("actor_system_id"));
        // The ledger lists it with grantedBySystemId null.
        JsonNode ledger = json(doGet(CONSENTS, patient)).path("content").get(0);
        assertEquals("EXPIRED", ledger.path("eventKind").asText());
        assertTrue(ledger.path("grantedBySystemId").isNull());
    }

    @Test
    void caregiverCreatedDependent_writesAConsentRowButNotifiesNobody() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Rudo");
        body.put("lastName", "Moyo");
        body.put("relationship", "mother");
        body.put("guardianAttested", true);
        body.put("accessLevel", "FULL_ACCESS");
        MvcResult created = doPost("/api/caregiver/dependents", caregiver, body);
        assertStatus(201, created);
        int accessId = json(created).path("accessId").asInt();
        assertEquals(List.of("GRANTED"), ledgerKinds(accessId));
        assertEquals(caregiver.getSystemId(), jdbc.queryForObject(
                "SELECT granted_by_system_id FROM consent_records WHERE access_id = ?", String.class, accessId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT (scope ->> 'guardianAttested')::boolean FROM consent_records WHERE access_id = ?", Boolean.class, accessId));
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE system_id = ?", caregiver.getSystemId()));
    }

    // ==================== CAREGIVER ACTIVITY ====================

    @Test
    void activityRowsAppearAfterTheCaregiverReadsLabResults_andOthersAreRefused() throws Exception {
        DependentAccess grant = grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        LabResult lab = labResult(patient);
        assertStatus(200, doGet("/api/lab-results/" + lab.getResultId(), caregiver));
        // A denied attempt is not listed.
        assertStatus(403, doGet("/api/imaging-results?patientSystemId=" + patient.getSystemId(), caregiver));

        String url = GRANTS + "/" + grant.getAccessId() + "/activity";
        JsonNode page = json(doGet(url, patient));
        assertEquals(1, page.path("totalElements").asInt(), page.toString());
        JsonNode entry = page.path("content").get(0);
        assertEquals("READ", entry.path("action").asText());
        assertEquals("LAB_RESULT", entry.path("resourceType").asText());
        assertFalse(entry.path("timestamp").isNull());
        assertTrue(entry.path("auditId").asLong() > 0);
        assertEquals(4, entry.size(), "only auditId, action, resourceType and timestamp are returned");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'AUDIT_LOG' "
                + "AND system_id = ? AND patient_system_id = ?", patient.getSystemId(), patient.getSystemId()));
        // The patient's own read of the feed never shows up in it.
        assertEquals(1, json(doGet(url, patient)).path("totalElements").asInt());

        MvcResult other = doGet(url, otherPatient);
        assertStatus(403, other);
        assertEquals("Not authorized to view this access record", message(other));
        MvcResult missing = doGet(GRANTS + "/999999/activity", patient);
        assertStatus(404, missing);
        assertEquals("Access record not found", message(missing));
        assertStatus(403, doGet(url, caregiver));
        assertStatus(403, doGet(url, admin));

        // A revoked grant still shows its history.
        assertStatus(204, doDelete(GRANTS + "/" + grant.getAccessId(), patient));
        assertEquals(1, json(doGet(url, patient)).path("totalElements").asInt());
    }

    // ==================== JOBS ====================

    @Test
    void pendingRequestsExpireAfterThirtyDays_withoutNotifications() throws Exception {
        int old = requestForThomas(caregiver);
        int recent = requestForThomas(otherCaregiver);
        jdbc.update("UPDATE caregiver_access_requests SET created_at = ? WHERE request_id = ?",
                LocalDateTime.now().minusDays(31), old);
        jdbc.update("UPDATE caregiver_access_requests SET created_at = ? WHERE request_id = ?",
                LocalDateTime.now().minusDays(29), recent);
        int notificationsBefore = count("SELECT count(*) FROM notifications");

        assertEquals(1, accessRequestExpiryJob.sweepOnce(LocalDateTime.now()));
        assertEquals(0, accessRequestExpiryJob.sweepOnce(LocalDateTime.now()));
        assertEquals("EXPIRED", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, old));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class, recent));
        assertEquals(notificationsBefore, count("SELECT count(*) FROM notifications"));

        JsonNode expired = json(doGet(CG_REQUESTS + "?status=EXPIRED", caregiver)).path("content").get(0);
        assertFalse(expired.path("respondedAt").isNull());
        MvcResult approve = doPost(PT_REQUESTS + "/" + old + "/approve", patient, approveBody());
        assertStatus(409, approve);
        assertEquals("This request has expired", message(approve));
        MvcResult decline = doPost(PT_REQUESTS + "/" + old + "/decline", patient, Map.of());
        assertStatus(409, decline);
        assertEquals("This request has expired", message(decline));
        // The stored status is the truth: an overdue request not yet swept can still be approved.
        jdbc.update("UPDATE caregiver_access_requests SET created_at = ? WHERE request_id = ?",
                LocalDateTime.now().minusDays(40), recent);
        assertStatus(200, doPost(PT_REQUESTS + "/" + recent + "/approve", patient, approveBody()));
    }

    @Test
    void expiryNotice_oncePerExpiryDate_andSuppressedByTheToggle() throws Exception {
        LocalDateTime soon = LocalDateTime.now().plusDays(2).withNano(0);
        DependentAccess grant = grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(soon);
        });
        grantAccess(caregiver, otherPatient, a -> a.setExpiresAt(LocalDateTime.now().plusDays(5)));
        DependentAccess muted = grantAccess(otherCaregiver, patient, a -> a.setExpiresAt(soon));
        switchOffAccessChangeAlerts(otherCaregiver);

        assertEquals(1, accessExpiryNoticeJob.sweepOnce(LocalDateTime.now()));
        assertEquals(0, accessExpiryNoticeJob.sweepOnce(LocalDateTime.now()));
        Map<String, Object> n10 = jdbc.queryForMap("SELECT title, message, dedupe_key, actor_system_id, action_url "
                + "FROM notifications WHERE system_id = ?", caregiver.getSystemId());
        assertEquals("Your access expires soon", n10.get("title"));
        assertEquals("Your access to PATIENT User1's information ends on "
                + soon.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)),
                n10.get("message"));
        assertEquals("ACCESS_EXPIRING:" + grant.getAccessId() + ":" + soon.toLocalDate(), n10.get("dedupe_key"));
        assertNull(n10.get("actor_system_id"));
        assertEquals(0, count("SELECT count(*) FROM notifications WHERE system_id = ?", otherCaregiver.getSystemId()));
        assertNotNull(muted.getAccessId());

        // A new expiry date (extension) is announced again.
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE access_id = ?", soon.plusDays(1), grant.getAccessId());
        assertEquals(1, accessExpiryNoticeJob.sweepOnce(LocalDateTime.now()));
        assertEquals(2, notifications(caregiver.getSystemId(), "ACCESS_EXPIRING:%"));
    }
}
