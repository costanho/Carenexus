package com.carenexus.api.releasegates;

import com.carenexus.api.core.model.DependentAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Release gates for Care Direct / Care Proxy messaging (CONTRACT.md section 7). */
class MessagingReleaseGateTest extends CommittedIntegrationTest {

    private static final String THREADS = "/api/communications/threads";

    private int createCareTeamThread() throws Exception {
        MvcResult created = doPost(THREADS, doctor, Map.of(
                "patientSystemId", patient.getSystemId(),
                "type", "CARE_TEAM",
                "subject", "Blood pressure follow-up",
                "memberSystemIds", List.of(),
                "clientThreadId", clientId()));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("threadId").asInt();
    }

    private MvcResult send(int threadId, com.carenexus.api.auth.model.User as, String clientMessageId, String body)
            throws Exception {
        return doPost(THREADS + "/" + threadId + "/messages", as,
                Map.of("clientMessageId", clientMessageId, "body", body, "urgent", false));
    }

    @Test
    void unrelatedUsersAndGuessedIdsAreDenied() throws Exception {
        int threadId = createCareTeamThread();
        assertEquals(201, send(threadId, doctor, clientId(), "Please measure twice a day.").getResponse().getStatus());

        for (var outsider : List.of(otherPatient, otherDoctor, otherCaregiver)) {
            MvcResult thread = doGet(THREADS + "/" + threadId, outsider);
            assertEquals(403, thread.getResponse().getStatus(), "thread read by " + outsider.getSystemId());
            assertEquals("You no longer have access to this conversation", message(thread));
            assertEquals(403, doGet(THREADS + "/" + threadId + "/messages", outsider).getResponse().getStatus());
            assertEquals(403, send(threadId, outsider, clientId(), "hello").getResponse().getStatus());
            assertEquals(403, doPost(THREADS + "/" + threadId + "/read", outsider,
                    Map.of("lastReadMessageId", 1)).getResponse().getStatus());
            assertEquals(403, doPost(THREADS + "/" + threadId + "/members", outsider,
                    Map.of("systemIds", List.of(outsider.getSystemId()))).getResponse().getStatus());
            JsonNode list = json(doGet(THREADS, outsider));
            assertEquals(0, list.path("totalElements").asInt(), "outsider thread list");
        }

        // Guessed id that does not exist.
        MvcResult missing = doGet(THREADS + "/999999", patient);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Conversation not found", message(missing));
        assertEquals(404, doGet(THREADS + "/999999/messages", patient).getResponse().getStatus());

        // Admins never read or send messages.
        MvcResult adminRead = doGet(THREADS + "/" + threadId + "/messages", admin);
        assertEquals(403, adminRead.getResponse().getStatus());
        assertEquals("Administrators cannot access conversations", message(adminRead));
        assertEquals(403, send(threadId, admin, clientId(), "hi").getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM thread_members WHERE system_id = ?",
                Integer.class, admin.getSystemId()));
        // An unknown id answers 404 for administrators as well
        assertEquals(404, doGet(THREADS + "/999999/messages", admin).getResponse().getStatus());

