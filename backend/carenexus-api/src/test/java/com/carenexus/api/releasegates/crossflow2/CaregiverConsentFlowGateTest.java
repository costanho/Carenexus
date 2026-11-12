package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.caregiveraccess.job.AccessExpiryNoticeJob;
import com.carenexus.api.communications.job.CaregiverAccessExpirySweeper;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.lifecycle.AccountLifecycleTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.allOf;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.ofCategory;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.onlyWithKey;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKey;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKeyPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate of caregiver consent: a request (be-caregiver-requests-consent), its approval, a permission change
 * and a revocation through the real CaregiverService (be-caregiver-core), the expiry notice and the expiry sweeper
 * (communications), and an administrator's deactivation of the caregiver (be-account-lifecycle). Every step that
 * changes a grant writes exactly one consent-ledger row and, through the publisher, exactly one notification to the
 * caregiver (API-NOTES: the approval is the SYSTEM notification ACCESS_REQUEST_APPROVED and replaces "Access granted";
 * every later step is ACCESS_CHANGE and is suppressed by the caregiver's accessChangeAlerts toggle, while the ledger
 * row is written regardless). A deactivated caregiver loses every grant (one REVOKED row each, the administrator as
 * actor) and every conversation membership about those patients.
 */
class CaregiverConsentFlowGateTest extends AccountLifecycleTestSupport {

    private static final String CG_REQUESTS = "/api/caregiver/access-requests";
    private static final String PT_REQUESTS = "/api/patients/me/caregiver-access/requests";
    private static final String GRANTS = "/api/patients/me/caregiver-access";
    private static final String RECORDS = "/api/medical-records?patientSystemId=";

    @Autowired
    private AccessExpiryNoticeJob expiryNoticeJob;

    @Autowired
    private CaregiverAccessExpirySweeper expirySweeper;

    // ==================== HELPERS ====================

