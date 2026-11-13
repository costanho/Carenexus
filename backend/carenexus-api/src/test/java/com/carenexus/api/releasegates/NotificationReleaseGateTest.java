package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.communications.service.NotificationService;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for the notification endpoints and the notification model (V24): the new response fields, ownership
 * (another user can neither read nor mark), expired rows hidden from the list and the counts, the type filter and
 * the page envelope, caregiver fan-out with and without the permission and after expiry, the audit rows, and the
 * sender's notifications being read by sending.
 */
class NotificationReleaseGateTest extends CommittedIntegrationTest {

    private static final String NOTIFICATIONS = "/api/notifications";
    private static final String UNREAD = "/api/notifications/unread-count";
    private static final String THREADS = "/api/communications/threads";

    @Autowired
    private NotificationPublisher publisher;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    // ==================== helpers ====================

    private NotificationCommand.Builder labResult(int resultId) {
        return NotificationCommand.builder()
                .type(NotificationType.LAB_RESULT)
                .category(NotificationCategory.LAB_RESULT)
                .title("New lab result")
                .message("A lab result for Thomas Dube is available")
                .target(NotificationTarget.LAB_RESULTS)
                .resourceType("LAB_RESULT")
                .resourceId(resultId)
                .actorSystemId(doctor.getSystemId())
                .dedupeKey("LAB_RESULT:" + resultId);
    }

    private int publishTo(User recipient, NotificationType type, NotificationCategory category, String dedupeKey,
                          User aboutPatient, LocalDateTime expiresAt) {
        return publisher.publish(NotificationCommand.builder()
                .recipientSystemId(recipient.getSystemId())
                .type(type)
                .category(category)
                .title("Update")
                .message("Something changed")
                .patientSystemId(aboutPatient != null ? aboutPatient.getSystemId() : null)
                .dedupeKey(dedupeKey)
                .expiresAt(expiresAt)
                .build()).orElseThrow();
    }

    private int systemNotification(User recipient, String dedupeKey) {
        return publishTo(recipient, NotificationType.SYSTEM, NotificationCategory.SYSTEM, dedupeKey, null, null);
    }

