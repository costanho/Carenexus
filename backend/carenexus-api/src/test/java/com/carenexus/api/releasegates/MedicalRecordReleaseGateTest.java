package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release gate for medical records (be-medical-records): authorisation matrix including caregiver records
 * permission, validation envelope including ICD codes, idempotent create, partial update, soft delete 404,
 * paging, the automatic CONSULT record of a completed consultation (event published by the test) and the
 * terminology search role gate. Requests commit, so audit rows and listeners are real.
 */
class MedicalRecordReleaseGateTest extends CommittedIntegrationTest {

    private static final String RECORDS = "/api/medical-records";
    private static final String A1 = "Your role is not authorized to perform this action";
    private static final String A2 = "Only the doctor who created this item can change it";
    private static final String A3 = "Patients can only access their own data";
    private static final String A4 = "You are not part of this patient's care team";
    private static final String A5 = "You don't have active proxy access to this patient";
    private static final String A6 = "Your proxy access for this patient does not allow viewing medical records";
    private static final String A8 = "You can only access your own doctor data";
    private static final String E4 = "Medical record not found";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ==================== CREATE ====================

    @Test
    void careDoctorFilesRecordWithNormalisedCodesAndAudit() throws Exception {
        Map<String, Object> body = body("Review of persistent cough", " note ");
        body.put("doctorSystemId", otherDoctor.getSystemId());
        body.put("source", "CONSULTATION");
        body.put("description", "  Dry cough for two weeks.  ");
        body.put("treatmentPlan", "   ");
        body.put("icdCodes", List.of(
                Map.of("code", " j06.9 "),
                Map.of("code", "R05.3", "description", "Chronic cough"),
                Map.of("code", "J06.9", "description", "duplicate is dropped")));

        MvcResult result = doPost(RECORDS, doctor, body);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode r = json(result);
        assertThat(r.path("recordType").asText()).isEqualTo("NOTE");
        assertThat(r.path("doctorSystemId").asText()).isEqualTo(doctor.getSystemId());
        assertThat(r.path("doctorName").asText()).isEqualTo("Dr. DOCTOR User2");
        assertThat(r.path("source").asText()).isEqualTo("MANUAL");
        assertThat(r.path("description").asText()).isEqualTo("Dry cough for two weeks.");
        assertThat(r.path("treatmentPlan").isNull()).isTrue();
        assertThat(r.has("observations") && r.path("observations").isNull()).isTrue();
        assertThat(r.path("isDeleted").asBoolean()).isFalse();
        assertThat(r.path("deletedAt").isNull()).isTrue();
        assertThat(r.path("createdAt").asText()).isEqualTo(r.path("updatedAt").asText());
        assertThat(r.path("icdCodes").isTextual()).isTrue();
        JsonNode codes = r.path("icdCodeList");
        assertThat(codes).hasSize(2);
        assertThat(codes.get(0).path("code").asText()).isEqualTo("J06.9");
        assertThat(codes.get(0).path("description").asText()).isEqualTo("Acute upper respiratory infection, unspecified");
        assertThat(codes.get(0).path("verified").asBoolean()).isTrue();
        assertThat(codes.get(1).path("code").asText()).isEqualTo("R05.3");
        assertThat(codes.get(1).path("description").asText()).isEqualTo("Chronic cough");
        assertThat(codes.get(1).path("verified").asBoolean()).isFalse();

        int id = r.path("recordId").asInt();
        assertThat(auditRows("CREATE", id, doctor)).isEqualTo(1);

        // A reload returns the same stored values.
        JsonNode read = json(doGet(RECORDS + "/" + id, patient));
        assertThat(read.path("icdCodeList")).hasSize(2);
        assertThat(read.path("createdAt").asText()).isEqualTo(r.path("createdAt").asText());
    }

    @Test
    void onlyARelatedActiveDoctorMayFileRecords() throws Exception {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        Map<String, Object> body = body("Title", "NOTE");

        assertForbidden(doPost(RECORDS, patient, body), A1);
        assertForbidden(doPost(RECORDS, caregiver, body), A1);
        assertForbidden(doPost(RECORDS, admin, body), A1);
        assertForbidden(doPost(RECORDS, otherDoctor, body), A4);

        jdbc.update("UPDATE doctors SET is_active = FALSE WHERE system_id = ?", doctor.getSystemId());
        assertForbidden(doPost(RECORDS, doctor, body), "Your clinician account is not active");
        assertThat(count("SELECT COUNT(*) FROM medical_records")).isZero();
    }

