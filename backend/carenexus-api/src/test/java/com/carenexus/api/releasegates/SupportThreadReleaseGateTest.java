package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gates for support conversations (be-communications-hardening section 5): one open conversation per user,
 * administrators answer only support conversations, the admin inbox is administrator-only, nobody else reads someone's
 * support conversation, and administrators are notified once per thread and hour.
 */
class SupportThreadReleaseGateTest extends CommittedIntegrationTest {

    private static final String THREADS = "/api/communications/threads";
    private static final String OPEN = "/api/communications/support-thread";
    private static final String INBOX = "/api/admin/support/threads";

    private int openSupport(User as, String subject) throws Exception {
        MvcResult result = doPost(OPEN, as, subject == null ? null : Map.of("subject", subject));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("threadId").asInt();
    }

    private MvcResult send(int threadId, User as, String clientMessageId, String body, boolean urgent) throws Exception {
        return doPost(THREADS + "/" + threadId + "/messages", as,
                Map.of("clientMessageId", clientMessageId, "body", body, "urgent", urgent));
    }

    private int auditRows(String action, Integer threadId) {
        return threadId == null
                ? jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_type = 'SUPPORT_THREAD' AND action = ?"
                + " AND resource_id IS NULL", Integer.class, action)
                : jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_type = 'SUPPORT_THREAD' AND action = ?"
                + " AND resource_id = ?", Integer.class, action, threadId);
    }

    // ==================== ONE OPEN THREAD PER USER ====================

    @Test
    void eachUserHasOneOpenSupportThread_withoutAnyPatientContext() throws Exception {
        // The caregiver fixture holds no grant at all: contacting support needs none
        MvcResult created = doPost(OPEN, caregiver, Map.of("subject", "  Cannot see my invoice  "));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode thread = json(created);
        int threadId = thread.path("threadId").asInt();
        assertEquals("SUPPORT", thread.path("type").asText());
        assertEquals("Cannot see my invoice", thread.path("subject").asText());
        assertTrue(thread.path("patientSystemId").isNull());
        assertTrue(thread.path("patientName").isNull());
        assertFalse(thread.path("canManageMembers").asBoolean());
        assertFalse(thread.path("closed").asBoolean());
        assertTrue(thread.path("closedAt").isNull());
        assertEquals(1, thread.path("members").size());
        assertEquals("CAREGIVER", thread.path("members").get(0).path("role").asText());
        assertTrue(thread.path("members").get(0).path("label").asText().endsWith("(Caregiver)"));

        // Archive, then open again: the same thread, archive flag cleared, subject ignored
        assertEquals(204, doPut(THREADS + "/" + threadId + "/archive", caregiver, Map.of("archived", true))
                .getResponse().getStatus());
        MvcResult again = doPost(OPEN, caregiver, Map.of("subject", "Another subject"));
        assertEquals(200, again.getResponse().getStatus());
        assertEquals(threadId, json(again).path("threadId").asInt());
        assertEquals("Cannot see my invoice", json(again).path("subject").asText());
        assertFalse(json(again).path("archived").asBoolean());
        assertEquals(200, doPost(OPEN, caregiver, null).getResponse().getStatus());

        // Each role has its own; administrators cannot open one
        int patientThread = openSupport(patient, null);
        int doctorThread = openSupport(doctor, "");
        assertNotEquals(patientThread, doctorThread);
        assertTrue(json(doGet(THREADS + "/" + doctorThread, doctor)).path("subject").isNull());
        MvcResult adminOpen = doPost(OPEN, admin, Map.of());
        assertEquals(403, adminOpen.getResponse().getStatus());
        assertEquals("Administrators cannot open a support conversation", message(adminOpen));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM message_threads WHERE conversation_type = 'SUPPORT'"
                + " AND created_by_system_id = ?", Integer.class, caregiver.getSystemId()));
        assertEquals("PATIENT_SUPPORT", jdbc.queryForObject("SELECT thread_type FROM message_threads WHERE thread_id = ?",
                String.class, threadId));
        assertEquals(1, auditRows("CREATE", threadId));
        // Opening notifies nobody
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class));

        // The opener sees it in the normal list without a patient filter, not with one
        JsonNode list = json(doGet(THREADS, caregiver));
        assertEquals(1, list.path("totalElements").asInt());
        assertEquals(threadId, list.path("content").get(0).path("threadId").asInt());
        JsonNode patientList = json(doGet(THREADS, patient));
        assertEquals(1, patientList.path("totalElements").asInt());
        assertEquals(0, json(doGet(THREADS + "?patientSystemId=" + patient.getSystemId(), patient))
                .path("totalElements").asInt());
    }

    @Test
    void concurrentFirstOpens_produceOneThread() throws Exception {
        int callers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<MvcResult> open = () -> {
                    start.await();
                    return doPost(OPEN, otherPatient, Map.of("subject", "Login problem"));
                };
                futures.add(pool.submit(open));
            }
            start.countDown();
            Set<Integer> ids = new HashSet<>();
            int createdCount = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(30, TimeUnit.SECONDS);
                int status = result.getResponse().getStatus();
                assertTrue(status == 200 || status == 201, status + " " + result.getResponse().getContentAsString());
                if (status == 201) {
                    createdCount++;
                }
                ids.add(json(result).path("threadId").asInt());
            }
            assertEquals(1, ids.size());
            assertEquals(1, createdCount);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM message_threads WHERE created_by_system_id = ?",
                Integer.class, otherPatient.getSystemId()));
    }

    // ==================== ADMINISTRATORS ANSWER ====================

    @Test
    void administratorCanAnswer_andTheOpenerIsNotified() throws Exception {
        int threadId = openSupport(caregiver, "Cannot see my invoice");
        // Urgent is ignored in a support conversation
        MvcResult question = send(threadId, caregiver, clientId(), "The invoice from last week is missing.", true);
        assertEquals(201, question.getResponse().getStatus(), question.getResponse().getContentAsString());
        assertFalse(json(question).path("urgent").asBoolean());
        assertEquals("CAREGIVER", json(question).path("senderRole").asText());
        assertTrue(json(question).path("senderLabel").asText().endsWith("(Caregiver)"));
        int questionId = json(question).path("messageId").asInt();

        // Inbox: listed with opener and unread flag; listing does not join
        JsonNode inbox = json(doGet(INBOX, admin));
        assertEquals(1, inbox.path("totalElements").asInt());
        JsonNode row = inbox.path("content").get(0);
        assertEquals(threadId, row.path("threadId").asInt());
        assertEquals(caregiver.getSystemId(), row.path("openerSystemId").asText());
        assertEquals("CAREGIVER", row.path("openerRole").asText());
        assertEquals("CAREGIVER User3", row.path("openerName").asText());
        assertTrue(row.path("open").asBoolean());
        assertTrue(row.path("unread").asBoolean());
        assertEquals(questionId, row.path("lastMessage").path("messageId").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM thread_members WHERE thread_id = ? AND system_id = ?",
                Integer.class, threadId, admin.getSystemId()));
        assertEquals(1, auditRows("READ", null));

        // First thread request joins the administrator (full history)
        MvcResult adminView = doGet(THREADS + "/" + threadId, admin);
        assertEquals(200, adminView.getResponse().getStatus(), adminView.getResponse().getContentAsString());
        assertEquals(1, json(adminView).path("unreadCount").asInt());
        assertEquals("ADMIN", jdbc.queryForObject("SELECT member_role FROM thread_members WHERE thread_id = ? AND system_id = ?",
                String.class, threadId, admin.getSystemId()));
        assertEquals(1, auditRows("UPDATE", threadId));
        JsonNode history = json(doGet(THREADS + "/" + threadId + "/messages", admin));
        assertEquals(1, history.path("messages").size());
        assertEquals("The invoice from last week is missing.", history.path("messages").get(0).path("body").asText());
        assertEquals(1, auditRows("UPDATE", threadId), "joining happens once");

        MvcResult answer = send(threadId, admin, clientId(), "Thanks, we are looking into it.", false);
        assertEquals(201, answer.getResponse().getStatus(), answer.getResponse().getContentAsString());
        assertEquals("ADMIN", json(answer).path("senderRole").asText());
        assertTrue(json(answer).path("senderLabel").asText().endsWith("(Support)"));
        assertFalse(json(doGet(INBOX, admin)).path("content").get(0).path("unread").asBoolean());

        // The opener reads the answer and got one MESSAGE notification for it
        JsonNode openerView = json(doGet(THREADS + "/" + threadId + "/messages", caregiver));
        assertEquals(2, openerView.path("messages").size());
        Map<String, Object> notification = jdbc.queryForMap("SELECT type, thread_id, message_id, patient_system_id, message"
                + " FROM notifications WHERE system_id = ?", caregiver.getSystemId());
        assertEquals("MESSAGE", notification.get("type"));
        assertEquals(threadId, ((Number) notification.get("thread_id")).intValue());
        assertEquals(json(answer).path("messageId").asInt(), ((Number) notification.get("message_id")).intValue());
        assertNull(notification.get("patient_system_id"));
        assertFalse(String.valueOf(notification.get("message")).contains("null"), String.valueOf(notification.get("message")));
        assertEquals(1, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());
        JsonNode openerThread = json(doGet(THREADS + "/" + threadId, caregiver));
        assertEquals(2, openerThread.path("members").size());
        assertFalse(openerThread.path("hasUrgentUnread").asBoolean());

        // Support conversations never change members
        MvcResult addMember = doPost(THREADS + "/" + threadId + "/members", caregiver, Map.of("systemIds", List.of(doctor.getSystemId())));
        assertEquals(409, addMember.getResponse().getStatus());
        assertEquals("Members of a support conversation cannot be changed", message(addMember));
        assertEquals(409, doDelete(THREADS + "/" + threadId + "/members/" + admin.getSystemId(), admin).getResponse().getStatus());
        MvcResult adminArchive = doPut(THREADS + "/" + threadId + "/archive", admin, Map.of("archived", true));
        assertEquals(409, adminArchive.getResponse().getStatus());
        assertEquals("Support conversations are closed, not archived", message(adminArchive));
    }

    @Test
    void administratorGets403OnEveryClinicalThread() throws Exception {
        MvcResult created = doPost(THREADS, doctor, Map.of("patientSystemId", patient.getSystemId(), "type", "CARE_TEAM",
                "clientThreadId", clientId()));
        assertEquals(201, created.getResponse().getStatus());
        int clinical = json(created).path("threadId").asInt();
        assertEquals(201, send(clinical, doctor, clientId(), "Please measure twice a day.", false).getResponse().getStatus());

        for (MvcResult denied : List.of(
                doGet(THREADS + "/" + clinical, admin),
                doGet(THREADS + "/" + clinical + "/messages", admin),
                send(clinical, admin, clientId(), "hello", false),
                doPost(THREADS + "/" + clinical + "/read", admin, Map.of("lastReadMessageId", 1)),
                doPut(THREADS + "/" + clinical + "/archive", admin, Map.of("archived", true)),
                doPost(THREADS + "/" + clinical + "/members", admin, Map.of("systemIds", List.of(admin.getSystemId()))))) {
            assertEquals(403, denied.getResponse().getStatus(), denied.getResponse().getContentAsString());
            assertEquals("Administrators cannot access conversations", message(denied));
        }
        // No membership was created for the administrator and nothing was sent
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM thread_members WHERE system_id = ?",
                Integer.class, admin.getSystemId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM messages WHERE thread_id = ?", Integer.class, clinical));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = ?"
                + " AND resource_type = 'MESSAGE_THREAD'", Integer.class, admin.getSystemId()) >= 1);
        // Closing a clinical thread through the support route does not confirm it exists
        MvcResult close = doPost(INBOX + "/" + clinical + "/close", admin, null);
        assertEquals(404, close.getResponse().getStatus());
        assertEquals("Conversation not found", message(close));
        // Unknown ids answer 404 for administrators too
        assertEquals(404, doGet(THREADS + "/999999", admin).getResponse().getStatus());
    }

    // ==================== ADMIN INBOX IS ADMIN ONLY ====================

    @Test
    void nonAdministratorsCannotUseTheInbox() throws Exception {
        int threadId = openSupport(patient, "Help");
        assertEquals(201, send(threadId, patient, clientId(), "Hello support", false).getResponse().getStatus());

        for (User user : List.of(patient, doctor, caregiver, otherPatient)) {
            MvcResult list = doGet(INBOX, user);
            assertEquals(403, list.getResponse().getStatus(), "inbox for " + user.getSystemId());
            assertEquals("You don't have permission to access this resource", message(list));
            assertEquals(403, doPost(INBOX + "/" + threadId + "/close", user, null).getResponse().getStatus());
        }
        assertNull(jdbc.queryForObject("SELECT closed_at FROM message_threads WHERE thread_id = ?", Object.class, threadId));

        // The administrator inbox: filters, size clamp, threads without messages are not listed
        openSupport(caregiver, "Nothing written yet");
        JsonNode all = json(doGet(INBOX + "?size=500&page=-2", admin));
        assertEquals(1, all.path("totalElements").asInt());
        assertEquals(100, all.path("size").asInt());
        assertEquals(0, all.path("page").asInt());
        assertEquals(1, json(doGet(INBOX + "?open=true&size=1", admin)).path("totalElements").asInt());
        assertEquals(0, json(doGet(INBOX + "?open=false", admin)).path("totalElements").asInt());
    }

    // ==================== PRIVACY BETWEEN USERS ====================

    @Test
    void nobodyElseCanReadSomeonesSupportThread() throws Exception {
        int threadId = openSupport(caregiver, "Private question");
        assertEquals(201, send(threadId, caregiver, clientId(), "About my account", false).getResponse().getStatus());

        for (User outsider : List.of(patient, doctor, otherCaregiver, otherPatient, otherDoctor)) {
            MvcResult read = doGet(THREADS + "/" + threadId, outsider);
            assertEquals(403, read.getResponse().getStatus(), "thread read by " + outsider.getSystemId());
            assertEquals("You no longer have access to this conversation", message(read));
            assertEquals(403, doGet(THREADS + "/" + threadId + "/messages", outsider).getResponse().getStatus());
            assertEquals(403, send(threadId, outsider, clientId(), "hi", false).getResponse().getStatus());
            assertEquals(403, doPost(THREADS + "/" + threadId + "/read", outsider, Map.of("lastReadMessageId", 0))
                    .getResponse().getStatus());
            for (JsonNode listed : json(doGet(THREADS, outsider)).path("content")) {
                assertNotEquals(threadId, listed.path("threadId").asInt());
            }
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM thread_members WHERE thread_id = ? AND system_id <> ?",
                Integer.class, threadId, caregiver.getSystemId()));
    }

    // ==================== NOTIFICATIONS ====================

    @Test
    void administratorsAreNotifiedOncePerThreadAndHour() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "admin2@example.com", "ADMIN");
        User inactiveAdmin = createAndSaveUser(9, "ADM-00003", "admin3@example.com", "ADMIN");
        inactiveAdmin.setIsActive(false);
        userRepository.saveAndFlush(inactiveAdmin);

        int threadId = openSupport(caregiver, "Login problem");
        for (int i = 0; i < 3; i++) {
            assertEquals(201, send(threadId, caregiver, clientId(), "Message " + i, false).getResponse().getStatus());
        }

        for (User a : List.of(admin, secondAdmin)) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, category, target, resource_type, resource_id,"
                    + " thread_id, message_id, patient_system_id, actor_system_id, action_url, title, message, dedupe_key"
                    + " FROM notifications WHERE system_id = ?", a.getSystemId());
            assertEquals(1, rows.size(), "notifications of " + a.getSystemId());
            Map<String, Object> n = rows.get(0);
            assertEquals("MESSAGE", n.get("type"));
            assertEquals("MESSAGE", n.get("category"));
            assertEquals("MESSAGES", n.get("target"));
            assertEquals("SUPPORT_THREAD", n.get("resource_type"));
            assertEquals(threadId, ((Number) n.get("resource_id")).intValue());
            assertNull(n.get("thread_id"));
            assertNull(n.get("message_id"));
            assertNull(n.get("patient_system_id"));
            assertEquals(caregiver.getSystemId(), n.get("actor_system_id"));
            assertEquals("/dashboard/admin?section=messages&id=" + threadId, n.get("action_url"));
            assertEquals("New support message", n.get("title"));
            assertEquals("CAREGIVER User3 sent a message to support", n.get("message"));
            assertTrue(String.valueOf(n.get("dedupe_key")).startsWith("SUPPORT_MESSAGE:" + threadId + ":"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, inactiveAdmin.getSystemId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, caregiver.getSystemId()));
        assertEquals(1, json(doGet("/api/notifications/unread-count", admin)).path("unreadMessageCount").asInt());

        // One administrator answers twice: the opener gets one notification per message, the other administrator none
        int lastId = 0;
        for (int i = 0; i < 2; i++) {
            MvcResult answer = send(threadId, admin, clientId(), "Answer " + i, false);
            assertEquals(201, answer.getResponse().getStatus());
            lastId = json(answer).path("messageId").asInt();
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ? AND thread_id = ?",
                Integer.class, caregiver.getSystemId(), threadId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, secondAdmin.getSystemId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, admin.getSystemId()));

        // Reading the thread clears the administrator's support notification
        MvcResult read = doPost(THREADS + "/" + threadId + "/read", admin, Map.of("lastReadMessageId", lastId));
        assertEquals(200, read.getResponse().getStatus(), read.getResponse().getContentAsString());
        assertEquals(0, json(doGet("/api/notifications/unread-count", admin)).path("unreadMessageCount").asInt());
        assertEquals(1, json(doGet("/api/notifications/unread-count", secondAdmin)).path("unreadMessageCount").asInt());
        MvcResult openerRead = doPost(THREADS + "/" + threadId + "/read", caregiver, Map.of("lastReadMessageId", lastId));
        assertEquals(0, json(openerRead).path("unreadCount").asInt());
        assertEquals(0, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());
    }

    // ==================== CLOSING ====================

    @Test
    void closedThreadStaysReadable_refusesNewMessages_andANewOneCanBeOpened() throws Exception {
        int threadId = openSupport(patient, "Billing question");
        String storedId = clientId();
        assertEquals(201, send(threadId, patient, storedId, "Why was I charged twice?", false).getResponse().getStatus());

        MvcResult closed = doPost(INBOX + "/" + threadId + "/close", admin, null);
        assertEquals(200, closed.getResponse().getStatus(), closed.getResponse().getContentAsString());
        assertFalse(json(closed).path("open").asBoolean());
        String closedAt = json(closed).path("closedAt").asText();
        assertFalse(closedAt.isBlank());
        // Closing does not join and does not notify
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM thread_members WHERE thread_id = ? AND system_id = ?",
                Integer.class, threadId, admin.getSystemId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, patient.getSystemId()));
        MvcResult closedAgain = doPost(INBOX + "/" + threadId + "/close", admin, null);
        assertEquals(200, closedAgain.getResponse().getStatus());
        assertEquals(closedAt, json(closedAgain).path("closedAt").asText());
        assertEquals(1, auditRows("UPDATE", threadId));

        MvcResult refused = send(threadId, patient, clientId(), "Hello?", false);
        assertEquals(409, refused.getResponse().getStatus());
        assertEquals("This support conversation is closed", message(refused));
        assertEquals(409, send(threadId, admin, clientId(), "Reopening?", false).getResponse().getStatus());
        // A retry of the message stored before the close still answers 200
        assertEquals(200, send(threadId, patient, storedId, "Why was I charged twice?", false).getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM messages WHERE thread_id = ?", Integer.class, threadId));

        // Still readable by the opener
        JsonNode thread = json(doGet(THREADS + "/" + threadId, patient));
        assertTrue(thread.path("closed").asBoolean());
        assertFalse(thread.path("closedAt").isNull());
        assertEquals(1, json(doGet(THREADS + "/" + threadId + "/messages", patient)).path("messages").size());
        assertEquals(200, doPost(THREADS + "/" + threadId + "/read", patient, Map.of("lastReadMessageId", 0))
                .getResponse().getStatus());

        // A new support conversation can be opened
        int next = openSupport(patient, "Another question");
        assertNotEquals(threadId, next);
        assertEquals(1, json(doGet(INBOX + "?open=false", admin)).path("totalElements").asInt());
        assertEquals(0, json(doGet(INBOX + "?open=true", admin)).path("totalElements").asInt());
        assertEquals(404, doPost(INBOX + "/999999/close", admin, null).getResponse().getStatus());
    }
}
