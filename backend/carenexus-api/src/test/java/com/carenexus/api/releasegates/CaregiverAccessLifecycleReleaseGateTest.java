package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.communications.job.CaregiverAccessExpirySweeper;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.service.CaregiverService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * Release gates for the caregiver grant lifecycle in messaging (expiry, extension, re-grant), retry-safe creation
 * under concurrency, notification copy and preferences, approval requests, and patient-facing consultation lists.
 * Runs with committed transactions (CommittedIntegrationTest) so every listener runs as in production.
 */
class CaregiverAccessLifecycleReleaseGateTest extends CommittedIntegrationTest {

    private static final String THREADS = "/api/communications/threads";
    private static final String ACCESS = "/api/patients/me/caregiver-access/";

    @Autowired
    private CaregiverAccessExpirySweeper expirySweeper;

    @Autowired
    private CaregiverService caregiverService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    private int careTeamWithCaregiver() throws Exception {
        MvcResult created = doPost(THREADS, doctor, Map.of(
                "patientSystemId", patient.getSystemId(), "type", "CARE_TEAM", "subject", "e2e-fix lifecycle",
                "memberSystemIds", List.of(), "clientThreadId", clientId()));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        int threadId = json(created).path("threadId").asInt();
        addCaregiver(threadId);
        return threadId;
    }

    private void addCaregiver(int threadId) throws Exception {
        MvcResult added = doPost(THREADS + "/" + threadId + "/members", patient,
                Map.of("systemIds", List.of(caregiver.getSystemId())));
        assertEquals(200, added.getResponse().getStatus(), added.getResponse().getContentAsString());
    }

    private MvcResult send(int threadId, User as, String clientMessageId, String body) throws Exception {
        return doPost(THREADS + "/" + threadId + "/messages", as,
                Map.of("clientMessageId", clientMessageId, "body", body, "urgent", false));
    }

    private List<String> bodiesSeenBy(int threadId, User as) throws Exception {
        MvcResult result = doGet(THREADS + "/" + threadId + "/messages", as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        List<String> bodies = new ArrayList<>();
        json(result).path("messages").forEach(m -> bodies.add(m.path("body").asText()));
        return bodies;
    }

    private String removalReason(int threadId, User member) {
        return jdbc.queryForObject("SELECT removal_reason FROM thread_members WHERE thread_id = ? AND system_id = ?",
                String.class, threadId, member.getSystemId());
    }

    private void lapse(DependentAccess access) {
        DependentAccess stored = dependentAccessRepository.findById(access.getAccessId()).orElseThrow();
        stored.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        dependentAccessRepository.saveAndFlush(stored);
    }

    private void extend(DependentAccess access) throws Exception {
        MvcResult extended = doPatch(ACCESS + access.getAccessId(), patient,
                Map.of("expiresAt", LocalDate.now().plusDays(30).toString()));
        assertEquals(200, extended.getResponse().getStatus(), extended.getResponse().getContentAsString());
        assertTrue(json(extended).path("messagingAllowed").asBoolean(), "messaging effective again after extension");
    }

    // ==================== EXPIRY / EXTENSION / RE-GRANT ====================

    @Test
    void expiredGrant_sweeperEndsMemberships_andExtensionStartsANewHistoryBoundary() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int threadId = careTeamWithCaregiver();
        assertEquals(201, send(threadId, doctor, clientId(), "Before the lapse").getResponse().getStatus());
        assertEquals(List.of("Before the lapse"), bodiesSeenBy(threadId, caregiver));

        lapse(access);
        assertEquals(201, send(threadId, doctor, clientId(), "Sent during the lapse").getResponse().getStatus());

        assertEquals(1, expirySweeper.sweepOnce(LocalDateTime.now()));
        assertEquals("ACCESS_EXPIRED", removalReason(threadId, caregiver));
        assertNotNull(jdbc.queryForObject("SELECT expiry_processed_at FROM dependent_access WHERE access_id = ?",
                LocalDateTime.class, access.getAccessId()));
        assertEquals(0, expirySweeper.sweepOnce(LocalDateTime.now()), "a processed lapse is not processed twice");

        extend(access);

        // Extension does not restore the old membership
        assertEquals(403, doGet(THREADS + "/" + threadId, caregiver).getResponse().getStatus());
        assertEquals(0, json(doGet(THREADS, caregiver)).path("totalElements").asInt());

        // Re-added: history starts now. Nothing from before or during the lapse, no unread, no notifications.
        addCaregiver(threadId);
        assertEquals(List.of(), bodiesSeenBy(threadId, caregiver));
        assertEquals(0, json(doGet(THREADS + "/" + threadId, caregiver)).path("unreadCount").asInt());
        assertEquals(0, json(doGet("/api/notifications/unread-count", caregiver)).path("unreadMessageCount").asInt());

        assertEquals(201, send(threadId, doctor, clientId(), "After access returned").getResponse().getStatus());
        assertEquals(List.of("After access returned"), bodiesSeenBy(threadId, caregiver));
    }