        // A caregiver of the patient without a grant cannot get in either, even with the thread id.
        assertEquals(403, doGet(THREADS + "/" + threadId + "/messages", caregiver).getResponse().getStatus());
    }

    @Test
    void newlyAddedMemberCannotSeeEarlierMessagesPreviewsUnreadOrNotifications() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int threadId = createCareTeamThread();
        assertEquals(201, send(threadId, doctor, clientId(), "Earlier message one").getResponse().getStatus());
        assertEquals(201, send(threadId, patient, clientId(), "Earlier message two").getResponse().getStatus());

        MvcResult added = doPost(THREADS + "/" + threadId + "/members", patient,
                Map.of("systemIds", List.of(caregiver.getSystemId())));
        assertEquals(200, added.getResponse().getStatus(), added.getResponse().getContentAsString());

        JsonNode messages = json(doGet(THREADS + "/" + threadId + "/messages", caregiver));
        assertEquals(0, messages.path("messages").size(), "earlier messages must be hidden");
        JsonNode threads = json(doGet(THREADS, caregiver));
        assertEquals(1, threads.path("totalElements").asInt());
        JsonNode thread = threads.path("content").get(0);
        assertTrue(thread.path("lastMessage").isNull(), "no preview of earlier messages: " + thread);
        assertEquals(0, thread.path("unreadCount").asInt());
        assertEquals(0, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadCount").asInt());
        assertEquals(0, json(doGet("/api/notifications", caregiver)).path("totalElements").asInt());
        // Paging backwards from a guessed cursor does not reveal history either.
        assertEquals(0, json(doGet(THREADS + "/" + threadId + "/messages?before=999999", caregiver))
                .path("messages").size());

        assertEquals(201, send(threadId, doctor, clientId(), "Welcome Sarah").getResponse().getStatus());
        messages = json(doGet(THREADS + "/" + threadId + "/messages", caregiver));
        assertEquals(1, messages.path("messages").size());
        assertEquals("Welcome Sarah", messages.path("messages").get(0).path("body").asText());
        thread = json(doGet(THREADS + "/" + threadId, caregiver));
        assertEquals(1, thread.path("unreadCount").asInt());
        assertEquals(1, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());
    }

    @Test
    void duplicateClientMessageIdStoresOneMessage() throws Exception {
        int threadId = createCareTeamThread();
        String clientMessageId = clientId();

        MvcResult first = send(threadId, patient, clientMessageId, "Is it fine to take it at night?");
        MvcResult retry = send(threadId, patient, clientMessageId, "Is it fine to take it at night? (retry)");
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, retry.getResponse().getStatus());
        assertEquals(json(first).path("messageId").asInt(), json(retry).path("messageId").asInt());
        assertEquals("Is it fine to take it at night?", json(retry).path("body").asText());

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE thread_id = ? AND client_message_id = ?",
                Integer.class, threadId, clientMessageId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE system_id = ? AND thread_id = ?",
                Integer.class, doctor.getSystemId(), threadId));
    }

    @Test
    void revokedCaregiverLosesThreadAccess() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int threadId = createCareTeamThread();
        assertEquals(200, doPost(THREADS + "/" + threadId + "/members", patient,
                Map.of("systemIds", List.of(caregiver.getSystemId()))).getResponse().getStatus());
        assertEquals(201, send(threadId, caregiver, clientId(), "Dad slept well").getResponse().getStatus());
        assertEquals(201, send(threadId, doctor, clientId(), "Good to hear").getResponse().getStatus());
        assertEquals(1, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());

        MvcResult revoke = doDelete("/api/patients/me/caregiver-access/" + access.getAccessId(), patient);
        assertEquals(204, revoke.getResponse().getStatus(), revoke.getResponse().getContentAsString());

        MvcResult thread = doGet(THREADS + "/" + threadId, caregiver);
        assertEquals(403, thread.getResponse().getStatus());
        assertEquals("You no longer have access to this conversation", message(thread));
        assertEquals(403, doGet(THREADS + "/" + threadId + "/messages", caregiver).getResponse().getStatus());
        assertEquals(403, send(threadId, caregiver, clientId(), "still here?").getResponse().getStatus());
        assertEquals(0, json(doGet(THREADS, caregiver)).path("totalElements").asInt());
        assertEquals(0, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());

        // The listener removed the membership (not re-added if access returns).
        assertEquals("ACCESS_REVOKED", jdbc.queryForObject(
                "SELECT removal_reason FROM thread_members WHERE thread_id = ? AND system_id = ?",
                String.class, threadId, caregiver.getSystemId()));
        // The patient and doctor no longer see the caregiver as a member.
        JsonNode members = json(doGet(THREADS + "/" + threadId, patient)).path("members");
        for (JsonNode m : members) {
            assertTrue(!caregiver.getSystemId().equals(m.path("systemId").asText()), "caregiver still listed");
        }
    }

    @Test
    void expiredGrantCannotMessage() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int threadId = createCareTeamThread();
        assertEquals(200, doPost(THREADS + "/" + threadId + "/members", patient,
                Map.of("systemIds", List.of(caregiver.getSystemId()))).getResponse().getStatus());

        access.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        dependentAccessRepository.saveAndFlush(access);

        assertEquals(403, doGet(THREADS + "/" + threadId, caregiver).getResponse().getStatus());
        assertEquals(403, send(threadId, caregiver, clientId(), "hello").getResponse().getStatus());
        assertEquals(403, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());
    }
}