    private JsonNode list(User as, String query) throws Exception {
        MvcResult result = doGet(NOTIFICATIONS + query, as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private JsonNode counts(User as) throws Exception {
        MvcResult result = doGet(UNREAD, as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int markRead(int notificationId, User as) throws Exception {
        return doPost(NOTIFICATIONS + "/" + notificationId + "/read", as, null).getResponse().getStatus();
    }

    private int auditRows(User actor, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'NOTIFICATION' "
                + "AND action = ? AND system_id = ?", Integer.class, action, actor.getSystemId());
    }

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

    private MvcResult send(int threadId, User as, String clientMessageId, String body) throws Exception {
        return doPost(THREADS + "/" + threadId + "/messages", as,
                Map.of("clientMessageId", clientMessageId, "body", body, "urgent", false));
    }

    // ==================== NEW FIELDS ====================

    @Test
    void listShowsTheNewFields_forPublisherRows_threadMessages_andApprovalRequests() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS, labResult(88).build());

        // A publisher row, as the caregiver sees it
        JsonNode page = list(caregiver, "");
        assertEquals(1, page.path("totalElements").asInt());
        JsonNode lab = page.path("content").get(0);
        Set<String> fields = new TreeSet<>();
        lab.fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(List.of("notificationId", "type", "title", "message", "priority", "read", "readAt",
                "createdAt", "threadId", "messageId", "patientSystemId", "actionUrl", "target", "category",
                "resourceType", "resourceId")), fields, "icon and expiresAt are not exposed; nulls are serialized");
        assertEquals("LAB_RESULT", lab.path("type").asText());
        assertEquals("New lab result", lab.path("title").asText());
        assertEquals("A lab result for Thomas Dube is available", lab.path("message").asText());
        assertEquals("NORMAL", lab.path("priority").asText());
        assertFalse(lab.path("read").asBoolean());
        assertTrue(lab.path("readAt").isNull());
        assertTrue(lab.path("createdAt").asText().endsWith("Z"), lab.path("createdAt").asText());
        assertTrue(lab.path("threadId").isNull());
        assertTrue(lab.path("messageId").isNull());
        assertEquals("PAT-00001", lab.path("patientSystemId").asText());
        assertEquals("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=88&tab=labs", lab.path("actionUrl").asText());
        assertEquals("LAB_RESULTS", lab.path("target").asText());
        assertEquals("LAB_RESULT", lab.path("category").asText());
        assertEquals("LAB_RESULT", lab.path("resourceType").asText());
        assertEquals(88, lab.path("resourceId").asInt());

        // The same event, as the patient sees it: the link of the patient dashboard
        JsonNode forPatient = list(patient, "").path("content").get(0);
        assertEquals("/dashboard/patient?section=lab-results&id=88", forPatient.path("actionUrl").asText());

        // A thread message keeps target and actionUrl null and is opened by threadId
        int threadId = createCareTeamThread();
        assertEquals(201, send(threadId, doctor, clientId(), "Please bring your readings.").getResponse().getStatus());
        JsonNode message = list(patient, "?type=MESSAGE").path("content").get(0);
        assertEquals("MESSAGE", message.path("type").asText());
        assertEquals("MESSAGE", message.path("category").asText());
        assertEquals(threadId, message.path("threadId").asInt());
        assertTrue(message.path("messageId").asInt() > 0);
        assertTrue(message.path("target").isNull());
        assertTrue(message.path("actionUrl").isNull());
        assertTrue(message.path("resourceType").isNull());
        assertTrue(message.path("resourceId").isNull());
        assertFalse(message.path("message").asText().contains("readings"), "never the message body");

        // A caregiver approval request keeps the target the clients already know
        notificationService.createCaregiverApprovalNotification(patient.getSystemId(), caregiver.getSystemId(), false, true);
        JsonNode approval = list(patient, "?type=system").path("content").get(0);
        assertEquals("SYSTEM", approval.path("type").asText());
        assertEquals("SYSTEM", approval.path("category").asText());
        assertEquals("CAREGIVER_ACCESS", approval.path("target").asText());
        assertEquals("/dashboard/patient?section=my-proxies", approval.path("actionUrl").asText());
        assertEquals("Caregiver approval needed", approval.path("title").asText());
        assertTrue(approval.path("resourceId").isNull());
        assertEquals("CAREGIVER_ACCESS", jdbc.queryForObject(
                "SELECT target FROM notifications WHERE notification_id = ?", String.class,
                approval.path("notificationId").asInt()), "the target is stored, not derived from the link");
    }

    // ==================== OWNERSHIP ====================