    @Test
    void invalidBodiesAnswer400WithTheValidationEnvelope() throws Exception {
        Map<String, Object> noTitle = body("Title", "NOTE");
        noTitle.remove("title");
        assertValidation(doPost(RECORDS, doctor, noTitle), "title: is required", "title");

        assertValidation(doPost(RECORDS, doctor, body("Title", "LETTER")),
                "recordType: must be one of CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE, OPERATION", "recordType");
        assertValidation(doPost(RECORDS, doctor, body("x".repeat(201), "NOTE")),
                "title: must be at most 200 characters", "title");

        Map<String, Object> badPatient = body("Title", "NOTE");
        badPatient.put("patientSystemId", "patient-1");
        assertValidation(doPost(RECORDS, doctor, badPatient), "patientSystemId: must be a valid system id", "patientSystemId");

        Map<String, Object> zeroConsultation = body("Title", "NOTE");
        zeroConsultation.put("consultationId", 0);
        assertValidation(doPost(RECORDS, doctor, zeroConsultation), "consultationId: must be a positive number", "consultationId");

        Map<String, Object> longText = body("Title", "NOTE");
        longText.put("observations", "o".repeat(10001));
        assertValidation(doPost(RECORDS, doctor, longText), "observations: must be at most 10000 characters", "observations");

        Map<String, Object> tooMany = body("Title", "NOTE");
        List<Map<String, String>> codes = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            codes.add(Map.of("code", "Z99." + i));
        }
        tooMany.put("icdCodes", codes);
        assertValidation(doPost(RECORDS, doctor, tooMany), "icdCodes: must contain at most 20 codes", "icdCodes");

        Map<String, Object> nullItem = body("Title", "NOTE");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        nullItem.put("icdCodes", withNull);
        assertValidation(doPost(RECORDS, doctor, nullItem), "icdCodes[0]: must not be null", "icdCodes[0]");

        Map<String, Object> blankCode = body("Title", "NOTE");
        blankCode.put("icdCodes", List.of(Map.of("code", "I10"), Map.of("code", " ")));
        assertValidation(doPost(RECORDS, doctor, blankCode), "icdCodes[1].code: is required", "icdCodes[1].code");

        Map<String, Object> longDescription = body("Title", "NOTE");
        longDescription.put("icdCodes", List.of(Map.of("code", "I10", "description", "d".repeat(256))));
        assertValidation(doPost(RECORDS, doctor, longDescription),
                "icdCodes[0].description: must be at most 255 characters", "icdCodes[0].description");