    @Test
    void extensionBeforeTheSweeperRan_stillStartsANewHistoryBoundary() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int threadId = careTeamWithCaregiver();
        assertEquals(201, send(threadId, doctor, clientId(), "Before the lapse").getResponse().getStatus());

        lapse(access);
        assertEquals(201, send(threadId, doctor, clientId(), "Sent during the lapse").getResponse().getStatus());
        assertNull(removalReason(threadId, caregiver), "no sweep yet: the membership row is still active");

        extend(access);

        // The grant change itself ended the surviving membership (synchronous, same transaction)
        assertEquals("ACCESS_EXPIRED", removalReason(threadId, caregiver));
        assertEquals(403, doGet(THREADS + "/" + threadId + "/messages", caregiver).getResponse().getStatus());
        addCaregiver(threadId);
        assertEquals(List.of(), bodiesSeenBy(threadId, caregiver));
    }

    @Test
    void reGrantAfterARevocationWhoseCleanupNeverRan_enforcesAFreshBoundary() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int threadId = careTeamWithCaregiver();
        assertEquals(201, send(threadId, doctor, clientId(), "Before revocation").getResponse().getStatus());

        // Revoked without the AFTER_COMMIT clean-up (as if the listener had failed): the membership survives
        jdbc.update("UPDATE dependent_access SET is_active = false, revoked_at = now(), messaging_approved_at = NULL,"
                + " messaging_approved_by_system_id = NULL, calls_approved_at = NULL, calls_approved_by_system_id = NULL"
                + " WHERE access_id = ?", access.getAccessId());
        assertNull(removalReason(threadId, caregiver));
        assertEquals(201, send(threadId, doctor, clientId(), "While revoked").getResponse().getStatus());

        MvcResult regrant = doPost("/api/patients/me/caregiver-access", patient, Map.of(
                "caregiverEmail", caregiver.getEmail(), "relationship", "daughter", "accessLevel", "CUSTOM",
                "canMessageCareTeam", true));
        assertEquals(201, regrant.getResponse().getStatus(), regrant.getResponse().getContentAsString());
        assertTrue(json(regrant).path("messagingAllowed").asBoolean());

        assertEquals("ACCESS_REVOKED", removalReason(threadId, caregiver));
        assertEquals(403, doGet(THREADS + "/" + threadId, caregiver).getResponse().getStatus());
        addCaregiver(threadId);
        assertEquals(List.of(), bodiesSeenBy(threadId, caregiver));
    }

    // ==================== CONCURRENT RETRIES ====================

    /** Runs the same request from {@code n} threads released together; returns the HTTP results. */
    private List<MvcResult> concurrently(int n, Callable<MvcResult> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentCreateWithTheSameClientThreadId_returnsOneThread() throws Exception {
        String clientThreadId = clientId();
        Map<String, Object> body = Map.of("patientSystemId", patient.getSystemId(), "type", "CARE_TEAM",
                "subject", "e2e-fix concurrent", "memberSystemIds", List.of(), "clientThreadId", clientThreadId);

        List<MvcResult> results = concurrently(6, () -> doPost(THREADS, doctor, body));

        Set<Integer> threadIds = new HashSet<>();
        int created = 0;
        for (MvcResult r : results) {
            int status = r.getResponse().getStatus();
            assertTrue(status == 200 || status == 201, "status " + status + ": " + r.getResponse().getContentAsString());
            created += status == 201 ? 1 : 0;
            threadIds.add(json(r).path("threadId").asInt());
        }
        assertEquals(1, created, "exactly one request created the thread");
        assertEquals(1, threadIds.size(), "every retry got the same thread");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM message_threads WHERE client_thread_id = ?",
                Integer.class, clientThreadId));
    }

    @Test
    void concurrentDuplicateSends_storeOneMessageAndOneNotificationPerRecipient() throws Exception {
        MvcResult thread = doPost(THREADS, doctor, Map.of("patientSystemId", patient.getSystemId(), "type", "CARE_TEAM",
                "memberSystemIds", List.of(), "clientThreadId", clientId()));
        int threadId = json(thread).path("threadId").asInt();
        String clientMessageId = clientId();

        List<MvcResult> results = concurrently(6, () -> send(threadId, patient, clientMessageId, "Can I take it with food?"));

        Set<Integer> messageIds = new HashSet<>();
        int created = 0;
        for (MvcResult r : results) {
            int status = r.getResponse().getStatus();
            assertTrue(status == 200 || status == 201, "status " + status + ": " + r.getResponse().getContentAsString());
            created += status == 201 ? 1 : 0;
            messageIds.add(json(r).path("messageId").asInt());
        }
        assertEquals(1, created);
        assertEquals(1, messageIds.size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM messages WHERE thread_id = ? AND client_message_id = ?",
                Integer.class, threadId, clientMessageId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ? AND thread_id = ?",
                Integer.class, doctor.getSystemId(), threadId));
    }

    // ==================== NOTIFICATIONS ====================

    @Test
    void patientsOwnMessage_isAnnouncedAsSentToYou_andMessageNotificationsCanBeTurnedOff() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int threadId = careTeamWithCaregiver();

        // The caregiver turns in-app message notifications off
        MvcResult settings = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/profile/settings/notifications"), caregiver, Map.of("messageNotifications", false));
        assertEquals(200, settings.getResponse().getStatus(), settings.getResponse().getContentAsString());

        assertEquals(201, send(threadId, patient, clientId(), "My readings this morning").getResponse().getStatus());

        JsonNode toDoctor = json(doGet("/api/notifications", doctor)).path("content");
        assertEquals(1, toDoctor.size());
        String text = toDoctor.get(0).path("message").asText();
        assertEquals("PATIENT User1 sent you a message", text);
        assertFalse(text.contains("about"), text);

        // No notification row for the caregiver, but the inbox still counts the message as unread
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE system_id = ?",
                Integer.class, caregiver.getSystemId()));
        assertEquals(1, json(doGet(THREADS + "/" + threadId, caregiver)).path("unreadCount").asInt());
    }

    @Test
    void pendingCaregiverApproval_notifiesThePatient_andApprovalClearsIt() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        // Admin changes never approve: calls now wait for the patient
        caregiverService.updateCaregiverAccessAsPatient(admin.getSystemId(), true, access.getAccessId(),
                UpdateDependentAccessRequest.builder().canJoinCalls(false).build());
        caregiverService.updateCaregiverAccessAsPatient(admin.getSystemId(), true, access.getAccessId(),
                UpdateDependentAccessRequest.builder().canJoinCalls(true).build());

        JsonNode list = json(doGet("/api/notifications", patient)).path("content");
        assertEquals(1, list.size(), list.toString());
        JsonNode n = list.get(0);
        assertEquals("SYSTEM", n.path("type").asText());
        assertEquals("CAREGIVER_ACCESS", n.path("target").asText());
        assertEquals("/dashboard/patient?section=my-proxies", n.path("actionUrl").asText());
        assertEquals("CAREGIVER User3 is waiting for your approval to join your video calls", n.path("message").asText());
        assertEquals(1, json(doGet("/api/notifications/unread-count", patient)).path("unreadCount").asInt());

        MvcResult approve = doPatch(ACCESS + access.getAccessId(), patient, Map.of("canJoinCalls", true));
        assertEquals(200, approve.getResponse().getStatus(), approve.getResponse().getContentAsString());
        assertEquals(0, json(doGet("/api/notifications/unread-count", patient)).path("unreadCount").asInt());
    }

    // ==================== RECIPIENTS ====================

    @Test
    void recipients_reportWhoMayAddToAnExistingCareTeam() throws Exception {
        grantApprovedAccess(caregiver, patient);
        String url = "/api/communications/recipients?patientSystemId=" + patient.getSystemId();

        for (JsonNode r : json(doGet(url, caregiver)).path("recipients")) {
            assertTrue(r.path("canAddToCareTeam").asBoolean(), "caregivers can include people when starting one");
            assertFalse(r.path("canAddToExistingCareTeam").asBoolean(), "caregivers cannot add members later: " + r);
        }
        for (JsonNode r : json(doGet(url, doctor)).path("recipients")) {
            assertTrue(r.path("canAddToExistingCareTeam").asBoolean(), r.toString());
        }
    }

    // ==================== CONSULTATIONS ====================

    @Test
    void patientsNeverSeeDraftConsultations_andResponsesCarryTheAppointmentType() throws Exception {
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(patient.getSystemId()).doctorSystemId(doctor.getSystemId())
                .scheduledAt(LocalDateTime.now().minusDays(2)).type("PHONE").status("COMPLETED").build());
        Consultation draft = consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).chiefComplaint("Working notes").status("DRAFT").build());
        Consultation inProgress = consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).chiefComplaint("Notes being written").status("IN_PROGRESS").build());
        consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).chiefComplaint("Final notes").status("COMPLETED").build());

        JsonNode mine = json(doGet("/api/consultations/patient/" + patient.getSystemId(), patient));
        assertEquals(1, mine.size(), mine.toString());
        assertEquals("COMPLETED", mine.get(0).path("status").asText());
        assertEquals("PHONE", mine.get(0).path("appointmentType").asText());
        assertEquals(404, doGet("/api/consultations/" + draft.getConsultationId(), patient).getResponse().getStatus());
        assertEquals(1, json(doGet("/api/consultations/appointment/" + appointment.getAppointmentId(), patient)).size());
        assertEquals(0, json(doGet("/api/consultations/me/draft", patient)).size());
        assertEquals(404, doGet("/api/consultations/" + inProgress.getConsultationId(), patient).getResponse().getStatus());
        assertEquals(0, json(doGet("/api/consultations/me/in-progress", patient)).size());

        JsonNode doctorView = json(doGet("/api/consultations/doctor/" + doctor.getSystemId(), doctor));
        assertEquals(3, doctorView.size(), "the doctor still sees the draft and the one in progress");
        doctorView.forEach(c -> assertEquals("PHONE", c.path("appointmentType").asText()));
        assertEquals(200, doGet("/api/consultations/" + draft.getConsultationId(), doctor).getResponse().getStatus());
    }
}
