package com.carenexus.api.health;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared fixtures of the health-tracking HTTP tests: committed requests (listeners and audit run as in production)
 * against the care circle of {@link CommittedIntegrationTest} (patient PAT-00001 with doctor DOC-00001, caregiver
 * CGV-00001 without a grant, unrelated PAT-00002 / DOC-00002 / CGV-00002, admin ADM-00001).
 */
public abstract class HealthApiTestBase extends CommittedIntegrationTest {

    protected static final String METRICS = "/api/health-metrics";
    protected static final String SYMPTOMS = "/api/symptom-logs";

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Clinic wall-clock string of a time (seconds precision, no offset). */
    protected static String wallClock(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.SECONDS).format(WALL_CLOCK);
    }

    /** One reading as the web sends it; {@code recordedAt} may be null (= now). */
    protected static Map<String, Object> reading(String type, Object value, LocalDateTime recordedAt) {
        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("type", type);
        reading.put("value", value);
        if (recordedAt != null) {
            reading.put("recordedAt", wallClock(recordedAt));
        }
        return reading;
    }

    protected static Map<String, Object> reading(String type, Object value) {
        return reading(type, value, null);
    }

    /** A POST /api/health-metrics body; {@code patientSystemId} may be null (patient self). */
    @SafeVarargs
    protected static Map<String, Object> session(String patientSystemId, Map<String, Object>... readings) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (patientSystemId != null) {
            body.put("patientSystemId", patientSystemId);
        }
        body.put("readings", new ArrayList<>(List.of(readings)));
        return body;
    }

    protected MvcResult record(User as, Map<String, Object> body) throws Exception {
        return doPost(METRICS, as, body);
    }

    /** Records and asserts 201; returns the response body. */
    protected JsonNode recordOk(User as, Map<String, Object> body) throws Exception {
        MvcResult result = record(as, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    /** A blood-pressure pair (plus optional heart rate) at {@code at}, recorded by {@code as} for the patient. */
    protected JsonNode recordBloodPressure(User as, String patientSystemId, int systolic, int diastolic,
                                          LocalDateTime at) throws Exception {
        return recordOk(as, session(patientSystemId, reading("BP_SYSTOLIC", systolic, at),
                reading("BP_DIASTOLIC", diastolic, at)));
    }

    /** A POST /api/symptom-logs body. */
    protected static Map<String, Object> symptomLog(String patientSystemId, String severity, String... symptoms) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (patientSystemId != null) {
            body.put("patientSystemId", patientSystemId);
        }
        body.put("symptoms", List.of(symptoms));
        body.put("severity", severity);
        return body;
    }

    protected JsonNode logOk(User as, Map<String, Object> body) throws Exception {
        MvcResult result = doPost(SYMPTOMS, as, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    /** Asserts the status and the {@code message} of the error envelope. */
    protected void assertError(MvcResult result, int status, String message) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertEquals(status, result.getResponse().getStatus(), content);
        assertEquals(message, message(result), content);
    }

    /** Asserts a field-validation 400 with exactly one {@code errors} entry. */
    protected void assertFieldError(MvcResult result, String field, String fieldMessage) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertEquals(400, result.getResponse().getStatus(), content);
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText(), content);
        assertEquals(1, body.path("errors").size(), content);
        assertEquals(field, body.path("errors").get(0).path("field").asText(), content);
        assertEquals(fieldMessage, body.path("errors").get(0).path("message").asText(), content);
        assertEquals(field + ": " + fieldMessage, body.path("message").asText(), content);
    }

    /** Asserts a rule-validation 400 (code VALIDATION_FAILED, no errors array). */
    protected void assertRuleError(MvcResult result, String message) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertEquals(400, result.getResponse().getStatus(), content);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText(), content);
        assertEquals(message, message(result), content);
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected int auditRows(String action, String resourceType, String patientSystemId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? AND patient_system_id = ?",
                action, resourceType, patientSystemId);
    }

    /** "<role> User<n>", the name createAndSaveUser gives a fixture user. */
    protected static String nameOf(User user) {
        return user.getFirstName() + " " + user.getLastName();
    }
}
