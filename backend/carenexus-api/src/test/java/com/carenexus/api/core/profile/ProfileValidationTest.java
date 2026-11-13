package com.carenexus.api.core.profile;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Request validation of /api/profile: the Bean Validation envelope (code VALIDATION_FAILED, errors[], message
 * "field: text") for profile, emergency-contact and settings bodies, the service rules for the allergy and
 * condition lists, the clearing semantics of a partial profile update, malformed JSON, the phone conflict, and the
 * V51 time zone default.
 */
class ProfileValidationTest extends CommittedIntegrationTest {

    private static final String PROFILE = "/api/profile";
    private static final String CONTACTS = "/api/profile/emergency-contacts";
    private static final String SETTINGS = "/api/profile/settings";

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private void assertValidation(MvcResult result, String expectedMessage) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode error = json(result);
        assertEquals("VALIDATION_FAILED", error.path("code").asText());
        assertEquals(expectedMessage, error.path("message").asText());
        assertTrue(error.path("errors").isArray() && !error.path("errors").isEmpty());
    }

    private void assertServiceRule(MvcResult result, String expectedMessage) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode error = json(result);
        assertEquals(expectedMessage, error.path("message").asText());
        assertTrue(error.path("code").isMissingNode());
        assertTrue(error.path("errors").isMissingNode());
    }

    private MvcResult rawCall(String method, String url, String rawJson) throws Exception {
        var builder = "PATCH".equals(method) ? patch(url) : put(url);
        return mockMvc.perform(builder.header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content(rawJson)).andReturn();
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ==================== PROFILE ====================

    @Test
    void profileFieldConstraints() throws Exception {
        assertValidation(doPut(PROFILE, patient, body("firstName", repeat('a', 101))),
                "firstName: First name must be at most 100 characters");
        assertValidation(doPut(PROFILE, patient, body("lastName", repeat('b', 101))),
                "lastName: Last name must be at most 100 characters");
        assertValidation(doPut(PROFILE, patient, body("phone", "call me")), "phone: Enter a valid phone number");
        assertValidation(doPut(PROFILE, patient, body("phone", "+263 (77) 212-3456 000")),
                "phone: Phone number must be at most 20 characters");
        assertValidation(doPut(PROFILE, patient, body("dateOfBirth", LocalDate.now().toString())),
                "dateOfBirth: Date of birth must be in the past");
        assertValidation(doPut(PROFILE, patient, body("gender", "unknown")),
                "gender: Gender must be MALE, FEMALE or OTHER");
        assertValidation(doPut(PROFILE, patient, body("bloodType", "C+")),
                "bloodType: Blood type must be one of A+, A-, B+, B-, AB+, AB-, O+, O-");
        assertValidation(doPut(PROFILE, patient, body("allergies", "\"" + repeat('x', 6000) + "\"")),
                "allergies: Allergies must be at most 6000 characters");
        assertValidation(doPut(PROFILE, patient, body("chronicConditions", "\"" + repeat('x', 6000) + "\"")),
                "chronicConditions: Chronic conditions must be at most 6000 characters");
        // A 100-character name padded with spaces is trimmed before validation.
        assertEquals(200, doPut(PROFILE, patient, body("firstName", "  " + repeat('a', 100) + "  "))
                .getResponse().getStatus());
    }

    @Test
    void listContentRules() throws Exception {
        assertServiceRule(doPut(PROFILE, patient, body("allergies", "Penicillin")), "Allergies must be a list");
        assertServiceRule(doPut(PROFILE, patient, body("allergies", "{\"a\":1}")), "Allergies must be a list");
        assertServiceRule(doPut(PROFILE, patient, body("chronicConditions", "[\"x\"] trailing")),
                "Chronic conditions must be a list");
        assertServiceRule(doPut(PROFILE, patient, body("allergies", "[\"Latex\", 3]")),
                "Each allergy must be 1 to 100 characters");
        assertServiceRule(doPut(PROFILE, patient, body("allergies", "[\"  \"]")),
                "Each allergy must be 1 to 100 characters");
        assertServiceRule(doPut(PROFILE, patient, body("chronicConditions", "[\"" + repeat('c', 101) + "\"]")),
                "Each chronic condition must be 1 to 100 characters");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add("item " + i);
        }
        assertServiceRule(doPut(PROFILE, patient, body("allergies", objectMapper.writeValueAsString(many))),
                "Allergies can have at most 50 entries");
        assertServiceRule(doPut(PROFILE, patient, body("chronicConditions", objectMapper.writeValueAsString(many))),
                "Chronic conditions can have at most 50 entries");
    }

    @Test
    void partialUpdateClearsOnlyTheClearableFields() throws Exception {
        MvcResult set = doPut(PROFILE, patient, body("gender", "female", "bloodType", "ab+",
                "allergies", "[\"Latex\"]", "chronicConditions", "[\"Asthma\"]", "dateOfBirth", "1958-03-14"));
        assertEquals(200, set.getResponse().getStatus(), set.getResponse().getContentAsString());
        assertEquals("FEMALE", json(set).get("gender").asText());
        assertEquals("AB+", json(set).get("bloodType").asText());

        MvcResult cleared = doPut(PROFILE, patient, body("gender", "", "bloodType", " ", "allergies", "",
                "chronicConditions", "[]", "dateOfBirth", null));
        assertEquals(200, cleared.getResponse().getStatus(), cleared.getResponse().getContentAsString());
        JsonNode profile = json(cleared);
        assertTrue(profile.get("gender").isNull());
        assertTrue(profile.get("bloodType").isNull());
        assertEquals(0, objectMapper.readTree(profile.get("allergies").asText()).size());
        assertEquals(0, objectMapper.readTree(profile.get("chronicConditions").asText()).size());
        assertEquals("1958-03-14", profile.get("dateOfBirth").asText(), "null leaves the date of birth unchanged");
    }

    @Test
    void phoneOfAnotherAccountIsAConflict() throws Exception {
        MvcResult conflict = doPut(PROFILE, patient, body("phone", otherPatient.getPhone(), "firstName", "Tom"));
        assertEquals(409, conflict.getResponse().getStatus());
        assertEquals("Phone number already registered", message(conflict));
        assertEquals("PATIENT", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                patient.getSystemId()));
        // The caller's own phone is not a conflict.
        assertEquals(200, doPut(PROFILE, patient, body("phone", patient.getPhone())).getResponse().getStatus());
    }

    @Test
    void malformedBodiesAreRejected() throws Exception {
        MvcResult badDate = rawCall("PUT", PROFILE, "{\"dateOfBirth\":\"12/05/1980\"}");
        assertEquals(400, badDate.getResponse().getStatus());
        assertEquals("Malformed request body", message(badDate));
        MvcResult arrayAllergies = rawCall("PUT", PROFILE, "{\"allergies\":[\"x\"]}");
        assertEquals(400, arrayAllergies.getResponse().getStatus());
        assertEquals("Malformed request body", message(arrayAllergies));
        MvcResult badToggle = rawCall("PATCH", SETTINGS, "{\"labResultAlerts\":\"sometimes\"}");
        assertEquals(400, badToggle.getResponse().getStatus());
        assertEquals("Malformed request body", message(badToggle));
    }

    // ==================== EMERGENCY CONTACTS ====================

    @Test
    void emergencyContactCreateConstraints() throws Exception {
        MvcResult empty = doPost(CONTACTS, patient, body());
        assertValidation(empty, "name: Contact name is required");
        List<String> fields = new ArrayList<>();
        json(empty).get("errors").forEach(e -> fields.add(e.get("field").asText()));
        assertEquals(List.of("name", "phone"), fields);

        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", " ")), "phone: Phone number is required");
        assertValidation(doPost(CONTACTS, patient, body("name", repeat('n', 151), "phone", "+263771111111")),
                "name: Contact name must be at most 150 characters");
        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", "+263771111111",
                "relationship", repeat('r', 81))), "relationship: Relationship must be at most 80 characters");
        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", "12")),
                "phone: Enter a valid phone number");
        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", "+263771111111", "email", "ann@x")),
                "email: Enter a valid email address");
        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", "+263771111111",
                "patientSystemId", "patient-1")), "patientSystemId: Invalid patient id");
        assertValidation(doPost(CONTACTS, patient, body("name", "Ann", "phone", "+263771111111",
                "email", repeat('e', 140) + "@example.com")), "email: Email must be at most 150 characters");
    }

    @Test
    void emergencyContactUpdateConstraints() throws Exception {
        MvcResult created = doPost(CONTACTS, patient, body("name", "Ann", "phone", "+263771111111",
                "email", "ann@example.com", "relationship", "Sister"));
        assertEquals(201, created.getResponse().getStatus());
        String url = CONTACTS + "/" + json(created).get("contactId").asInt();

        assertValidation(doPut(url, patient, body("name", "  ")), "name: Contact name cannot be blank");
        assertValidation(doPut(url, patient, body("phone", "")), "phone: Enter a valid phone number");
        assertValidation(doPut(url, patient, body("email", "nope")), "email: Enter a valid email address");

        MvcResult cleared = doPut(url, patient, body("email", "", "relationship", ""));
        assertEquals(200, cleared.getResponse().getStatus());
        assertTrue(json(cleared).get("email").isNull());
        assertTrue(json(cleared).get("relationship").isNull());
        assertEquals("Ann", json(cleared).get("name").asText());
        assertEquals("+263771111111", json(cleared).get("phone").asText());

        MvcResult unprimary = doPut(url, patient, body("isPrimary", false, "isCaregiver", true));
        assertFalse(json(unprimary).get("isPrimary").asBoolean());
        assertTrue(json(unprimary).get("isCaregiver").asBoolean());
    }

    // ==================== SETTINGS ====================

    @Test
    void settingsConstraints() throws Exception {
        assertValidation(doPatch(SETTINGS, patient, body("quietHoursStart", "7:00")),
                "quietHoursStart: Use 24-hour time as HH:mm");
        assertValidation(doPatch(SETTINGS, patient, body("quietHoursEnd", "24:00")),
                "quietHoursEnd: Use 24-hour time as HH:mm");
        assertValidation(doPatch(SETTINGS, patient, body("quietHoursStart", "")),
                "quietHoursStart: Use 24-hour time as HH:mm");
        assertValidation(doPatch(SETTINGS, patient, body("timezone", repeat('z', 51))),
                "timezone: Time zone must be at most 50 characters");

        // Times can be stored while quiet hours are off, even when equal.
        MvcResult stored = doPatch(SETTINGS, patient, body("quietHoursStart", "09:00", "quietHoursEnd", "09:00"));
        assertEquals(200, stored.getResponse().getStatus());
        assertEquals("09:00", json(stored).get("quietHoursStart").asText());
        assertEquals(200, doPatch(SETTINGS, patient, body()).getResponse().getStatus());
        assertEquals("UTC", json(doPatch(SETTINGS, patient, body("timezone", "UTC"))).get("timezone").asText());
    }

    @Test
    void timeZoneColumnDefaultsToClinicTime() {
        jdbc.update("INSERT INTO user_settings (system_id) VALUES (?)", doctor.getSystemId());
        assertNull(jdbc.queryForObject("SELECT timezone FROM user_settings WHERE system_id = ?", String.class,
                doctor.getSystemId()));
    }
}
