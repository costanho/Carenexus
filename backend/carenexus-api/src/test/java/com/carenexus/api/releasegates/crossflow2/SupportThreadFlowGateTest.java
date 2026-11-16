package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.allOf;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate of support conversations: a patient opens a support thread (be-communications-hardening), the
 * administrators are notified through the publisher (be-foundation-notify), an administrator answers through the
 * shared messaging endpoints, and the patient receives exactly one MESSAGE notification that names the administrator
 * as Support and no patient (API-NOTES: '&lt;First Last&gt; (Support) sent you a message'). The same administrator can
 * neither open nor read a clinical conversation, and nobody but the opener and the administrators sees the thread.
 */
class SupportThreadFlowGateTest extends CommittedIntegrationTest {

    private static final String THREADS = "/api/communications/threads";
    private static final String OPEN = "/api/communications/support-thread";
    private static final String INBOX = "/api/admin/support/threads";
    private static final String ADMINS_CANNOT = "Administrators cannot access conversations";

    private MvcResult send(int threadId, User as, String body) throws Exception {
        return doPost(THREADS + "/" + threadId + "/messages", as,
                Map.of("clientMessageId", clientId(), "body", body, "urgent", false));
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    @Test
    void patientOpensSupport_anAdministratorAnswers_andThePatientIsNotifiedOnce() throws Exception {
        // ---- the patient opens a support conversation and writes ----
        JsonNode opened = ok(doPost(OPEN, patient, Map.of("subject", "I cannot find my receipt")), 201);
        int threadId = opened.path("threadId").asInt();
        assertEquals("SUPPORT", opened.path("type").asText());
        assertTrue(opened.path("patientSystemId").isNull());
        JsonNode question = ok(send(threadId, patient, "Where do I download the receipt for last week's visit?"), 201);
        int questionId = question.path("messageId").asInt();

        // The administrator is told once, without a patient
        List<Map<String, Object>> toAdmin = jdbc.queryForList("SELECT type, resource_type, resource_id, "
                + "patient_system_id, actor_system_id, title FROM notifications WHERE system_id = ?", admin.getSystemId());
        assertEquals(1, toAdmin.size(), toAdmin.toString());
        assertEquals("MESSAGE", toAdmin.get(0).get("type"));
        assertEquals("SUPPORT_THREAD", toAdmin.get(0).get("resource_type"));
        assertEquals(threadId, ((Number) toAdmin.get(0).get("resource_id")).intValue());
        assertNull(toAdmin.get(0).get("patient_system_id"));
        assertEquals(patient.getSystemId(), toAdmin.get(0).get("actor_system_id"));
        assertEquals(0, allOf(jdbc, patient.getSystemId()), "the opener is not told about their own message");

        // ---- the administrator finds it in the inbox, reads it and answers ----
        JsonNode inbox = ok(doGet(INBOX + "?open=true", admin), 200);
        assertEquals(1, inbox.path("totalElements").asInt());
        assertEquals(threadId, inbox.path("content").get(0).path("threadId").asInt());
        assertEquals(patient.getSystemId(), inbox.path("content").get(0).path("openerSystemId").asText());
        JsonNode history = ok(doGet(THREADS + "/" + threadId + "/messages", admin), 200);
        assertEquals(questionId, history.path("messages").get(0).path("messageId").asInt());
        JsonNode answer = ok(send(threadId, admin, "Open Billing, choose the invoice and press Receipt."), 201);
        int answerId = answer.path("messageId").asInt();
        assertEquals("ADMIN", answer.path("senderRole").asText());

        // ---- the patient gets exactly one notification for the answer, naming Support and no patient ----
        List<Map<String, Object>> toPatient = jdbc.queryForList("SELECT type, category, message, thread_id, message_id, "
                + "patient_system_id, actor_system_id FROM notifications WHERE system_id = ?", patient.getSystemId());
        assertEquals(1, toPatient.size(), toPatient.toString());
        Map<String, Object> row = toPatient.get(0);
        assertEquals("MESSAGE", row.get("type"));
        assertEquals("MESSAGE", row.get("category"));
        assertEquals(admin.getFirstName() + " " + admin.getLastName() + " (Support) sent you a message",
                row.get("message"));
        assertEquals(threadId, ((Number) row.get("thread_id")).intValue());
        assertEquals(answerId, ((Number) row.get("message_id")).intValue());
        assertNull(row.get("patient_system_id"));
        assertEquals(admin.getSystemId(), row.get("actor_system_id"));
        assertEquals(1, json(doGet("/api/notifications/unread-count", patient)).path("unreadMessageCount").asInt());

        // The patient reads both messages; the messages carry no patient
        JsonNode mine = ok(doGet(THREADS + "/" + threadId + "/messages", patient), 200);
        assertEquals(2, mine.path("messages").size());
        assertEquals(2, count(jdbc, "SELECT COUNT(*) FROM messages WHERE thread_id = ? AND patient_system_id IS NULL",
                threadId));

        // Nobody else can see it
        for (User outsider : List.of(doctor, caregiver, otherPatient)) {
            assertEquals(403, doGet(THREADS + "/" + threadId, outsider).getResponse().getStatus(),
                    outsider.getSystemId());
            for (JsonNode listed : json(doGet(THREADS, outsider)).path("content")) {
                assertFalse(threadId == listed.path("threadId").asInt(), "listed for " + outsider.getSystemId());
            }
            assertEquals(0, allOf(jdbc, outsider.getSystemId()), outsider.getSystemId());
        }
    }

    @Test
    void theAnsweringAdministratorCannotOpenOrReadAClinicalConversation() throws Exception {
        // The administrator is an active support member first
        int supportThread = ok(doPost(OPEN, patient, Map.of("subject", "Login help")), 201).path("threadId").asInt();
        ok(send(supportThread, patient, "I was signed out."), 201);
        ok(send(supportThread, admin, "Please sign in again; your session had expired."), 201);

        // Opening a clinical conversation about the patient is refused and stores nothing
        int threadsBefore = count(jdbc, "SELECT COUNT(*) FROM message_threads");
        MvcResult create = doPost(THREADS, admin, Map.of("patientSystemId", patient.getSystemId(), "type", "CARE_TEAM",
                "subject", "Billing follow-up", "memberSystemIds", List.of(), "clientThreadId", clientId()));
        assertEquals(403, create.getResponse().getStatus(), create.getResponse().getContentAsString());
        assertEquals(threadsBefore, count(jdbc, "SELECT COUNT(*) FROM message_threads"));

        // The doctor's clinical conversation with the patient stays closed to the administrator
        JsonNode clinical = ok(doPost(THREADS, doctor, Map.of("patientSystemId", patient.getSystemId(),
                "type", "CARE_TEAM", "subject", "Blood pressure", "memberSystemIds", List.of(),
                "clientThreadId", clientId())), 201);
        int clinicalId = clinical.path("threadId").asInt();
        ok(send(clinicalId, doctor, "Please measure twice a day."), 201);
        for (MvcResult denied : List.of(
                doGet(THREADS + "/" + clinicalId, admin),
                doGet(THREADS + "/" + clinicalId + "/messages", admin),
                send(clinicalId, admin, "Hello"))) {
            assertEquals(403, denied.getResponse().getStatus(), denied.getResponse().getContentAsString());
            assertEquals(ADMINS_CANNOT, message(denied));
        }
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM thread_members WHERE thread_id = ? AND system_id = ?",
                clinicalId, admin.getSystemId()));
        // The administrator's own list holds only the support conversation
        JsonNode adminList = json(doGet(THREADS, admin));
        for (JsonNode listed : adminList.path("content")) {
            assertEquals("SUPPORT", listed.path("type").asText(), listed.toString());
        }
        // The clinical thread is not in the support inbox, and closing it there does not reveal it
        for (JsonNode listed : json(doGet(INBOX, admin)).path("content")) {
            assertFalse(clinicalId == listed.path("threadId").asInt());
        }
        assertEquals(404, doPost(INBOX + "/" + clinicalId + "/close", admin, null).getResponse().getStatus());
    }
}
