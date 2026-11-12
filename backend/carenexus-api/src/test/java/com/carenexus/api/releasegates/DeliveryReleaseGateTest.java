package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.service.DevOutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for the delivery endpoints (api/be-foundation-delivery-storage): push devices are own-only, the
 * channels endpoint describes the installation to every signed-in user, and the development outbox is ADMIN-only,
 * paged, filtered and absent outside developer profiles. None of the endpoints writes an audit row.
 */
class DeliveryReleaseGateTest extends CommittedIntegrationTest {

    private static final String DEVICES = "/api/notifications/devices";
    private static final String CHANNELS = "/api/notifications/channels";
    private static final String OUTBOX = "/api/admin/dev/outbound-messages";
    private static final String EXPO_TOKEN = "ExponentPushToken[AbC-123_xyz:789]";

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private OutboundMessageWorker worker;

    @Autowired
    private OutboundMessageRepository messages;

    @Autowired
    private AuthorizationService authorizationService;

    private static Map<String, Object> device(String platform, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", platform);
        body.put("token", token);
        return body;
    }

    private MvcResult deleteDevice(String token, User as) throws Exception {
        // The URI template encodes the token as a client does with encodeURIComponent ([ and ] become %5B and %5D).
        return call(delete(DEVICES + "/{token}", token), as, null);
    }

    private String ownerOf(String token) {
        List<String> owners = jdbc.queryForList("SELECT system_id FROM push_devices WHERE token = ?", String.class, token);
        return owners.isEmpty() ? null : owners.get(0);
    }

