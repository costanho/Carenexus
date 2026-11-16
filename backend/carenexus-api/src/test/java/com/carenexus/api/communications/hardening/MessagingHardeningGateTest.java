package com.carenexus.api.communications.hardening;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Messaging and calls hardening (be-communications-hardening Part 1): Bean Validation envelopes on every request
 * DTO (validated before any service code), the two rate-limit buckets, the recipients cap and the list audits.
 */
class MessagingHardeningGateTest extends CommittedIntegrationTest {

    private static final String THREADS = "/api/communications/threads";

    @Autowired private RateLimitProperties rateLimitProperties;
    @Autowired private RateLimitGuard rateLimitGuard;

    private int createCareTeamThread() throws Exception {
        MvcResult created = doPost(THREADS, doctor, Map.of(
                "patientSystemId", patient.getSystemId(), "type", "CARE_TEAM", "clientThreadId", clientId()));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("threadId").asInt();
    }

    private void assertValidation(MvcResult result, String field, String text) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText(), body.toString());
        assertEquals(field + ": " + text, body.path("message").asText());
        assertEquals(field, body.path("errors").get(0).path("field").asText());
        assertEquals(text, body.path("errors").get(0).path("message").asText());
    }

    private void assertMalformed(MvcResult result) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("Malformed request body", message(result));
    }

    // ==================== 400 ENVELOPES ====================

    @Test
    void createThreadRequest_isValidatedBeforeTheService() throws Exception {
        assertValidation(doPost(THREADS, patient, Map.of()), "type", "is required");
        assertValidation(doPost(THREADS, patient, Map.of("type", "PRIVATE", "subject", "s".repeat(201))),
                "subject", "must be at most 200 characters");
        assertValidation(doPost(THREADS, patient, Map.of("type", "PRIVATE", "clientThreadId", "short")),
                "clientThreadId", "must be 8-64 letters, digits, '-' or '_'");
        assertValidation(doPost(THREADS, patient, Map.of("type", "PRIVATE", "patientSystemId", "")),
                "patientSystemId", "must be a valid system id");
        assertValidation(doPost(THREADS, doctor, Map.of("type", "CARE_TEAM", "patientSystemId", patient.getSystemId(),
                "memberSystemIds", List.of("DOC-00001", "nope"))), "memberSystemIds[1]", "must be a valid system id");
        List<String> tooMany = IntStream.range(0, 51).mapToObj(i -> String.format("DOC-%05d", 100 + i)).toList();
        assertValidation(doPost(THREADS, doctor, Map.of("type", "CARE_TEAM", "patientSystemId", patient.getSystemId(),
                "memberSystemIds", tooMany)), "memberSystemIds", "must contain at most 50 entries");
        assertMalformed(call(post(THREADS).contentType(MediaType.APPLICATION_JSON).content("{not json"), patient, null));

        // Service rules keep their statuses and texts (now typed exceptions)
        MvcResult support = doPost(THREADS, patient, Map.of("type", "SUPPORT"));
        assertEquals(400, support.getResponse().getStatus());
        assertEquals("Conversation type must be PRIVATE or CARE_TEAM", message(support));
        MvcResult noPatient = doPost(THREADS, doctor, Map.of("type", "CARE_TEAM"));
        assertEquals(400, noPatient.getResponse().getStatus());
        assertEquals("Patient ID is required", message(noPatient));

        // An administrator with an invalid body gets 400 (validation first), with a valid body 403
        assertValidation(doPost(THREADS, admin, Map.of()), "type", "is required");
        MvcResult adminCreate = doPost(THREADS, admin, Map.of("type", "CARE_TEAM", "patientSystemId", patient.getSystemId()));
        assertEquals(403, adminCreate.getResponse().getStatus());
        assertEquals("Administrators cannot access conversations", message(adminCreate));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM message_threads", Integer.class));
    }

    @Test
    void threadRouteBodies_areValidated_evenForAnUnknownThread() throws Exception {
        String unknown = THREADS + "/999999";
        assertValidation(doPost(unknown + "/members", patient, Map.of()), "systemIds", "choose at least one person to add");
        assertValidation(doPost(unknown + "/members", patient, Map.of("systemIds", List.of())),
                "systemIds", "choose at least one person to add");
        assertValidation(doPost(unknown + "/members", patient, Map.of("systemIds", List.of("bad"))),
                "systemIds[0]", "must be a valid system id");
        assertValidation(doPut(unknown + "/archive", patient, Map.of()), "archived", "must be true or false");
        assertValidation(doPost(unknown + "/messages", patient, Map.of("body", "hi")), "clientMessageId", "is required");
        assertValidation(doPost(unknown + "/messages", patient, Map.of("clientMessageId", "x", "body", "hi")),
                "clientMessageId", "must be 8-64 letters, digits, '-' or '_'");
        assertValidation(doPost(unknown + "/messages", patient, Map.of("clientMessageId", clientId(), "body", "   ")),
                "body", "must not be empty");
        assertValidation(doPost(unknown + "/messages", patient, Map.of("clientMessageId", clientId(), "body", "b".repeat(4001))),
                "body", "must be at most 4000 characters");
        assertValidation(doPost(unknown + "/read", patient, Map.of()), "lastReadMessageId", "is required");
        assertValidation(doPost(unknown + "/read", patient, Map.of("lastReadMessageId", -1)),
                "lastReadMessageId", "must be 0 or greater");
        assertValidation(doPost("/api/communications/support-thread", caregiver, Map.of("subject", "s".repeat(201))),
                "subject", "must be at most 200 characters");

        // A valid body reaches the service: unknown thread is 404 again
        assertEquals(404, doPost(unknown + "/read", patient, Map.of("lastReadMessageId", 0)).getResponse().getStatus());
        // An administrator sending an empty message is refused by validation before the access rules
        int threadId = createCareTeamThread();
        assertValidation(doPost(THREADS + "/" + threadId + "/messages", admin, Map.of("clientMessageId", clientId(), "body", "")),
                "body", "must not be empty");
    }

    @Test
    void callRequestBodies_areValidated() throws Exception {
        String call = "/api/appointments/999999/call";
        assertValidation(doPost(call + "/confirm-time", doctor, Map.of()), "scheduledAt", "is required");
        assertMalformed(doPost(call + "/confirm-time", doctor, null));
        assertMalformed(doPost(call + "/confirm-time", doctor, Map.of("scheduledAt", "tomorrow")));
        assertMalformed(doPost(call + "/join-request", patient, Map.of("actingForPatient", "maybe")));
        // A valid or absent body reaches CallService (unknown appointment)
        assertEquals(404, doPost(call + "/confirm-time", doctor, Map.of("scheduledAt", "2026-11-20T10:30:00"))
                .getResponse().getStatus());
        assertEquals(404, doPost(call + "/join-request", patient, null).getResponse().getStatus());
    }

    // ==================== 429 ====================

    @Test
    void threadCreateBucket_isPerCreator_andAnswers429WithRetryAfter() throws Exception {
        withBucket("thread-create", 2, () -> {
            Map<String, Object> body = Map.of("patientSystemId", patient.getSystemId(), "type", "CARE_TEAM");
            assertEquals(201, doPost(THREADS, doctor, body).getResponse().getStatus());
            // A find-or-create that returns an existing thread still counts
            MvcResult second = doPost(THREADS, doctor, Map.of("patientSystemId", patient.getSystemId(), "type", "PRIVATE",
                    "memberSystemIds", List.of(patient.getSystemId())));
            assertEquals(201, second.getResponse().getStatus());
            MvcResult limited = doPost(THREADS, doctor, body);
            assertRateLimited(limited);
            // Another creator has their own bucket
            assertEquals(201, doPost(THREADS, patient, Map.of("type", "CARE_TEAM",
                    "memberSystemIds", List.of(doctor.getSystemId()))).getResponse().getStatus());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM message_threads WHERE created_by_system_id = ?",
                    Integer.class, doctor.getSystemId()));
        }, doctor, patient);
    }

    @Test
    void messageSendBucket_isPerSender_andAnswers429WithRetryAfter() throws Exception {
        int threadId = createCareTeamThread();
        String url = THREADS + "/" + threadId + "/messages";
        withBucket("message-send", 2, () -> {
            String first = clientId();
            assertEquals(201, doPost(url, patient, Map.of("clientMessageId", first, "body", "one")).getResponse().getStatus());
            // A retry of a stored message still consumes a unit
            assertEquals(200, doPost(url, patient, Map.of("clientMessageId", first, "body", "one")).getResponse().getStatus());
            assertRateLimited(doPost(url, patient, Map.of("clientMessageId", clientId(), "body", "three")));
            assertEquals(201, doPost(url, doctor, Map.of("clientMessageId", clientId(), "body", "doctor"))
                    .getResponse().getStatus());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM messages WHERE sender_system_id = ?",
                    Integer.class, patient.getSystemId()));
        }, patient, doctor);
    }

    private void assertRateLimited(MvcResult result) throws Exception {
        assertEquals(429, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("RATE_LIMITED", json(result).path("code").asText());
        assertTrue(message(result).startsWith("Too many requests. Please try again in "), message(result));
        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertNotNull(retryAfter);
        assertTrue(Long.parseLong(retryAfter) > 0);
    }

    private interface Body {
        void run() throws Exception;
    }

    private void withBucket(String bucket, int limit, Body body, User... keys) throws Exception {
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(limit);
        small.setWindowSeconds(3600L);
        RateLimitProperties.Bucket previous = rateLimitProperties.getBuckets().put(bucket, small);
        for (User key : keys) {
            rateLimitGuard.reset(bucket, key.getSystemId());
        }
        rateLimitProperties.setEnabled(true);
        try {
            body.run();
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previous != null) {
                rateLimitProperties.getBuckets().put(bucket, previous);
            } else {
                rateLimitProperties.getBuckets().remove(bucket);
            }
            for (User key : keys) {
                rateLimitGuard.reset(bucket, key.getSystemId());
            }
        }
    }

    // ==================== CAP AND AUDIT ====================

    @Test
    void recipients_areCappedAt200_andAudited() throws Exception {
        for (int i = 0; i < 205; i++) {
            User extra = createAndSaveUser(100 + i, String.format("DOC-%05d", 100 + i), "doc" + i + "@example.com", "DOCTOR");
            careTeamRepository.saveAndFlush(CareTeam.builder()
                    .patientSystemId(patient.getSystemId())
                    .providerSystemId(extra.getSystemId())
                    .role("PRIMARY_DOCTOR")
                    .assignedAt(LocalDateTime.now())
                    .isActive(true)
                    .build());
        }

        MvcResult result = doGet("/api/communications/recipients", patient);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode recipients = json(result).path("recipients");
        assertEquals(200, recipients.size());
        Map<String, Integer> roles = new HashMap<>();
        recipients.forEach(r -> roles.merge(r.path("role").asText(), 1, Integer::sum));
        assertEquals(Map.of("DOCTOR", 200), roles);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = "
                + "'MESSAGE_RECIPIENT' AND patient_system_id = ? AND resource_id IS NULL", Integer.class, patient.getSystemId()));

        MvcResult adminRecipients = doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), admin);
        assertEquals(403, adminRecipients.getResponse().getStatus());
        assertEquals("Administrators cannot access conversations", message(adminRecipients));
    }

    @Test
    void threadList_isAuditedOncePerPatientOnThePage() throws Exception {
        createCareTeamThread();
        assertEquals(201, doPost(THREADS, doctor, Map.of("patientSystemId", patient.getSystemId(), "type", "PRIVATE",
                "memberSystemIds", List.of(patient.getSystemId()))).getResponse().getStatus());

        MvcResult list = doGet(THREADS + "?size=500&page=-1", doctor);
        assertEquals(200, list.getResponse().getStatus());
        JsonNode page = json(list);
        assertEquals(2, page.path("totalElements").asInt());
        assertEquals(50, page.path("size").asInt());
        assertEquals(0, page.path("page").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = "
                + "'MESSAGE_THREAD' AND resource_id IS NULL AND patient_system_id = ? AND system_id = ?",
                Integer.class, patient.getSystemId(), doctor.getSystemId()));

        // An empty page writes nothing
        doGet(THREADS, otherDoctor);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_type = 'MESSAGE_THREAD' "
                + "AND system_id = ?", Integer.class, otherDoctor.getSystemId()));

        MvcResult adminList = doGet(THREADS, admin);
        assertEquals(403, adminList.getResponse().getStatus());
        assertEquals("Administrators cannot access conversations", message(adminList));
    }
}
