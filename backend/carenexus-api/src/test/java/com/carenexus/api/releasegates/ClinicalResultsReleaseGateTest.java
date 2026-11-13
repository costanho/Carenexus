package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.core.results.ClinicalResultFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.results.ClinicalResultFixtures.imagingBody;
import static com.carenexus.api.core.results.ClinicalResultFixtures.labBody;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gates for lab and imaging results (contract be-lab-imaging): who may create, edit, change status, review,
 * cancel and read; caregiver flags, revocation and expiry; the 400 envelope; criticality normalisation; the review
 * stamp and the pending-review queues; paging and its clamp; documents by id; legacy arrays; idempotent create; the
 * audit rows; and exactly one ClinicalResultRecordedEvent per successful write with newlyCritical only on the
 * transition. Runs with committed transactions.
 */
@RecordApplicationEvents
class ClinicalResultsReleaseGateTest extends CommittedIntegrationTest {

    private static final String LAB = "/api/lab-results";
    private static final String IMAGING = "/api/imaging-results";
    private static final String NOT_ROLE = "Your role is not authorized to perform this action";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String NOT_AUTHOR = "Only the doctor who created this item can change it";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";

    @Autowired
    private ApplicationEvents applicationEvents;

    private ClinicalResultFixtures fixtures;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ClinicalResultFixtures(jdbc);
    }

    // ==================== HELPERS ====================

    private JsonNode created(MvcResult result) throws Exception {
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int createLab(Map<String, Object> body) throws Exception {
        return created(doPost(LAB, doctor, body)).path("resultId").asInt();
    }

    private int createImaging(Map<String, Object> body) throws Exception {
        return created(doPost(IMAGING, doctor, body)).path("imagingId").asInt();
    }

    private Map<String, Object> labWith(String key, Object value) {
        Map<String, Object> body = labBody(patient.getSystemId());
        body.put(key, value);
        return body;
    }

    private Map<String, Object> imagingWith(String key, Object value) {
        Map<String, Object> body = imagingBody(patient.getSystemId());
        body.put(key, value);
        return body;
    }

    private List<ClinicalResultRecordedEvent> events() {
        return applicationEvents.stream(ClinicalResultRecordedEvent.class).toList();
    }

    private ClinicalResultRecordedEvent onlyEvent() {
        List<ClinicalResultRecordedEvent> events = events();
        assertEquals(1, events.size(), "exactly one event: " + events);
        return events.get(0);
    }

    private void assertStatus(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        if (message != null) {
            assertEquals(message, message(result));
        }
    }

    private void assertValidation(MvcResult result, String message, boolean withErrors) throws Exception {
        assertStatus(result, 400, message);
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText(), body.toString());
        assertEquals(withErrors, body.has("errors") && body.path("errors").isArray() && body.path("errors").size() > 0,
                body.toString());
    }

    private MvcResult rawPost(String url, User as, String rawBody) throws Exception {
        var builder = post(url).header("Authorization", tokenFor(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody == null ? "" : rawBody);
        return mockMvc.perform(builder).andReturn();
    }

    private static LocalDate clinicToday() {
        return LocalDate.now(ZoneId.of("Africa/Harare"));
    }

    // ==================== CREATE ====================

    @Test
    void treatingDoctorCreatesLabResultAsAuthorWithNormalisedCriticalityAndOneEvent() throws Exception {
        Map<String, Object> body = labWith("status", "completed");
        body.put("isCritical", true);
        body.put("doctorSystemId", otherDoctor.getSystemId());
        body.put("reviewedBy", otherDoctor.getSystemId());
        body.put("fileUrl", "https://example.com/forged.pdf");
        body.put("testName", "  Full Blood Count (FBC)  ");
        body.put("unit", "   ");

        JsonNode result = created(doPost(LAB, doctor, body));

        assertEquals(doctor.getSystemId(), result.path("doctorSystemId").asText());
        assertEquals("Dr. DOCTOR User2", result.path("doctorName").asText());
        assertEquals("PATIENT User1", result.path("patientName").asText());
        assertEquals("Full Blood Count (FBC)", result.path("testName").asText());
        assertEquals("BLOOD", result.path("testType").asText());
        assertEquals("CRITICAL", result.path("status").asText());
        assertTrue(result.path("isCritical").asBoolean());
        assertEquals(clinicToday().toString(), result.path("testDate").asText());
        for (String nullField : List.of("reviewedBy", "reviewedByName", "reviewedAt", "reviewNotes", "documentId",
                "fileUrl", "unit", "consultationId")) {
            assertTrue(result.has(nullField), "field present: " + nullField);
            assertTrue(result.path(nullField).isNull(), "field null: " + nullField);
        }
        assertEquals(10.8, objectMapper.readTree(result.path("resultData").asText())
                .path("haemoglobin").path("value").asDouble());
        int id = result.path("resultId").asInt();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lab_results WHERE file_url IS NULL AND result_id = ?",
                Integer.class, id));

        ClinicalResultRecordedEvent event = onlyEvent();
        assertEquals(new ClinicalResultRecordedEvent("LAB", id, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), "CRITICAL", true, true, true), event);
        assertEquals(1, fixtures.auditRows("CREATE", "LAB_RESULT", patient.getSystemId()));
    }

    @Test
    void treatingDoctorCreatesImagingResult() throws Exception {
        Map<String, Object> body = imagingWith("status", "REQUIRES_REVIEW");
        body.put("thumbnailUrl", "https://example.com/forged.png");
        body.put("imageDate", clinicToday().minusDays(2).toString());

        JsonNode result = created(doPost(IMAGING, doctor, body));

        assertEquals("REQUIRES_REVIEW", result.path("status").asText());
        assertFalse(result.path("isCritical").asBoolean());
        assertTrue(result.path("thumbnailUrl").isNull());
        assertTrue(result.path("fileUrl").isNull());
        assertEquals("Dr. DOCTOR User2", result.path("doctorName").asText());
        assertEquals(clinicToday().minusDays(2).toString(), result.path("imageDate").asText());

        ClinicalResultRecordedEvent event = onlyEvent();
        assertEquals("IMAGING", event.kind());
        assertEquals(result.path("imagingId").asInt(), event.resultId());
        assertFalse(event.critical());
        assertFalse(event.newlyCritical());
        assertTrue(event.created());
        assertEquals(1, fixtures.auditRows("CREATE", "IMAGING_RESULT", patient.getSystemId()));
    }

    @Test
    void onlyATreatingDoctorMayCreate() throws Exception {
        assertStatus(doPost(LAB, otherDoctor, labBody(patient.getSystemId())), 403, NOT_CARE_TEAM);
        assertStatus(doPost(IMAGING, otherDoctor, imagingBody(patient.getSystemId())), 403, NOT_CARE_TEAM);
        for (User user : List.of(patient, caregiver, admin)) {
            assertStatus(doPost(LAB, user, labBody(patient.getSystemId())), 403, NOT_ROLE);
            assertStatus(doPost(IMAGING, user, imagingBody(patient.getSystemId())), 403, NOT_ROLE);
        }
        assertStatus(doPost(LAB, doctor, labBody("PAT-09999")), 404, "Patient not found");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM lab_results", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM imaging_results", Integer.class));
        assertTrue(events().isEmpty());
    }

    @Test
    void invalidCreateBodiesAnswer400WithTheEnvelope() throws Exception {
        assertValidation(doPost(LAB, doctor, labWith("testType", "SALIVA")),
                "testType: must be one of BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, OTHER", true);
        assertValidation(doPost(LAB, doctor, labWith("testName", "  ")), "testName: is required", true);
        assertValidation(doPost(LAB, doctor, labWith("testName", "x".repeat(201))),
                "testName: must be at most 200 characters", true);
        assertValidation(doPost(LAB, doctor, labWith("patientSystemId", null)), "patientSystemId: is required", true);
        assertValidation(doPost(LAB, doctor, labWith("patientSystemId", "thomas")),
                "patientSystemId: must be a valid system id", true);
        assertValidation(doPost(LAB, doctor, labWith("status", "CANCELLED")),
                "status: must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL", true);
        assertValidation(doPost(LAB, doctor, labWith("testDate", clinicToday().plusDays(2).toString())),
                "testDate: must not be in the future", true);
        assertValidation(doPost(LAB, doctor, labWith("documentId", 0)), "documentId: must be greater than 0", true);
        assertValidation(doPost(LAB, doctor, labWith("resultData", "[1, 2]")), "resultData: must be a JSON object", false);
        assertValidation(doPost(LAB, doctor, labWith("resultData", "{\"a\": ")), "resultData: must be a JSON object", false);
        assertValidation(doPost(LAB, doctor, labWith("resultData", "{\"a\": 1} trailing")),
                "resultData: must be a JSON object", false);
        Map<String, Object> contradiction = labWith("status", "CRITICAL");
        contradiction.put("isCritical", false);
        assertValidation(doPost(LAB, doctor, contradiction), "isCritical: cannot be false when status is CRITICAL", false);

        assertStatus(doPost(LAB, doctor, labWith("resultData", Map.of("a", 1))), 400, "Malformed request body");
        assertStatus(doPost(LAB, doctor, labWith("testDate", "yesterday")), 400, "Malformed request body");
        assertStatus(rawPost(LAB, doctor, "{not json"), 400, "Malformed request body");

        assertValidation(doPost(IMAGING, doctor, imagingWith("imagingType", "SCAN")),
                "imagingType: must be one of XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, ECHO", true);
        assertValidation(doPost(IMAGING, doctor, imagingWith("findings", "x".repeat(5001))),
                "findings: must be at most 5000 characters", true);
        assertValidation(doPost(IMAGING, doctor, imagingWith("imageDate", clinicToday().plusDays(2).toString())),
                "imageDate: must not be in the future", true);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM lab_results", Integer.class));
        assertTrue(events().isEmpty());
    }

    @Test
    void idempotencyKeyReplaysTheFirstCreate() throws Exception {
        String key = "lab-" + clientId();
        MvcResult first = doPostWithHeaders(LAB, doctor, labBody(patient.getSystemId()), Map.of("Idempotency-Key", key));
        MvcResult second = doPostWithHeaders(LAB, doctor, labBody(patient.getSystemId()), Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(200, second.getResponse().getStatus());
        assertEquals(json(first).path("resultId"), json(second).path("resultId"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM lab_results", Integer.class));
        assertEquals(1, events().size());

        String imagingKey = "img-" + clientId();
        MvcResult a = doPostWithHeaders(IMAGING, doctor, imagingBody(patient.getSystemId()), Map.of("Idempotency-Key", imagingKey));
        MvcResult b = doPostWithHeaders(IMAGING, doctor, imagingBody(patient.getSystemId()), Map.of("Idempotency-Key", imagingKey));
        assertEquals(201, a.getResponse().getStatus());
        assertEquals(200, b.getResponse().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM imaging_results", Integer.class));

        assertStatus(doPostWithHeaders(LAB, doctor, labBody(patient.getSystemId()), Map.of("Idempotency-Key", "bad key!")),
                400, "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    // ==================== DOCUMENTS ====================

    @Test
    void documentIdMustBeAUsableDocumentOfThePatient() throws Exception {
        int own = fixtures.document(patient.getSystemId(), doctor.getSystemId());
        int foreign = fixtures.document(otherPatient.getSystemId(), otherDoctor.getSystemId());

        assertValidation(doPost(LAB, doctor, labWith("documentId", foreign)),
                "documentId: document not found for this patient", false);
        assertValidation(doPost(IMAGING, doctor, imagingWith("documentId", 999_999)),
                "documentId: document not found for this patient", false);

        int id = createLab(labWith("documentId", own));
        assertEquals(own, json(doGet(LAB + "/" + id, patient)).path("documentId").asInt());

        fixtures.softDeleteDocument(own);
        assertTrue(json(doGet(LAB + "/" + id, patient)).path("documentId").isNull());
        assertValidation(doPut(LAB + "/" + id, doctor, Map.of("documentId", own)),
                "documentId: document not found for this patient", false);

        int replacement = fixtures.document(patient.getSystemId(), doctor.getSystemId());
        int imagingId = createImaging(imagingBody(patient.getSystemId()));
        JsonNode updated = json(doPut(IMAGING + "/" + imagingId, doctor, Map.of("documentId", replacement)));
        assertEquals(replacement, updated.path("documentId").asInt());
    }

    // ==================== UPDATE ====================

    @Test
    void onlyTheAuthorDoctorMayEdit() throws Exception {
        int id = createLab(labWith("status", "COMPLETED"));
        int imagingId = createImaging(imagingBody(patient.getSystemId()));
        fixtures.careTeam(patient.getSystemId(), otherDoctor.getSystemId());

        for (User user : List.of(otherDoctor, admin, patient, caregiver)) {
            assertStatus(doPut(LAB + "/" + id, user, Map.of("unit", "g/dL")), 403, NOT_AUTHOR);
            assertStatus(doPut(IMAGING + "/" + imagingId, user, Map.of("bodyPart", "Knee")), 403, NOT_AUTHOR);
        }
        assertStatus(doPut(LAB + "/999999", doctor, Map.of("unit", "g/dL")), 404, "Lab result not found");
        assertStatus(doPut(IMAGING + "/999999", doctor, Map.of("bodyPart", "Knee")), 404, "Imaging result not found");

        applicationEvents.clear();
        JsonNode updated = json(doPut(LAB + "/" + id, doctor, Map.of("unit", "g/dL", "resultValue", "10.8")));
        assertEquals("g/dL", updated.path("unit").asText());
        assertEquals("Full Blood Count (FBC)", updated.path("testName").asText());
        assertEquals(1, events().size());
        assertFalse(events().get(0).created());

        JsonNode cleared = json(doPut(LAB + "/" + id, doctor, Map.of("unit", " ")));
        assertTrue(cleared.path("unit").isNull());
        assertEquals("10.8", cleared.path("resultValue").asText());

        assertValidation(doPut(LAB + "/" + id, doctor, Map.of("testName", "  ")), "testName: must not be blank", true);
        assertValidation(doPut(LAB + "/" + id, doctor, Map.of("status", "CANCELLED")),
                "status: must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL", true);
        assertStatus(doPut(LAB + "/" + id, doctor, Map.of("status", "PENDING")), 409,
                "A recorded result cannot be moved back to pending");
        assertEquals(2, fixtures.auditRows("UPDATE", "LAB_RESULT", patient.getSystemId()));
    }

    @Test
    void putNormalisesCriticalityBothWays() throws Exception {
        int id = createLab(labWith("status", "COMPLETED"));
        applicationEvents.clear();

        JsonNode flagged = json(doPut(LAB + "/" + id, doctor, Map.of("isCritical", true)));
        assertEquals("CRITICAL", flagged.path("status").asText());
        assertTrue(flagged.path("isCritical").asBoolean());
        assertTrue(onlyEvent().newlyCritical());

        applicationEvents.clear();
        JsonNode unflagged = json(doPut(LAB + "/" + id, doctor, Map.of("isCritical", false)));
        assertEquals("COMPLETED", unflagged.path("status").asText());
        assertFalse(unflagged.path("isCritical").asBoolean());
        ClinicalResultRecordedEvent event = onlyEvent();
        assertFalse(event.critical());
        assertFalse(event.newlyCritical());

        assertValidation(doPut(LAB + "/" + id, doctor, Map.of("status", "CRITICAL", "isCritical", false)),
                "isCritical: cannot be false when status is CRITICAL", false);
    }

    // ==================== STATUS ====================

    @Test
    void patchStatusFollowsTheTransitionRulesAndNormalisation() throws Exception {
        Map<String, Object> body = labBody(patient.getSystemId());
        body.put("isCritical", true);
        int id = createLab(body);
        applicationEvents.clear();

        JsonNode completed = json(doPatch(LAB + "/" + id + "/status?status=completed", doctor, null));
        assertEquals("CRITICAL", completed.path("status").asText());
        assertTrue(completed.path("isCritical").asBoolean());
        assertFalse(onlyEvent().newlyCritical(), "the flag was already set");

        applicationEvents.clear();
        MvcResult same = doPatch(LAB + "/" + id + "/status?status=CRITICAL", doctor, null);
        assertStatus(same, 200, null);
        assertTrue(events().isEmpty(), "no-op publishes nothing");

        JsonNode back = json(doPatch(LAB + "/" + id + "/status?status=COMPLETED", doctor, null));
        assertEquals("COMPLETED", back.path("status").asText());
        assertFalse(back.path("isCritical").asBoolean());
        applicationEvents.clear();
        json(doPatch(LAB + "/" + id + "/status?status=CRITICAL", doctor, null));
        assertTrue(onlyEvent().newlyCritical(), "critical again after it stopped being critical");

        assertStatus(doPatch(LAB + "/" + id + "/status?status=PENDING", doctor, null), 409,
                "A recorded result cannot be moved back to pending");
        assertValidation(doPatch(LAB + "/" + id + "/status?status=CANCELLED", doctor, null),
                "status: must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL", true);
        assertStatus(doPatch(LAB + "/" + id + "/status", doctor, null), 400, "Missing required parameter 'status'");
        assertStatus(doPatch(LAB + "/999999/status?status=COMPLETED", doctor, null), 404, "Lab result not found");
        assertStatus(doPatch(LAB + "/" + id + "/status?status=COMPLETED", patient, null), 403, NOT_ROLE);
        assertStatus(doPatch(LAB + "/" + id + "/status?status=COMPLETED", admin, null), 403, NOT_ROLE);
        assertStatus(doPatch(LAB + "/" + id + "/status?status=COMPLETED", otherDoctor, null), 403, NOT_CARE_TEAM);

        // The author who lost the care relationship can no longer change status (but may still edit).
        fixtures.endCareTeam(patient.getSystemId(), doctor.getSystemId());
        assertStatus(doPatch(LAB + "/" + id + "/status?status=COMPLETED", doctor, null), 403, NOT_CARE_TEAM);
        assertStatus(doPut(LAB + "/" + id, doctor, Map.of("unit", "g/dL")), 200, null);
    }

    @Test
    void imagingStatusChangeAndCriticalEvents() throws Exception {
        int id = createImaging(imagingBody(patient.getSystemId()));
        applicationEvents.clear();

        JsonNode critical = json(doPatch(IMAGING + "/" + id + "/status?status=CRITICAL", doctor, null));
        assertEquals("CRITICAL", critical.path("status").asText());
        assertTrue(critical.path("isCritical").asBoolean());
        ClinicalResultRecordedEvent event = onlyEvent();
        assertTrue(event.critical());
        assertTrue(event.newlyCritical());
        assertEquals("IMAGING", event.kind());

        assertStatus(doPatch(IMAGING + "/" + id + "/status?status=PENDING", doctor, null), 409,
                "A recorded result cannot be moved back to pending");
        assertStatus(doPatch(IMAGING + "/" + id + "/status?status=COMPLETED", otherDoctor, null), 403, NOT_CARE_TEAM);
    }

    // ==================== REVIEW ====================

    @Test
    void reviewStampsTheCallerAndRejectsNonFinalTargets() throws Exception {
        int id = createLab(labWith("status", "REQUIRES_REVIEW"));
        fixtures.careTeam(patient.getSystemId(), otherDoctor.getSystemId());
        applicationEvents.clear();

        assertValidation(doPost(LAB + "/" + id + "/review", otherDoctor, Map.of("status", "PENDING")),
                "status: must be COMPLETED or CRITICAL", true);
        assertValidation(doPost(LAB + "/" + id + "/review", otherDoctor, Map.of("status", "REQUIRES_REVIEW")),
                "status: must be COMPLETED or CRITICAL", true);
        assertValidation(doPost(LAB + "/" + id + "/review", otherDoctor, Map.of("reviewNotes", "x")),
                "status: is required", true);
        assertValidation(doPost(LAB + "/" + id + "/review", otherDoctor,
                Map.of("status", "COMPLETED", "reviewNotes", "x".repeat(2001))),
                "reviewNotes: must be at most 2000 characters", true);
        assertStatus(rawPost(LAB + "/" + id + "/review", otherDoctor, null), 400, "Malformed request body");
        assertStatus(doPost(LAB + "/" + id + "/review", patient, Map.of("status", "COMPLETED")), 403, NOT_ROLE);
        assertStatus(doPost(LAB + "/" + id + "/review", admin, Map.of("status", "COMPLETED")), 403, NOT_ROLE);
        assertTrue(events().isEmpty());

        // The reviewer comes from the token; the old query parameters are not read.
        MvcResult reviewed = doPost(LAB + "/" + id + "/review?reviewedBy=" + doctor.getSystemId() + "&status=COMPLETED",
                otherDoctor, Map.of("status", "critical", "reviewNotes", "  Repeat FBC in 48 hours.  "));
        assertStatus(reviewed, 200, null);
        JsonNode body = json(reviewed);
        assertEquals("CRITICAL", body.path("status").asText());
        assertTrue(body.path("isCritical").asBoolean());
        assertEquals(otherDoctor.getSystemId(), body.path("reviewedBy").asText());
        assertEquals("Dr. DOCTOR User5", body.path("reviewedByName").asText());
        assertEquals("Repeat FBC in 48 hours.", body.path("reviewNotes").asText());
        assertFalse(body.path("reviewedAt").isNull());
        LocalDateTime reviewedAt = LocalDateTime.parse(body.path("reviewedAt").asText());
        assertTrue(Math.abs(java.time.Duration.between(reviewedAt, LocalDateTime.now(ZoneId.of("Africa/Harare")))
                .toMinutes()) < 5, "reviewedAt is clinic time");
        ClinicalResultRecordedEvent event = onlyEvent();
        assertEquals(otherDoctor.getSystemId(), event.actorSystemId());
        assertEquals(doctor.getSystemId(), event.doctorSystemId());
        assertTrue(event.newlyCritical());

        // Re-review replaces the stamp; COMPLETED clears the flag.
        JsonNode again = json(doPost(LAB + "/" + id + "/review", doctor, Map.of("status", "COMPLETED")));
        assertEquals("COMPLETED", again.path("status").asText());
        assertFalse(again.path("isCritical").asBoolean());
        assertEquals(doctor.getSystemId(), again.path("reviewedBy").asText());
        assertTrue(again.path("reviewNotes").isNull());

        // Back into REQUIRES_REVIEW clears the stamp.
        JsonNode reopened = json(doPatch(LAB + "/" + id + "/status?status=REQUIRES_REVIEW", doctor, null));
        assertTrue(reopened.path("reviewedBy").isNull());
        assertTrue(reopened.path("reviewedAt").isNull());
        assertTrue(reopened.path("reviewedByName").isNull());

        // A doctor without a relationship cannot review; nor can anyone review a cancelled result.
        int otherResult = createImaging(imagingBody(patient.getSystemId()));
        User stranger = createAndSaveUser(20, "DOC-00020", "stranger@example.com", "DOCTOR");
        assertStatus(doPost(IMAGING + "/" + otherResult + "/review", stranger, Map.of("status", "COMPLETED")), 403,
                NOT_CARE_TEAM);
        assertStatus(doDelete(IMAGING + "/" + otherResult, doctor), 204, null);
        assertStatus(doPost(IMAGING + "/" + otherResult + "/review", doctor, Map.of("status", "COMPLETED")), 409,
                "A cancelled result cannot be reviewed");
        assertStatus(doPost(LAB + "/999999/review", doctor, Map.of("status", "COMPLETED")), 404, "Lab result not found");
    }

    @Test
    void imagingReviewStampsTheCaller() throws Exception {
        int id = createImaging(imagingWith("status", "REQUIRES_REVIEW"));
        JsonNode reviewed = json(doPost(IMAGING + "/" + id + "/review", doctor,
                Map.of("status", "COMPLETED", "reviewNotes", "No action needed.")));
        assertEquals("COMPLETED", reviewed.path("status").asText());
        assertEquals(doctor.getSystemId(), reviewed.path("reviewedBy").asText());
        assertEquals("Dr. DOCTOR User2", reviewed.path("reviewedByName").asText());
        assertEquals("No action needed.", reviewed.path("reviewNotes").asText());
    }

    // ==================== PENDING-REVIEW QUEUE ====================

    @Test
    void pendingReviewQueueHoldsUnreviewedResultsOfAuthorOrCareTeam() throws Exception {
        int awaiting = createLab(labWith("status", "REQUIRES_REVIEW"));
        int critical = createLab(labWith("status", "CRITICAL"));
        createLab(labWith("status", "COMPLETED"));
        createLab(labBody(patient.getSystemId()));
        int reviewed = createLab(labWith("status", "REQUIRES_REVIEW"));
        json(doPost(LAB + "/" + reviewed + "/review", doctor, Map.of("status", "COMPLETED")));
        int cancelled = createLab(labWith("status", "REQUIRES_REVIEW"));
        doDelete(LAB + "/" + cancelled, doctor);

        // otherDoctor's own patient: not in doctor's queue.
        created(doPost(LAB, otherDoctor, labWith("patientSystemId", otherPatient.getSystemId())));
        Map<String, Object> foreign = labBody(otherPatient.getSystemId());
        foreign.put("status", "REQUIRES_REVIEW");
        int foreignId = created(doPost(LAB, otherDoctor, foreign)).path("resultId").asInt();

        JsonNode queue = json(doGet(LAB + "/me/pending-review", doctor));
        assertEquals(2, queue.path("totalElements").asInt(), queue.toString());
        List<Integer> ids = queue.path("content").findValues("resultId").stream().map(JsonNode::asInt).toList();
        assertTrue(ids.containsAll(List.of(awaiting, critical)), ids.toString());
        assertFalse(ids.contains(foreignId));

        // otherDoctor joins the patient's care team: sees doctor's results, plus their own authored one.
        fixtures.careTeam(patient.getSystemId(), otherDoctor.getSystemId());
        JsonNode otherQueue = json(doGet(LAB + "/me/pending-review?size=1", otherDoctor));
        assertEquals(3, otherQueue.path("totalElements").asInt(), otherQueue.toString());
        assertEquals(1, otherQueue.path("content").size());
        assertEquals(1, otherQueue.path("size").asInt());

        // Completing through PATCH leaves the queue.
        json(doPatch(LAB + "/" + awaiting + "/status?status=COMPLETED", doctor, null));
        assertEquals(1, json(doGet(LAB + "/me/pending-review", doctor)).path("totalElements").asInt());

        for (User user : List.of(patient, caregiver, admin)) {
            assertStatus(doGet(LAB + "/me/pending-review", user), 403, NOT_ROLE);
            assertStatus(doGet(IMAGING + "/me/pending-review", user), 403, NOT_ROLE);
        }

        int imagingAwaiting = createImaging(imagingWith("status", "CRITICAL"));
        createImaging(imagingWith("status", "COMPLETED"));
        JsonNode imagingQueue = json(doGet(IMAGING + "/me/pending-review", doctor));
        assertEquals(1, imagingQueue.path("totalElements").asInt());
        assertEquals(imagingAwaiting, imagingQueue.path("content").get(0).path("imagingId").asInt());
    }

    // ==================== DELETE ====================

    @Test
    void deleteCancelsAndHidesTheResult() throws Exception {
        Map<String, Object> body = labWith("status", "CRITICAL");
        int id = createLab(body);
        int kept = createLab(labBody(patient.getSystemId()));

        for (User user : List.of(patient, caregiver, otherDoctor)) {
            assertStatus(doDelete(LAB + "/" + id, user), 403, NOT_AUTHOR);
        }
        assertStatus(doDelete(LAB + "/999999", doctor), 404, "Lab result not found");

        applicationEvents.clear();
        assertStatus(doDelete(LAB + "/" + id, doctor), 204, null);
        assertEquals(new ClinicalResultRecordedEvent("LAB", id, patient.getSystemId(), doctor.getSystemId(),
                doctor.getSystemId(), "CANCELLED", false, false, false), onlyEvent());
        assertEquals(1, fixtures.auditRows("DELETE", "LAB_RESULT", patient.getSystemId()));

        applicationEvents.clear();
        assertStatus(doDelete(LAB + "/" + id, doctor), 204, null);
        assertTrue(events().isEmpty(), "repeat is a no-op");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM lab_results", Integer.class), "no row deleted");

        JsonNode one = json(doGet(LAB + "/" + id, patient));
        assertEquals("CANCELLED", one.path("status").asText());
        assertFalse(one.path("isCritical").asBoolean());

        JsonNode list = json(doGet(LAB, patient));
        assertEquals(1, list.path("totalElements").asInt());
        assertEquals(kept, list.path("content").get(0).path("resultId").asInt());
        assertEquals(1, json(doGet(LAB + "?status=cancelled", patient)).path("totalElements").asInt());
        assertEquals(1, json(doGet(LAB + "/patient/" + patient.getSystemId(), patient)).size());

        assertStatus(doPut(LAB + "/" + id, doctor, Map.of("unit", "g/dL")), 409,
                "This result is cancelled and cannot be changed");
        assertStatus(doPatch(LAB + "/" + id + "/status?status=COMPLETED", doctor, null), 409,
                "This result is cancelled and cannot be changed");
        assertStatus(doPost(LAB + "/" + id + "/review", doctor, Map.of("status", "COMPLETED")), 409,
                "A cancelled result cannot be reviewed");

        // An administrator may cancel; imaging gains CANCELLED (V39 CHECK).
        int imagingId = createImaging(imagingBody(patient.getSystemId()));
        assertStatus(doDelete(IMAGING + "/" + imagingId, admin), 204, null);
        assertEquals("CANCELLED", json(doGet(IMAGING + "/" + imagingId, admin)).path("status").asText());
        assertEquals(0, json(doGet(IMAGING + "/patient/" + patient.getSystemId(), patient)).size());
    }

    // ==================== READ ACCESS ====================

    @Test
    void patientReadsOwnResultsOnly() throws Exception {
        int id = createLab(labBody(patient.getSystemId()));
        int imagingId = createImaging(imagingBody(patient.getSystemId()));

        assertStatus(doGet(LAB + "/" + id, patient), 200, null);
        assertStatus(doGet(IMAGING + "/" + imagingId, patient), 200, null);
        assertStatus(doGet(LAB + "/" + id, otherPatient), 403, "Patients can only access their own data");
        assertStatus(doGet(IMAGING + "/" + imagingId, otherPatient), 403, "Patients can only access their own data");
        assertStatus(doGet(LAB + "/" + id, otherDoctor), 403, NOT_CARE_TEAM);
        assertStatus(doGet(LAB + "/" + id, admin), 200, null);
        assertStatus(doGet(LAB + "/999999", patient), 404, "Lab result not found");
        assertStatus(doGet(LAB + "/abc", patient), 400, "Invalid value for parameter 'id'");
        assertStatus(doGet(LAB + "/critical", patient), 400, "Invalid value for parameter 'id'");

        assertEquals(1, json(doGet(LAB, patient)).path("totalElements").asInt());
        assertEquals(0, json(doGet(LAB, otherPatient)).path("totalElements").asInt());
        assertStatus(doGet(LAB + "?patientSystemId=" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");
        assertStatus(doGet(LAB + "/patient/" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");
        assertStatus(doGet(IMAGING + "/patient/" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");

        int auditBefore = fixtures.auditRows("READ", "LAB_RESULT", patient.getSystemId());
        doGet(LAB + "/" + id, patient);
        assertEquals(auditBefore + 1, fixtures.auditRows("READ", "LAB_RESULT", patient.getSystemId()));
    }

    @Test
    void caregiverNeedsTheMatchingFlagAndLosesAccessOnRevokeOrExpiry() throws Exception {
        int id = createLab(labBody(patient.getSystemId()));
        int imagingId = createImaging(imagingBody(patient.getSystemId()));
        String labList = LAB + "?patientSystemId=" + patient.getSystemId();
        String imagingList = IMAGING + "?patientSystemId=" + patient.getSystemId();

        assertStatus(doGet(LAB + "/" + id, caregiver), 403, NO_PROXY);
        assertStatus(doGet(LAB, caregiver), 400, "patientSystemId is required");

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(doGet(LAB + "/" + id, caregiver), 403,
                "Your proxy access for this patient does not allow viewing lab results");
        assertStatus(doGet(labList, caregiver), 403,
                "Your proxy access for this patient does not allow viewing lab results");

        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        assertStatus(doGet(LAB + "/" + id, caregiver), 200, null);
        assertEquals(1, json(doGet(labList, caregiver)).path("totalElements").asInt());
        assertEquals(1, json(doGet(LAB + "/patient/" + patient.getSystemId(), caregiver)).size());
        assertStatus(doGet(IMAGING + "/" + imagingId, caregiver), 403,
                "Your proxy access for this patient does not allow viewing imaging results");
        assertStatus(doGet(imagingList, caregiver), 403,
                "Your proxy access for this patient does not allow viewing imaging results");

        grantAccess(caregiver, patient, a -> a.setCanViewImaging(true));
        assertStatus(doGet(IMAGING + "/" + imagingId, caregiver), 200, null);
        assertEquals(1, json(doGet(imagingList, caregiver)).path("totalElements").asInt());
        assertStatus(doGet(LAB + "/" + id, caregiver), 403,
                "Your proxy access for this patient does not allow viewing lab results");

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertStatus(doGet(LAB + "/" + id, caregiver), 200, null);
        assertStatus(doGet(IMAGING + "/" + imagingId, caregiver), 200, null);
        // Caregivers never write.
        assertStatus(doPut(LAB + "/" + id, caregiver, Map.of("unit", "g/dL")), 403, NOT_AUTHOR);
        assertStatus(doPost(LAB + "/" + id + "/review", caregiver, Map.of("status", "COMPLETED")), 403, NOT_ROLE);

        grantAccess(caregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setCanViewImaging(true);
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertStatus(doGet(LAB + "/" + id, caregiver), 403, NO_PROXY);
        assertStatus(doGet(imagingList, caregiver), 403, NO_PROXY);

        grantAccess(caregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setCanViewImaging(true);
        });
        assertStatus(doGet(LAB + "/" + id, caregiver), 200, null);
        fixtures.revokeGrant(caregiver.getSystemId(), patient.getSystemId());
        assertStatus(doGet(LAB + "/" + id, caregiver), 403, NO_PROXY);
        assertStatus(doGet(IMAGING + "/patient/" + patient.getSystemId(), caregiver), 403, NO_PROXY);

        // A grant for another patient does not reach this one.
        grantAccess(otherCaregiver, otherPatient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertStatus(doGet(LAB + "/" + id, otherCaregiver), 403, NO_PROXY);
    }

    // ==================== LISTS ====================

    @Test
    void canonicalListScopesFiltersAndClampsPages() throws Exception {
        createLab(labWith("testDate", clinicToday().minusDays(3).toString()));
        Map<String, Object> urine = labWith("testType", "URINE");
        urine.put("testDate", clinicToday().minusDays(1).toString());
        int newest = createLab(urine);
        int critical = createLab(labWith("status", "CRITICAL"));
        created(doPost(LAB, otherDoctor, labBody(otherPatient.getSystemId())));

        JsonNode page = json(doGet(LAB, doctor));
        assertEquals(3, page.path("totalElements").asInt(), "doctor without patient: own authored results");
        assertEquals(critical, page.path("content").get(0).path("resultId").asInt(), "newest first (today)");
        assertEquals(newest, page.path("content").get(1).path("resultId").asInt());
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt());
        assertEquals(1, page.path("totalPages").asInt());

        assertStatus(doGet(LAB + "?doctorSystemId=" + otherDoctor.getSystemId(), doctor), 403,
                "You can only access your own doctor data");
        assertStatus(doGet(LAB + "?patientSystemId=" + otherPatient.getSystemId(), doctor), 403, NOT_CARE_TEAM);
        assertEquals(3, json(doGet(LAB + "?patientSystemId=" + patient.getSystemId(), doctor)).path("totalElements").asInt());
        assertEquals(4, json(doGet(LAB, admin)).path("totalElements").asInt(), "admin: system-wide");
        assertEquals(1, json(doGet(LAB + "?doctorSystemId=" + otherDoctor.getSystemId(), admin)).path("totalElements").asInt());

        assertEquals(1, json(doGet(LAB + "?type=urine", doctor)).path("totalElements").asInt());
        assertEquals(1, json(doGet(LAB + "?critical=true", patient)).path("totalElements").asInt());
        assertEquals(2, json(doGet(LAB + "?critical=false", patient)).path("totalElements").asInt());
        assertEquals(1, json(doGet(LAB + "?status=CRITICAL", patient)).path("totalElements").asInt());
        assertEquals(0, json(doGet(LAB + "?consultationId=424242", patient)).path("totalElements").asInt());

        JsonNode clamped = json(doGet(LAB + "?size=500&page=-3", patient));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        JsonNode tiny = json(doGet(LAB + "?size=0", patient));
        assertEquals(1, tiny.path("size").asInt());
        assertEquals(1, tiny.path("content").size());
        assertEquals(3, tiny.path("totalPages").asInt());

        assertValidation(doGet(LAB + "?status=DONE", patient),
                "status: must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL, CANCELLED", true);
        assertValidation(doGet(LAB + "?type=SALIVA", patient),
                "type: must be one of BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, OTHER", true);
        assertStatus(doGet(LAB + "?critical=maybe", patient), 400, "Invalid value for parameter 'critical'");
        assertStatus(doGet(LAB + "?page=x", patient), 400, "Invalid value for parameter 'page'");

        int auditBefore = fixtures.auditRows("READ", "LAB_RESULT", otherPatient.getSystemId());
        json(doGet(LAB + "?patientSystemId=" + otherPatient.getSystemId() + "&status=CANCELLED", admin));
        assertEquals(auditBefore + 1, fixtures.auditRows("READ", "LAB_RESULT", otherPatient.getSystemId()),
                "a patient-scoped list writes one row even when empty");
    }

    @Test
    void imagingListFiltersCriticalByStatus() throws Exception {
        createImaging(imagingWith("status", "CRITICAL"));
        createImaging(imagingWith("imagingType", "mri"));
        createImaging(imagingWith("status", "COMPLETED"));

        assertEquals(1, json(doGet(IMAGING + "?critical=true", patient)).path("totalElements").asInt());
        assertEquals(2, json(doGet(IMAGING + "?critical=false", patient)).path("totalElements").asInt());
        assertEquals(0, json(doGet(IMAGING + "?critical=true&status=COMPLETED", patient)).path("totalElements").asInt());
        assertEquals(1, json(doGet(IMAGING + "?type=MRI", doctor)).path("totalElements").asInt());
        assertValidation(doGet(IMAGING + "?type=SCAN", doctor),
                "type: must be one of XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, ECHO", true);
        assertEquals(100, json(doGet(IMAGING + "?size=1000", admin)).path("size").asInt());
        assertStatus(doGet(IMAGING, caregiver), 400, "patientSystemId is required");
    }

    @Test
    void legacyArraysKeepTheirShape() throws Exception {
        createLab(labBody(patient.getSystemId()));
        createImaging(imagingBody(patient.getSystemId()));

        JsonNode labs = json(doGet(LAB + "/patient/" + patient.getSystemId(), patient));
        assertTrue(labs.isArray());
        assertEquals(1, labs.size());
        JsonNode lab = labs.get(0);
        for (String field : List.of("resultId", "patientSystemId", "doctorSystemId", "consultationId", "testName",
                "testType", "resultValue", "unit", "referenceRange", "resultData", "status", "isCritical", "testDate",
                "reviewedBy", "reviewedAt", "fileUrl", "createdAt", "doctorName", "patientName", "documentId")) {
            assertTrue(lab.has(field), field);
        }

        JsonNode doctorArray = json(doGet(LAB + "/doctor/" + doctor.getSystemId(), doctor));
        assertTrue(doctorArray.isArray());
        assertEquals(1, doctorArray.size());
        assertStatus(doGet(LAB + "/doctor/" + doctor.getSystemId(), otherDoctor), 403,
                "You can only access your own doctor data");
        assertStatus(doGet(LAB + "/doctor/" + doctor.getSystemId(), patient), 403,
                "You can only access your own doctor data");
        assertEquals(1, json(doGet(LAB + "/doctor/" + doctor.getSystemId(), admin)).size());

        JsonNode images = json(doGet(IMAGING + "/patient/" + patient.getSystemId(), patient));
        assertTrue(images.isArray());
        assertEquals(1, images.size());
        assertTrue(images.get(0).has("thumbnailUrl"));
        assertTrue(images.get(0).has("isCritical"));

        // Removed duplicates are gone.
        assertStatus(doGet(LAB + "/type/blood", admin), 404, "Resource not found");
        assertStatus(doGet(IMAGING + "/doctor/" + doctor.getSystemId(), doctor), 404, "Resource not found");
    }
}