    private int deviceCount(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM push_devices WHERE system_id = ?", Integer.class,
                user.getSystemId());
    }

    private int auditRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
    }

    private void assertValidationError(MvcResult result, String expectedMessage, String expectedField) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals(expectedMessage, body.path("message").asText());
        assertEquals("VALIDATION_FAILED", body.path("code").asText());
        assertEquals(expectedField, body.path("errors").get(0).path("field").asText());
        assertEquals(400, body.path("status").asInt());
        assertTrue(body.path("timestamp").asText().endsWith("Z"));
    }

    // ==================== PUSH DEVICES ====================

    @Test
    void registeringADevice_isAnUpsertByToken_201Then200() throws Exception {
        int auditBefore = auditRows();

        MvcResult created = doPost(DEVICES, patient, device("android", EXPO_TOKEN));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        assertNull(created.getResponse().getHeader("Location"));
        JsonNode first = json(created);
        assertTrue(first.path("deviceId").isInt());
        assertEquals("ANDROID", first.path("platform").asText(), "stored upper-case");
        Instant firstSeen = Instant.parse(first.path("lastSeenAt").asText());
        assertFalse(first.has("token"), "the token is not echoed");
        assertEquals(3, first.size(), "deviceId, platform, lastSeenAt");
        assertEquals(patient.getSystemId(), ownerOf(EXPO_TOKEN));

        // Same token again, other platform spelling: refreshed, same row.
        MvcResult refreshed = doPost(DEVICES, patient, device(" iOS ", EXPO_TOKEN));
        assertEquals(200, refreshed.getResponse().getStatus(), refreshed.getResponse().getContentAsString());
        JsonNode second = json(refreshed);
        assertEquals(first.path("deviceId").asInt(), second.path("deviceId").asInt());
        assertEquals("IOS", second.path("platform").asText());
        assertFalse(Instant.parse(second.path("lastSeenAt").asText()).isBefore(firstSeen));
        assertEquals(1, deviceCount(patient));
        assertEquals("IOS", jdbc.queryForObject("SELECT platform FROM push_devices WHERE token = ?", String.class, EXPO_TOKEN));

        assertEquals(auditBefore, auditRows(), "device registration is not audited");
    }

    @Test
    void everyRoleManagesItsOwnDevices_andNobodyElses() throws Exception {
        List<User> everyone = List.of(patient, doctor, caregiver, admin, otherPatient, otherDoctor, otherCaregiver);
        for (User user : everyone) {
            String token = "device-token-of-" + user.getSystemId();
            MvcResult created = doPost(DEVICES, user, device("WEB", token));
            assertEquals(201, created.getResponse().getStatus(), user.getSystemId());
            assertEquals(user.getSystemId(), ownerOf(token));
        }

        // Nobody can remove a device that is not theirs: 404, never 403, and the row stays.
        String patientsToken = "device-token-of-" + patient.getSystemId();
        for (User outsider : List.of(otherPatient, doctor, otherDoctor, caregiver, otherCaregiver, admin)) {
            MvcResult denied = deleteDevice(patientsToken, outsider);
            assertEquals(404, denied.getResponse().getStatus(), outsider.getSystemId());
            assertEquals("Device not found", message(denied));
        }
        assertEquals(patient.getSystemId(), ownerOf(patientsToken));

        // A caregiver with a full grant over the patient is still not the device owner.
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(404, deleteDevice(patientsToken, caregiver).getResponse().getStatus());
        assertEquals(patient.getSystemId(), ownerOf(patientsToken));

        // The owner can.
        MvcResult removed = deleteDevice(patientsToken, patient);
        assertEquals(204, removed.getResponse().getStatus());
        assertEquals("", removed.getResponse().getContentAsString());
        assertNull(ownerOf(patientsToken), "the row is hard-deleted");
        for (User other : everyone.subList(1, everyone.size())) {
            assertEquals(1, deviceCount(other), "other users' devices are untouched: " + other.getSystemId());
        }
    }

    @Test
    void removingADevice_204Then404_alsoForATokenWithBrackets() throws Exception {
        assertEquals(201, doPost(DEVICES, doctor, device("IOS", EXPO_TOKEN)).getResponse().getStatus());

        assertEquals(204, deleteDevice(EXPO_TOKEN, doctor).getResponse().getStatus());
        assertNull(ownerOf(EXPO_TOKEN));

        MvcResult again = deleteDevice(EXPO_TOKEN, doctor);
        assertEquals(404, again.getResponse().getStatus(), "repeating a successful delete answers 404");
        assertEquals("Device not found", message(again));
        assertFalse(json(again).has("code"));

        // A value that cannot be a token is simply not found.
        assertEquals(404, deleteDevice("no", doctor).getResponse().getStatus());
        assertEquals(404, deleteDevice("not a token!", doctor).getResponse().getStatus());
    }

    @Test
    void aDeviceFollowsTheAccountSignedInOnIt() throws Exception {
        assertEquals(201, doPost(DEVICES, patient, device("ANDROID", "shared-family-tablet-token")).getResponse().getStatus());
        int deviceId = jdbc.queryForObject("SELECT device_id FROM push_devices WHERE token = ?", Integer.class,
                "shared-family-tablet-token");

        MvcResult moved = doPost(DEVICES, caregiver, device("ANDROID", "shared-family-tablet-token"));
        assertEquals(200, moved.getResponse().getStatus(), "re-assignment answers 200");
        assertEquals(deviceId, json(moved).path("deviceId").asInt(), "the same row moves to the new account");
        assertEquals(caregiver.getSystemId(), ownerOf("shared-family-tablet-token"));
        assertEquals(0, deviceCount(patient));

        // The previous owner no longer has it.
        assertEquals(404, deleteDevice("shared-family-tablet-token", patient).getResponse().getStatus());
        assertEquals(204, deleteDevice("shared-family-tablet-token", caregiver).getResponse().getStatus());
    }

    @Test
    void aUserKeepsTheTenMostRecentlySeenDevices() throws Exception {
        for (int i = 1; i <= 11; i++) {
            assertEquals(201, doPost(DEVICES, patient, device("ANDROID", "phone-token-" + i)).getResponse().getStatus());
        }
        assertEquals(10, deviceCount(patient));
        assertNull(ownerOf("phone-token-1"), "the least recently seen device was dropped");
        assertEquals(patient.getSystemId(), ownerOf("phone-token-2"));
        assertEquals(patient.getSystemId(), ownerOf("phone-token-11"));

        // Refreshing an old device keeps it; the next new one pushes out the now-oldest.
        assertEquals(200, doPost(DEVICES, patient, device("ANDROID", "phone-token-2")).getResponse().getStatus());
        assertEquals(201, doPost(DEVICES, patient, device("ANDROID", "phone-token-12")).getResponse().getStatus());
        assertEquals(10, deviceCount(patient));
        assertEquals(patient.getSystemId(), ownerOf("phone-token-2"));
        assertNull(ownerOf("phone-token-3"));

        // Another user's devices never count against the cap.
        assertEquals(201, doPost(DEVICES, otherPatient, device("IOS", "other-phone-token")).getResponse().getStatus());
        assertEquals(10, deviceCount(patient));
    }

    @Test
    void theIdempotencyKeyHeaderIsAcceptedAndIgnored() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", clientId());

        assertEquals(201, doPostWithHeaders(DEVICES, patient, device("WEB", "web-push-token-0001"), headers)
                .getResponse().getStatus());
        assertEquals(200, doPostWithHeaders(DEVICES, patient, device("WEB", "web-push-token-0001"), headers)
                .getResponse().getStatus(), "a repeat simply answers 200");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class),
                "nothing is stored in idempotency_keys");
    }

    @Test
    void invalidDeviceRequests_answerTheValidationEnvelope() throws Exception {
        assertValidationError(doPost(DEVICES, patient, device(null, EXPO_TOKEN)), "platform: must not be null", "platform");
        assertValidationError(doPost(DEVICES, patient, device("WINDOWS", EXPO_TOKEN)),
                "platform: must be IOS, ANDROID or WEB", "platform");
        assertValidationError(doPost(DEVICES, patient, device("", EXPO_TOKEN)),
                "platform: must be IOS, ANDROID or WEB", "platform");
        assertValidationError(doPost(DEVICES, patient, device("IOS", null)), "token: must not be null", "token");
        for (String bad : List.of("short", "has space in it", " padded-token-value ", "semi;colon;token", "t".repeat(513))) {
            assertValidationError(doPost(DEVICES, patient, device("IOS", bad)),
                    "token: must be 8 to 512 letters, digits or _ : - [ ]", "token");
        }

        // Both invalid: the message carries the platform error and errors lists both fields.
        MvcResult both = doPost(DEVICES, patient, device("WINDOWS", "x"));
        assertValidationError(both, "platform: must be IOS, ANDROID or WEB", "platform");
        JsonNode errors = json(both).path("errors");
        assertEquals(2, errors.size());
        assertEquals("token", errors.get(1).path("field").asText());
        assertEquals("must be 8 to 512 letters, digits or _ : - [ ]", errors.get(1).path("message").asText());

        // Boundaries that are valid: 8 and 512 characters; unknown properties are ignored.
        assertEquals(201, doPost(DEVICES, patient, device("IOS", "12345678")).getResponse().getStatus());
        assertEquals(201, doPost(DEVICES, patient, device("IOS", "t".repeat(512))).getResponse().getStatus());
        Map<String, Object> extra = device("WEB", "token-with-extra-field");
        extra.put("systemId", otherPatient.getSystemId());
        assertEquals(201, doPost(DEVICES, patient, extra).getResponse().getStatus());
        assertEquals(patient.getSystemId(), ownerOf("token-with-extra-field"), "no body field names a user");

        // Malformed body and wrong content type.
        MvcResult malformed = mockMvc.perform(post(DEVICES).header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn();
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals("Malformed request body", message(malformed));
        MvcResult missing = mockMvc.perform(post(DEVICES).header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertEquals(400, missing.getResponse().getStatus());
        assertEquals("Malformed request body", message(missing));
        MvcResult text = mockMvc.perform(post(DEVICES).header("Authorization", tokenFor(patient))
                .contentType(MediaType.TEXT_PLAIN).content("platform=IOS")).andReturn();
        assertEquals(415, text.getResponse().getStatus());
        assertEquals("Unsupported content type", message(text));

        assertEquals(3, deviceCount(patient), "no invalid request stored anything");
    }

    @Test
    void deviceAndChannelEndpoints_needASignedInCaller() throws Exception {
        MvcResult register = mockMvc.perform(post(DEVICES).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(device("IOS", EXPO_TOKEN)))).andReturn();
        assertEquals(401, register.getResponse().getStatus());
        assertEquals("Authentication required", message(register));
        assertEquals(401, mockMvc.perform(delete(DEVICES + "/{token}", EXPO_TOKEN)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(CHANNELS)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(OUTBOX)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get(OUTBOX).header("Authorization", "Bearer not-a-token")).andReturn()
                .getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM push_devices", Integer.class));
    }

    // ==================== CHANNELS ====================

    @Test
    void channels_describeTheInstallationToEverySignedInUser() throws Exception {
        int auditBefore = auditRows();
        String expected = null;
        for (User user : List.of(patient, doctor, caregiver, admin, otherPatient)) {
            MvcResult result = doGet(CHANNELS, user);
            assertEquals(200, result.getResponse().getStatus(), user.getSystemId());
            JsonNode body = json(result);
            assertEquals(3, body.size(), "email, sms and push; the in-app channel is not listed");
            for (String channel : List.of("email", "sms", "push")) {
                JsonNode status = body.path(channel);
                assertEquals(2, status.size());
                assertTrue(status.path("available").isBoolean());
                assertTrue(status.path("available").asBoolean(), channel + " is available with the local provider");
                assertEquals("local", status.path("provider").asText());
            }
            if (expected == null) {
                expected = body.toString();
            }
            assertEquals(expected, body.toString(), "the answer is the same for every caller");
        }
        assertEquals(auditBefore, auditRows(), "reading the channels is not audited");
    }

    // ==================== DEVELOPMENT OUTBOX ====================

    /** Four rows, oldest first: email to Thomas, SMS to Thomas, push to Thomas, email to an address without account. */
    private List<Integer> seedOutbox() {
        List<Integer> ids = new ArrayList<>();
        ids.add(outbound.queueEmail("Thomas@Example.com", patient.getSystemId(), "delivery-test",
                Map.of("name", "Thomas", "link", "http://localhost:8081/auth/reset-password?token=Zq3vJ0aP7c1mT5uK"),
                "PASSWORD_RESET:17"));
        ids.add(outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test",
                Map.of("name", "Thomas"), null));
        ids.add(outbound.queue(new com.carenexus.api.delivery.OutboundRequest("PUSH", null, patient.getSystemId(),
                null, null, "Appointment confirmed", "Your appointment was booked. Open CareNexus for the details.",
                Map.of("actionUrl", "/dashboard/patient?section=my-appointments&id=412"), "N:88:PUSH", 88, null)));
        ids.add(outbound.queueEmail("e2e_ann%1@carenexus.test", null, "delivery-test", Map.of("name", "Ann"), null));
        return ids;
    }

    private List<Integer> idsOf(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("messageId").asInt()));
        return ids;
    }

    private JsonNode outbox(String query) throws Exception {
        MvcResult result = doGet(OUTBOX + query, admin);
        assertEquals(200, result.getResponse().getStatus(), query + " -> " + result.getResponse().getContentAsString());
        return json(result);
    }

    @Test
    void devOutbox_isAdminOnly() throws Exception {
        seedOutbox();
        for (User user : List.of(patient, doctor, caregiver, otherPatient, otherDoctor, otherCaregiver)) {
            MvcResult denied = doGet(OUTBOX, user);
            assertEquals(403, denied.getResponse().getStatus(), user.getSystemId());
            assertEquals("You don't have permission to access this resource", message(denied));
            assertFalse(denied.getResponse().getContentAsString().contains("reset-password"));
        }
        // A caregiver's grant over the patient does not open the outbox either.
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(403, doGet(OUTBOX + "?to=" + patient.getSystemId(), caregiver).getResponse().getStatus());

        assertEquals(200, doGet(OUTBOX, admin).getResponse().getStatus());
    }

    @Test
    void devOutbox_listsNewestFirst_withTheContractFields() throws Exception {
        List<Integer> ids = seedOutbox();
        int auditBefore = auditRows();

        JsonNode page = outbox("");
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt(), "default size");
        assertEquals(4, page.path("totalElements").asInt());
        assertEquals(1, page.path("totalPages").asInt());
        assertEquals(List.of(ids.get(3), ids.get(2), ids.get(1), ids.get(0)), idsOf(page), "newest first");

        JsonNode push = page.path("content").get(1);
        assertEquals(19, push.size(), "every field is present; nulls are sent as null");
        assertEquals("PUSH", push.path("channel").asText());
        assertEquals(patient.getSystemId(), push.path("recipientSystemId").asText());
        assertTrue(push.path("recipientAddress").isNull());
        assertTrue(push.path("templateId").isNull());
        assertEquals("Appointment confirmed", push.path("subject").asText());
        assertEquals("Your appointment was booked. Open CareNexus for the details.", push.path("body").asText());
        assertEquals("/dashboard/patient?section=my-appointments&id=412", push.path("data").path("actionUrl").asText());
        assertEquals(88, push.path("notificationId").asInt());
        assertEquals("N:88:PUSH", push.path("dedupeKey").asText());
        assertEquals("PENDING", push.path("status").asText());
        assertEquals(0, push.path("attempts").asInt());
        assertEquals(5, push.path("maxAttempts").asInt());
        assertTrue(push.has("lastError") && push.path("lastError").isNull());
        assertTrue(push.has("provider") && push.path("provider").isNull());
        assertTrue(push.has("providerMessageId") && push.path("providerMessageId").isNull());
        assertTrue(push.has("sentAt") && push.path("sentAt").isNull());
        Instant created = Instant.parse(push.path("createdAt").asText());
        assertEquals(created, Instant.parse(push.path("nextAttemptAt").asText()));

        JsonNode sms = page.path("content").get(2);
        assertEquals("SMS", sms.path("channel").asText());
        assertEquals("+263771234567", sms.path("recipientAddress").asText());
        assertEquals("delivery-test", sms.path("templateId").asText());
        assertTrue(sms.path("subject").isNull(), "an SMS has no subject");
        assertTrue(sms.path("data").isNull());
        assertTrue(sms.path("notificationId").isNull());
        assertTrue(sms.path("dedupeKey").isNull());

        JsonNode email = page.path("content").get(3);
        assertEquals("EMAIL", email.path("channel").asText());
        assertEquals("Thomas@Example.com", email.path("recipientAddress").asText(), "exactly as queued");
        assertEquals("CareNexus delivery test for Thomas", email.path("subject").asText());
        assertEquals("PASSWORD_RESET:17", email.path("dedupeKey").asText());
        // The body is final while the row is still PENDING: a reader takes the link without waiting for SENT.
        assertEquals("PENDING", email.path("status").asText());
        java.util.regex.Matcher link = java.util.regex.Pattern.compile("https?://\\S+").matcher(email.path("body").asText());
        assertTrue(link.find());
        assertEquals("http://localhost:8081/auth/reset-password?token=Zq3vJ0aP7c1mT5uK", link.group());

        JsonNode noAccount = page.path("content").get(0);
        assertTrue(noAccount.path("recipientSystemId").isNull(), "an address without an account");

        // After the worker ran: SENT by the local provider (the push has no device, so it is skipped).
        assertEquals(4, worker.sweepOnce());
        JsonNode sent = outbox("?template=delivery-test&to=thomas@example.com&channel=EMAIL").path("content").get(0);
        assertEquals("SENT", sent.path("status").asText());
        assertEquals(1, sent.path("attempts").asInt());
        assertEquals("local", sent.path("provider").asText());
        assertEquals("local-" + ids.get(0), sent.path("providerMessageId").asText());
        assertFalse(Instant.parse(sent.path("sentAt").asText()).isBefore(created.minusSeconds(60)));
        assertEquals(email.path("body").asText(), sent.path("body").asText());
        JsonNode skipped = outbox("?channel=PUSH").path("content").get(0);
        assertEquals("SKIPPED", skipped.path("status").asText());
        assertEquals("No registered device", skipped.path("lastError").asText());
        assertEquals(0, skipped.path("attempts").asInt());
        assertFalse(skipped.path("sentAt").isNull(), "sentAt is stamped for SKIPPED");

        assertEquals(auditBefore, auditRows(), "reading the outbox is not audited");
        assertEquals(4, messages.count(), "the outbox cannot be changed over HTTP");
    }

    @Test
    void devOutbox_filtersCombineWithAnd() throws Exception {
        List<Integer> ids = seedOutbox();
        Integer email = ids.get(0), sms = ids.get(1), push = ids.get(2), noAccount = ids.get(3);

        // channel: exact, case-insensitive, trimmed; blank means no filter.
        assertEquals(List.of(noAccount, email), idsOf(outbox("?channel=email")));
        assertEquals(List.of(sms), idsOf(outbox("?channel= Sms ")));
        assertEquals(List.of(push), idsOf(outbox("?channel=PUSH")));
        assertEquals(4, outbox("?channel=&to=&template=").path("totalElements").asInt());

        // to: case-insensitive contains on the address or the system id.
        assertEquals(List.of(email), idsOf(outbox("?to=THOMAS@example.COM")));
        assertEquals(List.of(email), idsOf(outbox("?to=example.com")));
        assertEquals(List.of(push, sms, email), idsOf(outbox("?to=" + patient.getSystemId())));
        assertEquals(List.of(push, sms, email), idsOf(outbox("?to=pat-0000")));
        assertEquals(List.of(sms), idsOf(outbox("?to=+26377")));
        assertEquals(List.of(noAccount), idsOf(outbox("?to=carenexus.test")));
        assertEquals(List.of(), idsOf(outbox("?to=nobody@example.com")));
        // % and _ are matched literally, not as wildcards (MockMvc percent-encodes the raw query text).
        assertEquals(List.of(noAccount), idsOf(outbox("?to=ann%1")));
        assertEquals(List.of(noAccount), idsOf(outbox("?to=e2e_ann")));
        assertEquals(List.of(noAccount), idsOf(outbox("?to=%")));
        assertEquals(List.of(), idsOf(outbox("?to=thomas_example")));
        assertEquals(List.of(), idsOf(outbox("?to=t%m")));

        // template: case-insensitive exact; PUSH rows have none and never match.
        assertEquals(List.of(noAccount, sms, email), idsOf(outbox("?template=Delivery-Test")));
        assertEquals(List.of(), idsOf(outbox("?template=delivery")));
        assertEquals(List.of(), idsOf(outbox("?template=delivery-test&channel=PUSH")));

        // AND.
        assertEquals(List.of(sms), idsOf(outbox("?channel=SMS&to=" + patient.getSystemId() + "&template=delivery-test")));
        assertEquals(List.of(), idsOf(outbox("?channel=SMS&to=carenexus.test")));

        // The reader's query: newest row for an address and template.
        JsonNode newest = outbox("?to=thomas@example.com&template=delivery-test&size=1");
        assertEquals(List.of(email), idsOf(newest));
        assertEquals(1, newest.path("size").asInt());
    }

    @Test
    void devOutbox_isPaged_andClampsPageAndSize() throws Exception {
        List<Integer> ids = seedOutbox();

        JsonNode first = outbox("?page=0&size=3");
        assertEquals(List.of(ids.get(3), ids.get(2), ids.get(1)), idsOf(first));
        assertEquals(3, first.path("size").asInt());
        assertEquals(4, first.path("totalElements").asInt());
        assertEquals(2, first.path("totalPages").asInt());

        JsonNode second = outbox("?page=1&size=3");
        assertEquals(List.of(ids.get(0)), idsOf(second));
        assertEquals(1, second.path("page").asInt());

        JsonNode beyond = outbox("?page=5&size=3");
        assertEquals(0, beyond.path("content").size());
        assertEquals(4, beyond.path("totalElements").asInt());

        assertEquals(100, outbox("?size=500").path("size").asInt(), "size is clamped to 100");
        assertEquals(1, outbox("?size=0").path("size").asInt(), "size is at least 1");
        assertEquals(1, outbox("?size=-7").path("content").size());
        assertEquals(0, outbox("?page=-3").path("page").asInt(), "a negative page becomes 0");

        // Availability probe of the web admin screen.
        JsonNode probe = outbox("?page=0&size=1");
        assertEquals(1, probe.path("content").size());
        assertEquals(4, probe.path("totalPages").asInt());
    }

    @Test
    void devOutbox_invalidParameters_answerTheErrorEnvelope() throws Exception {
        assertValidationError(doGet(OUTBOX + "?channel=FAX", admin), "channel: must be EMAIL, SMS or PUSH", "channel");
        assertValidationError(doGet(OUTBOX + "?to=" + "a".repeat(255), admin),
                "to: size must be between 0 and 254", "to");
        assertValidationError(doGet(OUTBOX + "?template=" + "t".repeat(81), admin),
                "template: size must be between 0 and 80", "template");
        assertEquals(200, doGet(OUTBOX + "?to=" + "a".repeat(254) + "&template=" + "t".repeat(80), admin)
                .getResponse().getStatus());

        MvcResult page = doGet(OUTBOX + "?page=abc", admin);
        assertEquals(400, page.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'page'", message(page));
        assertFalse(json(page).has("code"));
        MvcResult size = doGet(OUTBOX + "?size=ten", admin);
        assertEquals(400, size.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'size'", message(size));

        // The role gate comes first: a non-admin never learns whether a parameter was valid.
        assertEquals(403, doGet(OUTBOX + "?channel=FAX", doctor).getResponse().getStatus());
    }

    @Test
    void devOutbox_doesNotExistOutsideDeveloperProfiles_unlessAStagingInstallationKeepsEmailLocal() {
        seedOutbox();
        // A production installation never has it, even with every provider local.
        DevOutboxService production = outboxAsBuiltFor("production", DeliveryProperties.LOCAL);
        // A staging installation whose email really leaves the machine (here: disabled) does not have it either.
        DevOutboxService stagingWithoutLocalEmail = outboxAsBuiltFor("staging", DeliveryProperties.DISABLED);

        authenticateAs(admin);
        for (DevOutboxService hidden : List.of(production, stagingWithoutLocalEmail)) {
            assertFalse(hidden.isAvailable());
            ResourceNotFoundException notFound = assertThrows(ResourceNotFoundException.class,
                    () -> hidden.search(null, null, null, 0, 1));
            assertEquals("Resource not found", notFound.getMessage(),
                    "indistinguishable from a route that does not exist");
        }

        // The role is checked before the installation: a non-admin gets 403 on every installation.
        for (User user : List.of(patient, doctor, caregiver)) {
            authenticateAs(user);
            assertThrows(AuthorizationException.class, () -> production.search(null, null, null, 0, 1),
                    user.getSystemId());
        }
        clearAuthentication();
    }

    @Test
    void devOutbox_isReachableByAnAdminOnStagingWhileEmailIsLocal() {
        seedOutbox();
        // Staging without a developer profile and the default local email provider (README staging column):
        // verification and reset links never leave the database, so an administrator must be able to read them.
        for (String environment : List.of("staging", "development")) {
            DevOutboxService staging = outboxAsBuiltFor(environment, " LOCAL ");
            assertTrue(staging.isAvailable(), environment);

            authenticateAs(admin);
            assertTrue(staging.search(null, null, null, 0, 1).getTotalElements() > 0, environment);

            for (User user : List.of(patient, doctor, caregiver)) {
                authenticateAs(user);
                assertThrows(AuthorizationException.class, () -> staging.search(null, null, null, 0, 1),
                        user.getSystemId());
            }
        }
        clearAuthentication();
    }

    /** The service as an installation without a developer profile builds it. */
    private DevOutboxService outboxAsBuiltFor(String environment, String emailProvider) {
        MockEnvironment notADevInstallation = new MockEnvironment();
        notADevInstallation.setActiveProfiles(environment);
        DeliveryProperties delivery = new DeliveryProperties();
        delivery.getEmail().setProvider(emailProvider);
        return new DevOutboxService(messages, authorizationService,
                new EnvironmentInfo(notADevInstallation, environment), delivery, objectMapper);
    }
}