        // Service rule: a code that is not a well-formed ICD-10 code (errors[] absent).
        Map<String, Object> badCode = body("Title", "NOTE");
        badCode.put("icdCodes", List.of(Map.of("code", "I10"), Map.of("code", "XYZ-1")));
        MvcResult bad = doPost(RECORDS, doctor, badCode);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(bad)).isEqualTo("icdCodes[1].code: is not a valid ICD-10 code");
        assertThat(json(bad).path("code").asText()).isEqualTo("VALIDATION_FAILED");

        // icdCodes as a string (the old shape) is a malformed body.
        Map<String, Object> stringCodes = body("Title", "NOTE");
        stringCodes.put("icdCodes", "[{\"code\":\"I10\"}]");
        MvcResult malformed = doPost(RECORDS, doctor, stringCodes);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(malformed)).isEqualTo("Malformed request body");

        assertThat(count("SELECT COUNT(*) FROM medical_records")).isZero();
    }

    @Test
    void consultationMustExistAndBelongToThePatient() throws Exception {
        int otherPatientsConsultation = insertConsultation(otherPatient, otherDoctor, "COMPLETED", "Cough", null, null, null, null);
        Map<String, Object> foreign = body("Title", "NOTE");
        foreign.put("consultationId", otherPatientsConsultation);
        MvcResult result = doPost(RECORDS, doctor, foreign);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(result)).isEqualTo("Consultation does not belong to this patient");

        Map<String, Object> missing = body("Title", "NOTE");
        missing.put("consultationId", 999999);
        MvcResult notFound = doPost(RECORDS, doctor, missing);
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(notFound)).isEqualTo("Consultation not found");
    }

    @Test
    void idempotencyKeyReplaysTheFirstResponse() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", "records-key-0001");
        MvcResult first = doPostWithHeaders(RECORDS, doctor, body("First", "NOTE"), headers);
        MvcResult second = doPostWithHeaders(RECORDS, doctor, body("Different body", "LAB"), headers);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(second).path("recordId").asInt()).isEqualTo(json(first).path("recordId").asInt());
        assertThat(json(second).path("title").asText()).isEqualTo("First");
        assertThat(count("SELECT COUNT(*) FROM medical_records")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'CREATE' AND resource_type = 'MEDICAL_RECORD'")).isEqualTo(1);

        MvcResult badKey = doPostWithHeaders(RECORDS, doctor, body("x", "NOTE"), Map.of("Idempotency-Key", "short"));
        assertThat(badKey.getResponse().getStatus()).isEqualTo(400);
    }

    // ==================== READ ====================

    @Test
    void readMatrixForOneRecord() throws Exception {
        int id = createRecord(doctor, body("Visit note", "NOTE"));
        String url = RECORDS + "/" + id;

        assertThat(doGet(url, patient).getResponse().getStatus()).isEqualTo(200);
        assertThat(doGet(url, doctor).getResponse().getStatus()).isEqualTo(200);
        assertThat(doGet(url, admin).getResponse().getStatus()).isEqualTo(200);
        assertForbidden(doGet(url, otherPatient), A3);
        assertForbidden(doGet(url, otherDoctor), A4);
        assertForbidden(doGet(url, caregiver), A5);

        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertForbidden(doGet(url, caregiver), A6);

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertThat(doGet(url, caregiver).getResponse().getStatus()).isEqualTo(200);

        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertForbidden(doGet(url, caregiver), A5);

        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
            a.setIsActive(false);
        });
        assertForbidden(doGet(url, caregiver), A5);

        // The author can still open the record after the care relationship ended.
        jdbc.update("UPDATE care_team SET is_active = FALSE WHERE provider_system_id = ?", doctor.getSystemId());
        assertThat(doGet(url, doctor).getResponse().getStatus()).isEqualTo(200);

        assertThat(doGet(RECORDS + "/abc", patient).getResponse().getStatus()).isEqualTo(400);
        assertThat(doGet(RECORDS + "/999999", admin).getResponse().getStatus()).isEqualTo(404);
        assertThat(auditRows("READ", id, patient)).isEqualTo(1);
    }

    @Test
    void pagedListScopesFiltersAndSortsNewestFirst() throws Exception {
        int first = createRecord(doctor, withCodes(body("Hypertension review", "NOTE"), "I10"));
        int second = createRecord(doctor, body("Blood panel", "LAB"));
        int third = createRecord(doctor, withCodes(body("Sore throat", "CONSULT"), "J02.9"));
        createRecord(otherDoctor, bodyFor(otherPatient, "Other patient's note", "NOTE"));

        JsonNode page0 = json(doGet(RECORDS + "?size=2", patient));
        assertThat(page0.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("size").asInt()).isEqualTo(2);
        assertThat(ids(page0)).containsExactly(third, second);
        assertThat(ids(json(doGet(RECORDS + "?size=2&page=1", patient)))).containsExactly(first);
        assertThat(json(doGet(RECORDS + "?size=500", patient)).path("size").asInt()).isEqualTo(100);
        assertThat(json(doGet(RECORDS + "?size=0&page=-3", patient)).path("page").asInt()).isZero();

        assertThat(ids(json(doGet(RECORDS + "?type=lab", patient)))).containsExactly(second);
        assertThat(json(doGet(RECORDS + "?type=LAB&size=1", patient)).path("totalElements").asLong()).isEqualTo(1);
        assertThat(ids(json(doGet(RECORDS + "?q=j02", patient)))).containsExactly(third);
        assertThat(ids(json(doGet(RECORDS + "?q=pharyngitis", patient)))).containsExactly(third);
        assertThat(ids(json(doGet(RECORDS + "?q=HYPERTENSION", patient)))).containsExactly(first);
        assertThat(ids(json(doGet(RECORDS + "?q=100%25", patient)))).isEmpty();

        // Doctor: own records by default, a patient's history from every author with patientSystemId.
        assertThat(json(doGet(RECORDS, doctor)).path("totalElements").asLong()).isEqualTo(3);
        assertThat(json(doGet(RECORDS + "?doctorSystemId=" + doctor.getSystemId() + "&patientSystemId="
                + otherPatient.getSystemId(), doctor)).path("totalElements").asLong()).isZero();
        assertForbidden(doGet(RECORDS + "?doctorSystemId=" + otherDoctor.getSystemId(), doctor), A8);
        assertForbidden(doGet(RECORDS + "?patientSystemId=" + patient.getSystemId(), otherDoctor), A4);

        // Patients and caregivers.
        assertForbidden(doGet(RECORDS + "?patientSystemId=" + otherPatient.getSystemId(), patient), A3);
        MvcResult noPatient = doGet(RECORDS, caregiver);
        assertThat(noPatient.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(noPatient)).isEqualTo("patientSystemId: is required");
        assertForbidden(doGet(RECORDS + "?patientSystemId=" + patient.getSystemId(), caregiver), A5);
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertThat(json(doGet(RECORDS + "?patientSystemId=" + patient.getSystemId(), caregiver))
                .path("totalElements").asLong()).isEqualTo(3);

        // Admin sees everything.
        assertThat(json(doGet(RECORDS, admin)).path("totalElements").asLong()).isEqualTo(4);

        // Parameter validation.
        assertValidation(doGet(RECORDS + "?type=LETTER", patient),
                "type: must be one of CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE, OPERATION", "type");
        assertValidation(doGet(RECORDS + "?patientSystemId=bad", admin), "patientSystemId: must be a valid system id", "patientSystemId");
        assertValidation(doGet(RECORDS + "?consultationId=0", admin), "consultationId: must be a positive number", "consultationId");
        assertValidation(doGet(RECORDS + "?q=" + "q".repeat(101), admin), "q: must be at most 100 characters", "q");
        assertThat(doGet(RECORDS + "?page=x", admin).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void legacyArraysKeepTheirShapeAndRemovedRoutesAreGone() throws Exception {
        int first = createRecord(doctor, body("One", "NOTE"));
        int second = createRecord(doctor, body("Two", "LAB"));

        JsonNode byPatient = json(doGet(RECORDS + "/patient/" + patient.getSystemId(), patient));
        assertThat(byPatient.isArray()).isTrue();
        assertThat(byPatient).extracting(n -> n.path("recordId").asInt()).containsExactly(second, first);
        assertForbidden(doGet(RECORDS + "/patient/" + patient.getSystemId(), otherPatient), A3);

        JsonNode byDoctor = json(doGet(RECORDS + "/doctor/" + doctor.getSystemId(), doctor));
        assertThat(byDoctor.isArray()).isTrue();
        assertThat(byDoctor).hasSize(2);
        assertForbidden(doGet(RECORDS + "/doctor/" + doctor.getSystemId(), otherDoctor), A8);

        JsonNode root = json(doGet("/api/doctors/medical-records?type=lab", doctor));
        assertThat(root.isArray()).isTrue();
        assertThat(root).extracting(n -> n.path("recordId").asInt()).containsExactly(second);
        assertThat(json(doGet("/api/doctors/medical-records", admin))).isEmpty();
        assertThat(doGet("/api/doctors/medical-records", patient).getResponse().getStatus()).isEqualTo(403);

        assertThat(doGet(RECORDS + "/me/consult", patient).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet(RECORDS + "/type/NOTE", admin).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet("/api/doctors/medical-records/" + doctor.getSystemId(), doctor).getResponse().getStatus()).isEqualTo(404);
        assertThat(doGet("/api/doctors/medical-records/stats", doctor).getResponse().getStatus()).isEqualTo(404);
    }

    // ==================== UPDATE AND DELETE ====================

    @Test
    void authorOrAdminUpdatesPartially() throws Exception {
        Map<String, Object> create = withCodes(body("Original", "NOTE"), "I10");
        create.put("treatmentPlan", "Rest");
        create.put("observations", "Clear chest");
        int id = createRecord(doctor, create);
        String url = RECORDS + "/" + id;

        Map<String, Object> change = new LinkedHashMap<>();
        change.put("title", "  Updated  ");
        change.put("treatmentPlan", "  ");
        change.put("icdCodes", List.of());
        change.put("patientSystemId", otherPatient.getSystemId());
        change.put("consultationId", 42);
        MvcResult updated = doPut(url, doctor, change);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode r = json(updated);
        assertThat(r.path("title").asText()).isEqualTo("Updated");
        assertThat(r.path("treatmentPlan").isNull()).isTrue();
        assertThat(r.path("observations").asText()).isEqualTo("Clear chest");
        assertThat(r.path("recordType").asText()).isEqualTo("NOTE");
        assertThat(r.path("icdCodes").isNull()).isTrue();
        assertThat(r.path("icdCodeList")).isEmpty();
        assertThat(r.path("patientSystemId").asText()).isEqualTo(patient.getSystemId());
        assertThat(r.path("consultationId").isNull()).isTrue();
        assertThat(auditRows("UPDATE", id, doctor)).isEqualTo(1);

        assertForbidden(doPut(url, otherDoctor, Map.of("title", "x")), A2);
        assertForbidden(doPut(url, patient, Map.of("title", "x")), A2);
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertForbidden(doPut(url, caregiver, Map.of("title", "x")), A2);

        MvcResult byAdmin = doPut(url, admin, Map.of("recordType", "discharge"));
        assertThat(byAdmin.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(byAdmin).path("recordType").asText()).isEqualTo("DISCHARGE");
        assertThat(json(byAdmin).path("doctorSystemId").asText()).isEqualTo(doctor.getSystemId());

        assertValidation(doPut(url, doctor, Map.of("title", "   ")), "title: must not be blank", "title");
        assertValidation(doPut(url, doctor, Map.of("recordType", "X")),
                "recordType: must be one of CONSULT, LAB, IMAGING, PRESCRIPTION, REFERRAL, DISCHARGE, NOTE, OPERATION", "recordType");
        MvcResult badCode = doPut(url, doctor, Map.of("icdCodes", List.of(Map.of("code", "123"))));
        assertThat(message(badCode)).isEqualTo("icdCodes[0].code: is not a valid ICD-10 code");
    }

    @Test
    void softDeletedRecordAnswers404Everywhere() throws Exception {
        int id = createRecord(doctor, body("To delete", "NOTE"));
        String url = RECORDS + "/" + id;

        assertForbidden(doDelete(url, otherDoctor), A2);
        assertForbidden(doDelete(url, patient), A2);
        assertThat(doDelete(url, doctor).getResponse().getStatus()).isEqualTo(204);

        for (User user : List.of(patient, doctor, admin)) {
            MvcResult read = doGet(url, user);
            assertThat(read.getResponse().getStatus()).isEqualTo(404);
            assertThat(message(read)).isEqualTo(E4);
        }
        assertThat(doDelete(url, doctor).getResponse().getStatus()).isEqualTo(404);
        assertThat(doPut(url, doctor, Map.of("title", "x")).getResponse().getStatus()).isEqualTo(404);
        assertThat(json(doGet(RECORDS, patient)).path("totalElements").asLong()).isZero();
        assertThat(json(doGet(RECORDS + "/patient/" + patient.getSystemId(), patient))).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM medical_records WHERE is_deleted = TRUE AND deleted_at IS NOT NULL")).isEqualTo(1);
        assertThat(auditRows("DELETE", id, doctor)).isEqualTo(1);

        int adminDeletes = createRecord(doctor, body("Admin removes", "NOTE"));
        assertThat(doDelete(RECORDS + "/" + adminDeletes, admin).getResponse().getStatus()).isEqualTo(204);
    }

    // ==================== AUTOMATIC CONSULT RECORD ====================

    @Test
    void completedConsultationCreatesOneRecordVisibleToThePatient() throws Exception {
        int consultationId = insertConsultation(patient, doctor, "COMPLETED", "  Persistent\ncough   for two weeks ",
                "Bronchitis", "Dry cough, no fever.", "Fluids and rest.", "Chest clear on auscultation.");

        publishCompleted(consultationId, patient, doctor);

        JsonNode list = json(doGet(RECORDS + "?consultationId=" + consultationId, patient));
        assertThat(list.path("totalElements").asLong()).isEqualTo(1);
        JsonNode r = list.path("content").get(0);
        assertThat(r.path("recordType").asText()).isEqualTo("CONSULT");
        assertThat(r.path("source").asText()).isEqualTo("CONSULTATION");
        assertThat(r.path("title").asText()).isEqualTo("Persistent cough for two weeks");
        assertThat(r.path("description").asText()).isEqualTo("Dry cough, no fever.");
        assertThat(r.path("treatmentPlan").asText()).isEqualTo("Fluids and rest.");
        assertThat(r.path("observations").asText()).isEqualTo("Chest clear on auscultation.");
        assertThat(r.path("doctorSystemId").asText()).isEqualTo(doctor.getSystemId());
        assertThat(r.path("icdCodeList")).isEmpty();
        assertThat(r.path("icdCodes").isNull()).isTrue();
        assertThat(r.path("createdAt").asText()).isEqualTo(r.path("updatedAt").asText());
        int recordId = r.path("recordId").asInt();
        assertThat(auditRows("CREATE", recordId, doctor)).isEqualTo(1);

        // A repeated event adds nothing and does not fail.
        publishCompleted(consultationId, patient, doctor);
        assertThat(count("SELECT COUNT(*) FROM medical_records WHERE consultation_id = " + consultationId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'CREATE' AND resource_type = 'MEDICAL_RECORD'")).isEqualTo(1);

        // Caregivers with the records permission see it; a second CONSULT record by hand is refused.
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertThat(doGet(RECORDS + "/" + recordId, caregiver).getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> manual = body("Second consult record", "CONSULT");
        manual.put("consultationId", consultationId);
        MvcResult conflict = doPost(RECORDS, doctor, manual);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(message(conflict)).isEqualTo("A consultation record already exists for this consultation");

        // The author may edit the automatic record; after deleting it a CONSULT record may be filed by hand.
        assertThat(doPut(RECORDS + "/" + recordId, doctor, Map.of("icdCodes", List.of(Map.of("code", "J20.9"))))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(doDelete(RECORDS + "/" + recordId, doctor).getResponse().getStatus()).isEqualTo(204);
        assertThat(doPost(RECORDS, doctor, manual).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void manualConsultRecordPreventsTheAutomaticOneAndTitleFallsBack() throws Exception {
        int filedByHand = insertConsultation(patient, doctor, "IN_PROGRESS", "Headache", null, null, null, null);
        Map<String, Object> manual = body("Filed before completion", "CONSULT");
        manual.put("consultationId", filedByHand);
        createRecord(doctor, manual);
        publishCompleted(filedByHand, patient, doctor);
        assertThat(count("SELECT COUNT(*) FROM medical_records WHERE consultation_id = " + filedByHand)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM medical_records WHERE source = 'CONSULTATION'")).isZero();

        // Changing another record of that consultation to CONSULT is refused too.
        Map<String, Object> note = body("A note", "NOTE");
        note.put("consultationId", filedByHand);
        int noteId = createRecord(doctor, note);
        MvcResult conflict = doPut(RECORDS + "/" + noteId, doctor, Map.of("recordType", "CONSULT"));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

        int diagnosisOnly = insertConsultation(patient, doctor, "COMPLETED", "  ", "Acute bronchitis", null, null, null);
        publishCompleted(diagnosisOnly, patient, doctor);
        assertThat(jdbc.queryForObject("SELECT title FROM medical_records WHERE consultation_id = ?", String.class, diagnosisOnly))
                .isEqualTo("Acute bronchitis");

        int nothing = insertConsultation(patient, doctor, "COMPLETED", null, null, null, null, null);
        publishCompleted(nothing, patient, doctor);
        assertThat(jdbc.queryForObject("SELECT title FROM medical_records WHERE consultation_id = ?", String.class, nothing))
                .isEqualTo("Consultation");

        int longComplaint = insertConsultation(patient, doctor, "COMPLETED", "a".repeat(250), null, null, null, null);
        publishCompleted(longComplaint, patient, doctor);
        String title = jdbc.queryForObject("SELECT title FROM medical_records WHERE consultation_id = ?", String.class, longComplaint);
        assertThat(title).hasSize(200).endsWith("...").startsWith("a".repeat(197));

        // A missing consultation is ignored.
        publishCompleted(999999, patient, doctor);
    }

    // ==================== TERMINOLOGY ====================

    @Test
    void terminologySearchIsForDoctorsAndAdmins() throws Exception {
        MvcResult result = doGet("/api/terminology/icd?query=pharyng&size=10", doctor);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode page = json(result);
        assertThat(page.path("content")).extracting(n -> n.path("code").asText()).containsExactly("J02.0", "J02.9", "J31.2");
        assertThat(page.path("content").get(1).path("description").asText()).isEqualTo("Acute pharyngitis, unspecified");
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(10);

        JsonNode byCode = json(doGet("/api/terminology/icd?query=j06.9", admin));
        assertThat(byCode.path("content").get(0).path("code").asText()).isEqualTo("J06.9");

        JsonNode wide = json(doGet("/api/terminology/icd?query=un&size=5&page=1", doctor));
        assertThat(wide.path("totalElements").asLong()).isLessThanOrEqualTo(200).isGreaterThan(5);
        assertThat(wide.path("content")).hasSize(5);
        assertThat(json(doGet("/api/terminology/icd?query=un&page=1000", doctor)).path("content")).isEmpty();

        JsonNode tooShort = json(doGet("/api/terminology/icd?query=j", doctor));
        assertThat(tooShort.path("totalElements").asLong()).isZero();
        assertThat(tooShort.path("content")).isEmpty();
        assertThat(json(doGet("/api/terminology/icd", doctor)).path("content")).isEmpty();

        assertForbidden(doGet("/api/terminology/icd?query=pharyng", patient), A1);
        assertForbidden(doGet("/api/terminology/icd?query=pharyng", caregiver), A1);
        assertValidation(doGet("/api/terminology/icd?query=" + "x".repeat(101), doctor),
                "query: must be at most 100 characters", "query");
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ'")).isZero();
    }

    // ==================== HELPERS ====================

    private Map<String, Object> body(String title, String type) {
        return bodyFor(patient, title, type);
    }

    private static Map<String, Object> bodyFor(User forPatient, String title, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientSystemId", forPatient.getSystemId());
        body.put("recordType", type);
        body.put("title", title);
        return body;
    }

    private static Map<String, Object> withCodes(Map<String, Object> body, String... codes) {
        List<Map<String, String>> items = new ArrayList<>();
        for (String code : codes) {
            items.add(Map.of("code", code));
        }
        body.put("icdCodes", items);
        return body;
    }

    private int createRecord(User as, Map<String, Object> body) throws Exception {
        MvcResult result = doPost(RECORDS, as, body);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).path("recordId").asInt();
    }

    private int insertConsultation(User forPatient, User byDoctor, String status, String chiefComplaint, String diagnosis,
                                   String clinicalNotes, String treatmentPlan, String examination) {
        Timestamp at = Timestamp.valueOf(LocalDateTime.now().minusHours(1).withNano(0));
        Integer appointmentId = jdbc.queryForObject(
                "INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, duration_minutes, type, status) "
                        + "VALUES (?, ?, ?, 30, 'IN_PERSON', 'COMPLETED') RETURNING appointment_id",
                Integer.class, forPatient.getSystemId(), byDoctor.getSystemId(), at);
        return jdbc.queryForObject(
                "INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, chief_complaint, diagnosis, "
                        + "clinical_notes, treatment_plan, physical_examination, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING consultation_id",
                Integer.class, appointmentId, forPatient.getSystemId(), byDoctor.getSystemId(), chiefComplaint, diagnosis,
                clinicalNotes, treatmentPlan, examination, status, at);
    }

    /** Publishes the event the way ConsultationService does: inside the completing doctor's transaction. */
    private void publishCompleted(int consultationId, User forPatient, User byDoctor) {
        authenticateAs(byDoctor);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                    new ConsultationCompletedEvent(consultationId, null, forPatient.getSystemId(),
                            byDoctor.getSystemId(), false, null)));
        } finally {
            clearAuthentication();
        }
    }

    private static List<Integer> ids(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(n -> ids.add(n.path("recordId").asInt()));
        return ids;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long auditRows(String action, int recordId, User actor) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'MEDICAL_RECORD' "
                        + "AND resource_id = ? AND system_id = ? AND status = 'SUCCESS'",
                Long.class, action, recordId, actor.getSystemId());
        return value == null ? 0 : value;
    }

    private void assertForbidden(MvcResult result, String expectedMessage) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(message(result)).isEqualTo(expectedMessage);
    }

    private void assertValidation(MvcResult result, String expectedMessage, String field) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(400);
        JsonNode body = json(result);
        assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.path("errors")).extracting(n -> n.path("field").asText()).contains(field);
    }
}