    private void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** The caregiver asks the patient for lab results and messaging; returns the request id. */
    private int requestAccess(User from, User to) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("patientEmail", to.getEmail());
        body.put("relationship", "daughter");
        body.put("accessLevel", "CUSTOM");
        body.put("canViewLabResults", true);
        body.put("canMessageCareTeam", true);
        assertStatus(202, doPost(CG_REQUESTS, from, body));
        Integer id = jdbc.queryForObject("SELECT request_id FROM caregiver_access_requests WHERE caregiver_system_id = ? "
                + "AND patient_system_id = ? AND status = 'PENDING'", Integer.class, from.getSystemId(), to.getSystemId());
        return id == null ? -1 : id;
    }

    /** The patient approves lab results only; returns the new grant id. */
    private int approve(User as, int requestId) throws Exception {
        MvcResult approved = doPost(PT_REQUESTS + "/" + requestId + "/approve", as,
                Map.of("accessLevel", "CUSTOM", "canViewLabResults", true));
        assertStatus(200, approved);
        assertEquals("APPROVED", json(approved).path("status").asText());
        return json(approved).path("accessId").asInt();
    }

    /** The patient grants the caregiver directly (records only), ending on {@code expiresOn}; returns the grant id. */
    private int grantDirectly(User as, User to, LocalDate expiresOn) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("caregiverEmail", to.getEmail());
        body.put("relationship", "son");
        body.put("accessLevel", "CUSTOM");
        body.put("canViewRecords", true);
        body.put("expiresAt", expiresOn.toString());
        MvcResult created = doPost(GRANTS, as, body);
        assertStatus(201, created);
        return json(created).path("accessId").asInt();
    }

    private List<Map<String, Object>> ledger(int accessId) {
        return jdbc.queryForList("SELECT consent_id, event_kind, granted_by_system_id FROM consent_records "
                + "WHERE access_id = ? ORDER BY consent_id", accessId);
    }

    private List<String> ledgerKinds(int accessId) {
        return ledger(accessId).stream().map(row -> (String) row.get("event_kind")).toList();
    }

    /** The ledger row id of the newest step of the grant. */
    private int newestConsentId(int accessId) {
        List<Map<String, Object>> rows = ledger(accessId);
        return (Integer) rows.get(rows.size() - 1).get("consent_id");
    }

    private void switchOffAccessChangeAlerts(User user) throws Exception {
        assertStatus(200, doPatch("/api/profile/settings", user, Map.of("accessChangeAlerts", false)));
    }

    // ==================== REQUEST, APPROVE, CHANGE, REVOKE ====================

    @Test
    void requestApproveChangeRevoke_eachGrantStepWritesOneLedgerRowAndOneCaregiverNotification() throws Exception {
        // ---- request: the patient is asked (SYSTEM, N1); no grant, no ledger row, nothing for the caregiver ----
        int requestId = requestAccess(caregiver, patient);
        assertEquals(1, withKey(jdbc, patient.getSystemId(), "ACCESS_REQUEST_NEW:" + requestId));
        assertEquals(0, allOf(jdbc, caregiver.getSystemId()));
        assertEquals(0, CrossFlow2Fixtures.count(jdbc, "SELECT COUNT(*) FROM consent_records"));

        // ---- approve: GRANTED, and the SYSTEM approval is the caregiver's only notification ----
        int accessId = approve(patient, requestId);
        assertEquals(List.of("GRANTED"), ledgerKinds(accessId));
        assertEquals(patient.getSystemId(), ledger(accessId).get(0).get("granted_by_system_id"));
        Map<String, Object> approval = onlyWithKey(jdbc, caregiver.getSystemId(), "ACCESS_REQUEST_APPROVED:" + requestId);
        assertEquals("SYSTEM", approval.get("category"));
        assertEquals("Access request approved", approval.get("title"));
        assertEquals(0, ofCategory(jdbc, caregiver.getSystemId(), "ACCESS_CHANGE"), "no 'Access granted' as well");
        assertEquals(1, allOf(jdbc, caregiver.getSystemId()));

        // ---- change: PERMISSIONS_CHANGED, one ACCESS_CHANGE keyed by the ledger row ----
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canViewImaging", true)));
        assertEquals(List.of("GRANTED", "PERMISSIONS_CHANGED"), ledgerKinds(accessId));
        Map<String, Object> changed = onlyWithKey(jdbc, caregiver.getSystemId(),
                "ACCESS_CHANGE:" + accessId + ":" + newestConsentId(accessId));
        assertEquals("Your access was changed", changed.get("title"));
        assertEquals(patient.getSystemId(), changed.get("actor_system_id"));
        assertEquals(2, allOf(jdbc, caregiver.getSystemId()));

        // A PATCH that changes nothing records and notifies nothing
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canViewImaging", true)));
        assertEquals(2, ledger(accessId).size());
        assertEquals(2, allOf(jdbc, caregiver.getSystemId()));

        // ---- revoke: REVOKED, one ACCESS_CHANGE; a repeated revoke adds nothing ----
        assertStatus(204, doDelete(GRANTS + "/" + accessId, patient));
        assertStatus(204, doDelete(GRANTS + "/" + accessId, patient));
        assertEquals(List.of("GRANTED", "PERMISSIONS_CHANGED", "REVOKED"), ledgerKinds(accessId));
        Map<String, Object> removed = onlyWithKey(jdbc, caregiver.getSystemId(),
                "ACCESS_CHANGE:" + accessId + ":" + newestConsentId(accessId));
        assertEquals("Your access was removed", removed.get("title"));
        assertEquals(3, allOf(jdbc, caregiver.getSystemId()));
        assertEquals(2, ofCategory(jdbc, caregiver.getSystemId(), "ACCESS_CHANGE"));

        // Ledger writes are not audited as CONSENT; nobody else was notified about the grant
        assertEquals(0, CrossFlow2Fixtures.count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE resource_type = 'CONSENT'"));
        assertEquals(1, allOf(jdbc, patient.getSystemId()), "only the request notice");
        for (User outsider : List.of(otherCaregiver, otherPatient, doctor, admin)) {
            assertEquals(0, allOf(jdbc, outsider.getSystemId()), outsider.getSystemId());
        }
        // The revoked caregiver reads nothing any more
        assertStatus(403, doGet("/api/lab-results?patientSystemId=" + patient.getSystemId(), caregiver));
    }

    // ==================== EXPIRY ====================

    @Test
    void expiryNoticeAndExpiry_viaTheJobs_eachNotifyOnce_andOnlyTheExpiryWritesALedgerRow() throws Exception {
        int accessId = grantDirectly(patient, caregiver, LocalDate.now().plusDays(2));
        assertEquals(List.of("GRANTED"), ledgerKinds(accessId));
        assertEquals("Access granted", onlyWithKey(jdbc, caregiver.getSystemId(),
                "ACCESS_CHANGE:" + accessId + ":" + newestConsentId(accessId)).get("title"));
        assertStatus(200, doGet(RECORDS + patient.getSystemId(), caregiver));

        // The 3-day notice: one ACCESS_CHANGE notification, once per expiry date, no ledger row
        assertEquals(1, expiryNoticeJob.sweepOnce(LocalDateTime.now()));
        assertEquals(0, expiryNoticeJob.sweepOnce(LocalDateTime.now()));
        assertEquals(1, withKeyPrefix(jdbc, caregiver.getSystemId(), "ACCESS_EXPIRING:" + accessId + ":"));
        assertEquals(1, ledger(accessId).size());

        // The grant lapses: the sweeper records EXPIRED (no actor) and tells the caregiver once
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE access_id = ?",
                LocalDateTime.now().minusMinutes(5), accessId);
        assertEquals(1, expirySweeper.sweepOnce(LocalDateTime.now()));
        assertEquals(0, expirySweeper.sweepOnce(LocalDateTime.now()));
        assertEquals(List.of("GRANTED", "EXPIRED"), ledgerKinds(accessId));
        assertNull(ledger(accessId).get(1).get("granted_by_system_id"));
        Map<String, Object> expired = onlyWithKey(jdbc, caregiver.getSystemId(),
                "ACCESS_CHANGE:" + accessId + ":" + newestConsentId(accessId));
        assertEquals("Your access has expired", expired.get("title"));
        assertNull(expired.get("actor_system_id"));
        assertEquals(3, ofCategory(jdbc, caregiver.getSystemId(), "ACCESS_CHANGE"), "granted, expiring, expired");
        // The lapse is recorded on the grant once, and the caregiver reads nothing any more
        assertNotNull(jdbc.queryForObject("SELECT expiry_processed_at FROM dependent_access WHERE access_id = ?",
                Object.class, accessId));
        assertStatus(403, doGet(RECORDS + patient.getSystemId(), caregiver));
    }

    // ==================== TOGGLE ====================

    @Test
    void switchedOffToggle_silencesEveryAccessChange_butNotTheApproval_andTheLedgerIsWrittenRegardless()
            throws Exception {
        switchOffAccessChangeAlerts(caregiver);

        int requestId = requestAccess(caregiver, patient);
        int accessId = approve(patient, requestId);
        assertStatus(200, doPatch(GRANTS + "/" + accessId, patient, Map.of("canViewImaging", true)));
        assertStatus(204, doDelete(GRANTS + "/" + accessId, patient));
        assertEquals(List.of("GRANTED", "PERMISSIONS_CHANGED", "REVOKED"), ledgerKinds(accessId));

        int otherAccessId = grantDirectly(otherPatient, caregiver, LocalDate.now().plusDays(2));
        assertEquals(0, expiryNoticeJob.sweepOnce(LocalDateTime.now()), "the notice is switched off");
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE access_id = ?",
                LocalDateTime.now().minusMinutes(5), otherAccessId);
        assertEquals(1, expirySweeper.sweepOnce(LocalDateTime.now()));
        assertEquals(List.of("GRANTED", "EXPIRED"), ledgerKinds(otherAccessId));

        // The approval is SYSTEM and still arrives; nothing else does
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), "ACCESS_REQUEST_APPROVED:" + requestId));
        assertEquals(0, ofCategory(jdbc, caregiver.getSystemId(), "ACCESS_CHANGE"));
        assertEquals(1, allOf(jdbc, caregiver.getSystemId()));
    }

    // ==================== DEACTIVATED CAREGIVER ====================

    @Test
    void deactivatedCaregiver_losesEveryGrantWithOneLedgerRowEach_andEveryConversationMembership() throws Exception {
        DependentAccess first = grantApprovedAccess(caregiver, patient);
        DependentAccess second = grantApprovedAccess(caregiver, otherPatient);
        DependentAccess untouched = grantApprovedAccess(otherCaregiver, patient);
        int threadId = careTeamThreadWithCaregiver(patient, doctor, caregiver);
        assertNull(removalReason(threadId, caregiver));

        deactivate(caregiver, "Requested by the family");

        assertFalse(isActive(caregiver));
        for (DependentAccess grant : List.of(first, second)) {
            DependentAccess stored = dependentAccessRepository.findById(grant.getAccessId()).orElseThrow();
            assertFalse(stored.getIsActive());
            assertNotNull(stored.getRevokedAt());
            List<Map<String, Object>> rows = ledger(grant.getAccessId());
            assertEquals(1, rows.size(), rows.toString());
            assertEquals("REVOKED", rows.get(0).get("event_kind"));
            assertEquals(admin.getSystemId(), rows.get(0).get("granted_by_system_id"));
        }
        assertEquals("ACCESS_REVOKED", removalReason(threadId, caregiver));
        assertEquals(1, count("SELECT COUNT(*) FROM thread_members WHERE thread_id = ? AND system_id = ? "
                + "AND removed_at IS NOT NULL", threadId, caregiver.getSystemId()));

        // Another caregiver's grant and membership are untouched and get no ledger row
        assertTrue(dependentAccessRepository.findById(untouched.getAccessId()).orElseThrow().getIsActive());
        assertTrue(ledger(untouched.getAccessId()).isEmpty());
        // The patients still see the conversation without the caregiver
        for (var member : json(doGet(THREADS + "/" + threadId, patient)).path("members")) {
            assertFalse(caregiver.getSystemId().equals(member.path("systemId").asText()), "caregiver still listed");
        }
    }
}