    @Test
    void anotherUserCanNeitherReadNorMark_andGuessedIdsAre404() throws Exception {
        int id = systemNotification(patient, "PASSWORD_CHANGED:1");

        for (User outsider : List.of(otherPatient, doctor, caregiver, otherCaregiver, admin)) {
            String who = outsider.getSystemId();
            assertEquals(0, list(outsider, "").path("totalElements").asInt(), "list as " + who);
            assertEquals(0, counts(outsider).path("unreadCount").asInt(), "count as " + who);
            MvcResult marked = doPost(NOTIFICATIONS + "/" + id + "/read", outsider, null);
            assertEquals(404, marked.getResponse().getStatus(), "mark as " + who);
            assertEquals("Notification not found", message(marked));
            assertEquals(204, doPost(NOTIFICATIONS + "/read-all", outsider, null).getResponse().getStatus());
        }
        // Nobody else's call changed the patient's row
        assertEquals(1, counts(patient).path("unreadCount").asInt());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_read FROM notifications WHERE notification_id = ?",
                Boolean.class, id));

        // A guessed id is answered exactly like someone else's id
        MvcResult missing = doPost(NOTIFICATIONS + "/999999/read", patient, null);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Notification not found", message(missing));
        MvcResult notANumber = doPost(NOTIFICATIONS + "/abc/read", patient, null);
        assertEquals(400, notANumber.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'notificationId'", message(notANumber));

        // The owner marks it; a repeat is a no-op that keeps the first read time
        assertEquals(204, markRead(id, patient));
        LocalDateTime firstReadAt = jdbc.queryForObject("SELECT read_at FROM notifications WHERE notification_id = ?",
                LocalDateTime.class, id);
        assertNotNull(firstReadAt);
        assertEquals(204, markRead(id, patient));
        assertEquals(firstReadAt, jdbc.queryForObject("SELECT read_at FROM notifications WHERE notification_id = ?",
                LocalDateTime.class, id));
        JsonNode row = list(patient, "").path("content").get(0);
        assertTrue(row.path("read").asBoolean());
        assertTrue(row.path("readAt").asText().endsWith("Z"));
        assertEquals(0, counts(patient).path("unreadCount").asInt());

        // No token: 401 on every endpoint
        assertEquals(401, mockMvc.perform(get(NOTIFICATIONS)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(UNREAD)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(post(NOTIFICATIONS + "/" + id + "/read")).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(post(NOTIFICATIONS + "/read-all")).andReturn().getResponse().getStatus());
    }

    @Test
    void adminHasOnlyTheirOwnNotifications() throws Exception {
        systemNotification(patient, "PASSWORD_CHANGED:1");
        int forAdmin = publisher.publish(NotificationCommand.builder()
                .recipientSystemId(admin.getSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .title("Clinician waiting for verification")
                .message("A new clinician account is waiting for verification")
                .target(NotificationTarget.ADMIN_CLINICIAN_QUEUE)
                .dedupeKey("DOCTOR_PENDING:DOC-00002")
                .build()).orElseThrow();

        JsonNode page = list(admin, "");
        assertEquals(1, page.path("totalElements").asInt());
        assertEquals(forAdmin, page.path("content").get(0).path("notificationId").asInt());
        assertEquals("/dashboard/admin?section=clinician-queue", page.path("content").get(0).path("actionUrl").asText());
        assertEquals(1, counts(admin).path("unreadCount").asInt());
        assertEquals(0, counts(admin).path("unreadMessageCount").asInt());
    }

    // ==================== EXPIRY ====================

    @Test
    void expiredRowsAreHiddenFromTheListAndTheCounts() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        int expired = publishTo(patient, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_REMINDER,
                "APPT_REMINDER_24H:1", patient, now.minusMinutes(1));
        int current = publishTo(patient, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_REMINDER,
                "APPT_REMINDER_24H:2", patient, now.plusHours(2));
        int forever = publishTo(patient, NotificationType.SYSTEM, NotificationCategory.SYSTEM, "WELCOME:1", null, null);
        int expiredMessageType = publishTo(patient, NotificationType.MESSAGE, NotificationCategory.SYSTEM,
                "SUPPORT_MESSAGE:1", null, now.minusDays(1));

        JsonNode page = list(patient, "");
        assertEquals(2, page.path("totalElements").asInt());
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("notificationId").asInt()));
        assertEquals(List.of(forever, current), ids, "newest first, expired rows left out");
        assertEquals(2, list(patient, "?unreadOnly=true").path("totalElements").asInt());
        assertEquals(1, list(patient, "?type=APPOINTMENT").path("totalElements").asInt());
        assertEquals(0, list(patient, "?type=MESSAGE").path("totalElements").asInt());
        JsonNode counts = counts(patient);
        assertEquals(2, counts.path("unreadCount").asInt());
        assertEquals(0, counts.path("unreadMessageCount").asInt(), "the expired MESSAGE row is not counted");

        // A row that expired between list and click can still be marked: no error for the user
        assertEquals(204, markRead(expired, patient));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_read FROM notifications WHERE notification_id = ?",
                Boolean.class, expired));
        assertEquals(2, counts(patient).path("unreadCount").asInt());

        // A row disappears once its expiry passes
        jdbc.update("UPDATE notifications SET expires_at = ? WHERE notification_id = ?", now.minusSeconds(1), current);
        assertEquals(1, list(patient, "").path("totalElements").asInt());
        assertEquals(1, counts(patient).path("unreadCount").asInt());

        // read-all covers hidden rows too
        assertEquals(204, doPost(NOTIFICATIONS + "/read-all", patient, null).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND is_read = FALSE",
                Integer.class, patient.getSystemId()));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_read FROM notifications WHERE notification_id = ?",
                Boolean.class, expiredMessageType));
        assertEquals(0, counts(patient).path("unreadCount").asInt());
    }

    // ==================== FILTER AND PAGE ENVELOPE ====================

    @Test
    void typeFilter_pageEnvelope_sizeClamp_and400ForBadParameters() throws Exception {
        int first = publishTo(patient, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:1", patient, null);
        int second = publishTo(patient, NotificationType.PAYMENT, NotificationCategory.BILLING, "INVOICE_ISSUED:1", patient, null);
        int third = publishTo(patient, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:2", patient, null);

        // Envelope and order: newest first
        JsonNode all = list(patient, "");
        assertEquals(0, all.path("page").asInt());
        assertEquals(20, all.path("size").asInt());
        assertEquals(3, all.path("totalElements").asInt());
        assertEquals(1, all.path("totalPages").asInt());
        assertEquals(third, all.path("content").get(0).path("notificationId").asInt());
        assertEquals(second, all.path("content").get(1).path("notificationId").asInt());
        assertEquals(first, all.path("content").get(2).path("notificationId").asInt());

        // Paging
        JsonNode pageOne = list(patient, "?page=0&size=2");
        assertEquals(2, pageOne.path("content").size());
        assertEquals(2, pageOne.path("size").asInt());
        assertEquals(2, pageOne.path("totalPages").asInt());
        JsonNode pageTwo = list(patient, "?page=1&size=2");
        assertEquals(1, pageTwo.path("content").size());
        assertEquals(first, pageTwo.path("content").get(0).path("notificationId").asInt());
        assertEquals(1, pageTwo.path("page").asInt());

        // Clamps: size 1..50, negative page = 0
        assertEquals(50, list(patient, "?size=500").path("size").asInt());
        assertEquals(1, list(patient, "?size=0").path("size").asInt());
        assertEquals(1, list(patient, "?size=0").path("content").size());
        assertEquals(0, list(patient, "?page=-4").path("page").asInt());

        // Type filter: trimmed, any case; empty = no filter
        JsonNode appointments = list(patient, "?type=appointment");
        assertEquals(2, appointments.path("totalElements").asInt());
        appointments.path("content").forEach(n -> assertEquals("APPOINTMENT", n.path("type").asText()));
        assertEquals(1, list(patient, "?type=PAYMENT").path("totalElements").asInt());
        assertEquals("BILLING", list(patient, "?type=PAYMENT").path("content").get(0).path("category").asText());
        assertEquals(0, list(patient, "?type=REFERRAL").path("totalElements").asInt());
        assertEquals(3, list(patient, "?type=").path("totalElements").asInt());

        // unreadOnly combines with the type filter
        assertEquals(204, markRead(third, patient));
        assertEquals(1, list(patient, "?type=APPOINTMENT&unreadOnly=true").path("totalElements").asInt());
        assertEquals(2, list(patient, "?unreadOnly=true").path("totalElements").asInt());
        assertEquals(3, list(patient, "?unreadOnly=false").path("totalElements").asInt());

        // 400 envelope
        MvcResult unknownType = doGet(NOTIFICATIONS + "?type=INVOICE", patient);
        assertEquals(400, unknownType.getResponse().getStatus());
        JsonNode error = json(unknownType);
        assertEquals("Unknown notification type", error.path("message").asText());
        assertEquals(400, error.path("status").asInt());
        assertEquals("/api/notifications", error.path("path").asText());
        assertTrue(error.path("code").isMissingNode(), "no code on this error: " + error);
        MvcResult badPage = doGet(NOTIFICATIONS + "?page=abc", patient);
        assertEquals(400, badPage.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'page'", message(badPage));
    }

    // ==================== CAREGIVER PERMISSIONS ====================

    @Test
    void caregiverIsNotifiedOnlyWithThePermission_andNeverAfterTheGrantEnded() throws Exception {
        var grant = grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));      // no lab-results flag

        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS, labResult(88).build());

        assertEquals(1, list(patient, "").path("totalElements").asInt());
        assertEquals(1, list(caregiver, "").path("totalElements").asInt(), "caregiver with the flag");
        assertEquals(0, list(otherCaregiver, "").path("totalElements").asInt(), "caregiver without the flag");
        assertEquals(0, list(doctor, "").path("totalElements").asInt(), "the actor");

        // The grant expires: nothing new is created for the caregiver, what was delivered stays visible
        grant.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        dependentAccessRepository.saveAndFlush(grant);
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS, labResult(89).build());

        assertEquals(2, list(patient, "").path("totalElements").asInt());
        JsonNode stillThere = list(caregiver, "");
        assertEquals(1, stillThere.path("totalElements").asInt());
        assertEquals(88, stillThere.path("content").get(0).path("resourceId").asInt());
        assertEquals(1, counts(caregiver).path("unreadCount").asInt());

        // A caregiver who switched the category off gets nothing, the patient still does
        grantAccess(otherCaregiver, patient, a -> a.setCanViewLabResults(true));
        UserSettings off = new UserSettings();
        off.setSystemId(otherCaregiver.getSystemId());
        off.setLabResultAlerts(false);
        userSettingsRepository.saveAndFlush(off);
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS, labResult(90).build());

        assertEquals(3, list(patient, "").path("totalElements").asInt());
        assertEquals(0, list(otherCaregiver, "").path("totalElements").asInt());

        // Unless the notification is critical
        publisher.publishToPatientCircle(patient.getSystemId(), PatientPermission.LAB_RESULTS,
                labResult(91).priority(NotificationPriority.CRITICAL).build());
        JsonNode critical = list(otherCaregiver, "");
        assertEquals(1, critical.path("totalElements").asInt());
        assertEquals("CRITICAL", critical.path("content").get(0).path("priority").asText());
    }

    // ==================== AUDIT ====================

    @Test
    void listPageAndMarkReadAreAudited_theUnreadCountPollIsNot() throws Exception {
        int aboutThomas = publishTo(caregiver, NotificationType.LAB_RESULT, NotificationCategory.LAB_RESULT, "LAB_RESULT:1", patient, null);
        publishTo(caregiver, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:1", patient, null);
        publishTo(caregiver, NotificationType.APPOINTMENT, NotificationCategory.APPOINTMENT_CHANGE, "APPT_BOOKED:2", otherPatient, null);
        int account = systemNotification(caregiver, "PASSWORD_CHANGED:1");
        jdbc.update("DELETE FROM audit_log");

        // The poll is never audited
        counts(caregiver);
        counts(caregiver);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'NOTIFICATION'", Integer.class));

        // A page: one READ row per patient referenced on it
        assertEquals(4, list(caregiver, "").path("totalElements").asInt());
        assertEquals(2, auditRows(caregiver, "READ"));
        assertEquals(List.of("PAT-00001", "PAT-00002"), jdbc.queryForList(
                "SELECT patient_system_id FROM audit_log WHERE resource_type = 'NOTIFICATION' AND action = 'READ' "
                        + "AND system_id = ? AND status = 'SUCCESS' AND resource_id IS NULL ORDER BY patient_system_id",
                String.class, caregiver.getSystemId()));
        assertEquals("CAREGIVER", jdbc.queryForObject("SELECT DISTINCT actor_role FROM audit_log "
                + "WHERE resource_type = 'NOTIFICATION' AND action = 'READ'", String.class));

        // A page without patient references writes nothing; neither does an empty page
        assertEquals(1, list(caregiver, "?type=SYSTEM").path("totalElements").asInt());
        assertEquals(0, list(otherCaregiver, "").path("totalElements").asInt());
        assertEquals(2, auditRows(caregiver, "READ"));
        assertEquals(0, auditRows(otherCaregiver, "READ"));

        // Mark read: one UPDATE row on the unread-to-read transition, none on the repeat
        assertEquals(204, markRead(aboutThomas, caregiver));
        assertEquals(204, markRead(aboutThomas, caregiver));
        assertEquals(1, auditRows(caregiver, "UPDATE"));
        Map<String, Object> update = jdbc.queryForMap("SELECT resource_id, patient_system_id, status FROM audit_log "
                + "WHERE resource_type = 'NOTIFICATION' AND action = 'UPDATE' AND system_id = ?", caregiver.getSystemId());
        assertEquals(aboutThomas, update.get("resource_id"));
        assertEquals("PAT-00001", update.get("patient_system_id"));
        assertEquals("SUCCESS", update.get("status"));

        // An account-level notification is audited without a patient
        assertEquals(204, markRead(account, caregiver));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'NOTIFICATION' "
                + "AND action = 'UPDATE' AND resource_id = ? AND patient_system_id IS NULL", Integer.class, account));

        // A failed mark (someone else's row) writes no UPDATE row
        assertEquals(404, markRead(aboutThomas, otherCaregiver));
        assertEquals(0, auditRows(otherCaregiver, "UPDATE"));

        // read-all: one UPDATE row without resource id when something changed, none when nothing did
        assertEquals(204, doPost(NOTIFICATIONS + "/read-all", caregiver, null).getResponse().getStatus());
        assertEquals(3, auditRows(caregiver, "UPDATE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'NOTIFICATION' "
                + "AND action = 'UPDATE' AND system_id = ? AND resource_id IS NULL AND patient_system_id IS NULL",
                Integer.class, caregiver.getSystemId()));
        assertEquals(204, doPost(NOTIFICATIONS + "/read-all", caregiver, null).getResponse().getStatus());
        assertEquals(3, auditRows(caregiver, "UPDATE"));
        assertEquals(0, counts(caregiver).path("unreadCount").asInt());
    }

    // ==================== MESSAGING ====================

    @Test
    void sendingAMessage_marksTheSendersOwnNotificationsOfThatThreadRead() throws Exception {
        int threadId = createCareTeamThread();
        assertEquals(201, send(threadId, doctor, clientId(), "Please measure twice a day.").getResponse().getStatus());
        assertEquals(201, send(threadId, doctor, clientId(), "And write the values down.").getResponse().getStatus());
        int other = systemNotification(patient, "WELCOME:1");

        JsonNode before = counts(patient);
        assertEquals(3, before.path("unreadCount").asInt());
        assertEquals(2, before.path("unreadMessageCount").asInt());

        // The patient answers without ever posting /read
        String replyId = clientId();
        assertEquals(201, send(threadId, patient, replyId, "Will do.").getResponse().getStatus());

        JsonNode after = counts(patient);
        assertEquals(0, after.path("unreadMessageCount").asInt(), "the badge drops by sending");
        assertEquals(1, after.path("unreadCount").asInt(), "other notifications are untouched");
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_read FROM notifications WHERE notification_id = ?",
                Boolean.class, other));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND thread_id = ? "
                + "AND is_read = TRUE AND read_at IS NOT NULL", Integer.class, patient.getSystemId(), threadId));
        // The recipient of the reply has one unread message notification; the sender got none for their own message
        assertEquals(1, counts(doctor).path("unreadMessageCount").asInt());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND type = 'MESSAGE'",
                Integer.class, patient.getSystemId()));

        // A replay of the same clientMessageId (200) marks nothing
        assertEquals(201, send(threadId, doctor, clientId(), "Thank you.").getResponse().getStatus());
        assertEquals(1, counts(patient).path("unreadMessageCount").asInt());
        assertEquals(200, send(threadId, patient, replyId, "Will do.").getResponse().getStatus());
        assertEquals(1, counts(patient).path("unreadMessageCount").asInt());

        // The statuses of the message endpoints are unchanged; body fields are checked by Bean Validation
        // (field: message texts, be-communications-hardening 0.4)
        MvcResult empty = send(threadId, patient, clientId(), "   ");
        assertEquals(400, empty.getResponse().getStatus());
        assertEquals("body: must not be empty", message(empty));
        MvcResult badId = send(threadId, patient, "short", "hello");
        assertEquals(400, badId.getResponse().getStatus());
        assertEquals("clientMessageId: must be 8-64 letters, digits, '-' or '_'", message(badId));
        MvcResult both = doGet(THREADS + "/" + threadId + "/messages?before=5&after=2", patient);
        assertEquals(400, both.getResponse().getStatus());
        assertEquals("Use either before or after, not both", message(both));
        MvcResult noPosition = doPost(THREADS + "/" + threadId + "/read", patient, Map.of());
        assertEquals(400, noPosition.getResponse().getStatus());
        assertEquals("lastReadMessageId: is required", message(noPosition));
    }

    @Test
    void messageNotificationsFollowThePreference_andUrgentIsHighPriority() throws Exception {
        UserSettings off = new UserSettings();
        off.setSystemId(patient.getSystemId());
        off.setMessageNotifications(false);
        userSettingsRepository.saveAndFlush(off);
        grantApprovedAccess(caregiver, patient);
        int threadId = createCareTeamThread();
        assertEquals(200, doPost(THREADS + "/" + threadId + "/members", patient,
                Map.of("systemIds", List.of(caregiver.getSystemId()))).getResponse().getStatus());

        assertEquals(201, doPost(THREADS + "/" + threadId + "/messages", doctor,
                Map.of("clientMessageId", clientId(), "body", "Please call the clinic today.", "urgent", true))
                .getResponse().getStatus());

        assertEquals(0, list(patient, "").path("totalElements").asInt(), "message notifications switched off");
        JsonNode urgent = list(caregiver, "").path("content").get(0);
        assertEquals("HIGH", urgent.path("priority").asText());
        assertEquals("Urgent message", urgent.path("title").asText());
        assertEquals("MESSAGE", urgent.path("category").asText());
        assertEquals(1, counts(caregiver).path("unreadMessageCount").asInt());
    }

    // ==================== V24 ====================

    @Test
    void v24_userSettingsColumns_defaultsAndRoundTrip_andTheDedupeIndexIsPartial() {
        jdbc.update("INSERT INTO user_settings (system_id) VALUES (?)", patient.getSystemId());
        UserSettings defaults = userSettingsRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertEquals(Boolean.FALSE, defaults.getQuietHoursEnabled());
        assertNull(defaults.getQuietHoursStart());
        assertNull(defaults.getQuietHoursEnd());
        assertEquals(Boolean.TRUE, defaults.getBillingNotifications());

        UserSettings fresh = new UserSettings();
        assertEquals(Boolean.FALSE, fresh.getQuietHoursEnabled());
        assertEquals(Boolean.TRUE, fresh.getBillingNotifications());

        defaults.setQuietHoursEnabled(true);
        defaults.setQuietHoursStart(LocalTime.of(22, 0));
        defaults.setQuietHoursEnd(LocalTime.of(7, 30));
        defaults.setBillingNotifications(false);
        userSettingsRepository.saveAndFlush(defaults);

        UserSettings stored = userSettingsRepository.findBySystemId(patient.getSystemId()).orElseThrow();
        assertEquals(Boolean.TRUE, stored.getQuietHoursEnabled());
        assertEquals(LocalTime.of(22, 0), stored.getQuietHoursStart());
        assertEquals(LocalTime.of(7, 30), stored.getQuietHoursEnd());
        assertEquals(Boolean.FALSE, stored.getBillingNotifications());

        String index = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE tablename = 'notifications' "
                + "AND indexname = 'ux_notifications_recipient_dedupe'", String.class);
        assertNotNull(index);
        assertTrue(index.contains("UNIQUE") && index.contains("system_id") && index.contains("dedupe_key")
                && index.contains("WHERE (dedupe_key IS NOT NULL)"), index);
    }
}
