package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gates for /api/profile: own profile read and partial update, the access matrix for other accounts
 * (other patient, related and unrelated doctor, caregiver with and without records / profile-management access,
 * expired and revoked grants), health-field redaction, the refused clinical and email fields, emergency-contact
 * CRUD with the single primary contact and cross-patient ids, idempotent create, and settings ownership with the
 * time zone, quiet hours and removed fields. Runs with committed transactions (CommittedIntegrationTest).
 */
class ProfileReleaseGateTest extends CommittedIntegrationTest {

    private static final String PROFILE = "/api/profile";
    private static final String CONTACTS = "/api/profile/emergency-contacts";
    private static final String SETTINGS = "/api/profile/settings";

    private static final String OWN_DATA_ONLY = "Patients can only access their own data";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";
    private static final String NO_EDIT = "Your proxy access for this patient does not allow editing the profile";
    private static final String NO_RECORDS = "Your proxy access for this patient does not allow viewing medical records";
    private static final String ROLE_DENIED = "Your role is not authorized to perform this action";
    private static final String OWN_ACCOUNT_ONLY = "You can only access your own account";
    private static final String HEALTH_STATUS_REFUSED = "Health status is set by your doctor and cannot be changed here";
    private static final String EMAIL_REFUSED = "Email cannot be changed here. Use Change email in your security settings";

    // ==================== HELPERS ====================

    /** A JSON body from key/value pairs; values may be null. */
    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private void assertForbidden(MvcResult result, String expectedMessage) throws Exception {
        assertStatus(403, result);
        assertEquals(expectedMessage, message(result));
    }

    private void givePatientHealthData() {
        jdbc.update("UPDATE patients SET blood_type = 'O+', allergies = '[\"Penicillin\"]'::jsonb, "
                + "chronic_conditions = '[\"Asthma\"]'::jsonb, gender = 'MALE', date_of_birth = DATE '1958-03-14', "
                + "health_status = 'MONITOR' WHERE system_id = ?", patient.getSystemId());
    }

    private int auditRows(String action, String resourceType, String subjectSystemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? "
                        + "AND (patient_system_id = ? OR target_system_id = ?) AND status = 'SUCCESS'",
                Integer.class, action, resourceType, subjectSystemId, subjectSystemId);
        return count == null ? 0 : count;
    }

    private int accessDeniedRows(String actorSystemId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND system_id = ?", Integer.class, actorSystemId);
        return count == null ? 0 : count;
    }

    private String storedString(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private List<String> listOf(JsonNode jsonArrayString) throws Exception {
        List<String> values = new ArrayList<>();
        objectMapper.readTree(jsonArrayString.asText()).forEach(node -> values.add(node.asText()));
        return values;
    }

    private void assertHealthRedacted(JsonNode profile) {
        assertTrue(profile.get("bloodType").isNull());
        assertTrue(profile.get("allergies").isNull());
        assertTrue(profile.get("chronicConditions").isNull());
        assertTrue(profile.get("healthStatus").isNull());
    }

    private JsonNode createContact(User as, Map<String, Object> request) throws Exception {
        MvcResult result = doPost(CONTACTS, as, request);
        assertStatus(201, result);
        return json(result);
    }

    private Map<String, Object> contact(String name, String phone, Boolean primary) {
        return body("name", name, "phone", phone, "isPrimary", primary);
    }

    // ==================== OWN PROFILE ====================

    @Test
    void patientReadsOwnProfileAndUpdatesOnlyTheFieldsSent() throws Exception {
        MvcResult read = doGet(PROFILE, patient);
        assertStatus(200, read);
        JsonNode profile = json(read);
        assertEquals("PAT-00001", profile.get("systemId").asText());
        assertEquals("PAT-00001", profile.get("patientSystemId").asText());
        assertEquals("STABLE", profile.get("healthStatus").asText());
        assertTrue(profile.get("emergencyContacts").isArray());
        assertTrue(profile.get("relationship").isNull());
        assertEquals(1, auditRows("READ", "PROFILE", patient.getSystemId()));

        MvcResult update = doPut(PROFILE, patient,
                body("phone", " +263 77 212 3456 ", "allergies", "[\" Penicillin \",\"Latex\"]"));
        assertStatus(200, update);
        JsonNode updated = json(update);
        assertEquals("+263 77 212 3456", updated.get("phone").asText());
        assertEquals(List.of("Penicillin", "Latex"), listOf(updated.get("allergies")));
        assertEquals("PATIENT", updated.get("firstName").asText(), "names not sent stay unchanged");
        assertTrue(updated.get("bloodType").isNull(), "fields not sent stay unchanged");
        assertEquals(1, auditRows("UPDATE", "PROFILE", patient.getSystemId()));

        // Blank names and phone mean "unchanged", never an error.
        MvcResult blanks = doPut(PROFILE, patient, body("firstName", "  ", "lastName", "", "phone", ""));
        assertStatus(200, blanks);
        assertEquals("PATIENT", json(blanks).get("firstName").asText());
        assertEquals("+263 77 212 3456", json(blanks).get("phone").asText());
    }

    @Test
    void healthStatusAndEmailAreRefusedForEveryCaller() throws Exception {
        MvcResult patientStatus = doPut(PROFILE, patient, body("healthStatus", "CRITICAL", "firstName", "Tom"));
        assertStatus(400, patientStatus);
        assertEquals(HEALTH_STATUS_REFUSED, message(patientStatus));
        assertTrue(json(patientStatus).path("code").isMissingNode(), "service rule: no code");

        MvcResult adminStatus = doPut(PROFILE + "/user/" + patient.getSystemId(), admin, body("healthStatus", "STABLE"));
        assertStatus(400, adminStatus);
        assertEquals(HEALTH_STATUS_REFUSED, message(adminStatus));

        MvcResult email = doPut(PROFILE, patient, body("email", "thomas@example.com"));
        assertStatus(400, email);
        assertEquals(EMAIL_REFUSED, message(email));

        assertStatus(200, doPut(PROFILE, patient, body("healthStatus", "", "email", " ")));
        assertEquals("STABLE", storedString("SELECT health_status FROM patients WHERE system_id = ?", patient.getSystemId()));
        assertEquals("PATIENT", storedString("SELECT first_name FROM users WHERE system_id = ?", patient.getSystemId()),
                "a refused request saves nothing");
    }

    @Test
    void anotherPatientIsRefused() throws Exception {
        assertForbidden(doGet(PROFILE + "/user/" + otherPatient.getSystemId(), patient), OWN_DATA_ONLY);
        assertTrue(accessDeniedRows(patient.getSystemId()) >= 1);
        assertForbidden(doPut(PROFILE + "/user/" + otherPatient.getSystemId(), patient, body("firstName", "X")),
                ROLE_DENIED);
        assertForbidden(doGet(PROFILE + "/user/" + doctor.getSystemId(), patient), OWN_ACCOUNT_ONLY);
        MvcResult unknown = doGet(PROFILE + "/user/PAT-09999", patient);
        assertStatus(404, unknown);
        assertEquals("User not found", message(unknown));
    }

    @Test
    void relatedDoctorReadsEveryHealthFieldUnrelatedDoctorIsRefused() throws Exception {
        givePatientHealthData();

        MvcResult related = doGet(PROFILE + "/user/" + patient.getSystemId(), doctor);
        assertStatus(200, related);
        JsonNode profile = json(related);
        assertEquals("O+", profile.get("bloodType").asText());
        assertEquals(List.of("Penicillin"), listOf(profile.get("allergies")));
        assertEquals(List.of("Asthma"), listOf(profile.get("chronicConditions")));
        assertEquals("MONITOR", profile.get("healthStatus").asText());
        assertEquals("1958-03-14", profile.get("dateOfBirth").asText());

        assertForbidden(doGet(PROFILE + "/user/" + patient.getSystemId(), otherDoctor), NOT_CARE_TEAM);
        assertForbidden(doPut(PROFILE + "/user/" + patient.getSystemId(), doctor, body("firstName", "X")), ROLE_DENIED);
        assertForbidden(doGet(PROFILE + "/user/" + otherDoctor.getSystemId(), doctor), OWN_ACCOUNT_ONLY);
        assertEquals(1, auditRows("READ", "PROFILE", patient.getSystemId()));
    }

    // ==================== CAREGIVER ====================

    @Test
    void caregiverWithoutRecordsSeesDemographicsButNotHealthFields() throws Exception {
        givePatientHealthData();
        createContact(patient, contact("Sarah Dube", "+263773456789", true));
        grantAccess(caregiver, patient, a -> { });

        MvcResult redacted = doGet(PROFILE + "/user/" + patient.getSystemId(), caregiver);
        assertStatus(200, redacted);
        JsonNode profile = json(redacted);
        assertHealthRedacted(profile);
        assertEquals("1958-03-14", profile.get("dateOfBirth").asText());
        assertEquals("MALE", profile.get("gender").asText());
        assertEquals(1, profile.get("emergencyContacts").size(), "emergency contacts are never redacted");

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        JsonNode full = json(doGet(PROFILE + "/user/" + patient.getSystemId(), caregiver));
        assertEquals("O+", full.get("bloodType").asText());
        assertEquals("MONITOR", full.get("healthStatus").asText());
    }

    @Test
    void caregiverEditsOnlyWithProfileManagementAccess() throws Exception {
        String url = PROFILE + "/user/" + patient.getSystemId();
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertForbidden(doPut(url, caregiver, body("firstName", "Tom")), NO_EDIT);

        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageCarePlan(true);
        });
        assertForbidden(doPut(url, caregiver, body("firstName", "Tom")), NO_EDIT);

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        MvcResult allowed = doPut(url, caregiver,
                body("firstName", "Tom", "phone", "", "dateOfBirth", "1958-03-14", "gender", "male"));
        assertStatus(200, allowed);
        JsonNode updated = json(allowed);
        assertEquals("Tom", updated.get("firstName").asText());
        assertEquals("MALE", updated.get("gender").asText());
        assertEquals("+12345678901", updated.get("phone").asText(), "blank phone leaves it unchanged");
        assertHealthRedacted(updated);
        assertEquals(1, auditRows("UPDATE", "PROFILE", patient.getSystemId()));

        assertForbidden(doPut(PROFILE + "/user/" + otherPatient.getSystemId(), caregiver, body("firstName", "X")),
                NO_PROXY);
        assertForbidden(doPut(PROFILE + "/user/" + doctor.getSystemId(), caregiver, body("firstName", "X")),
                OWN_ACCOUNT_ONLY);
        MvcResult unknown = doPut(PROFILE + "/user/PAT-09999", caregiver, body("firstName", "X"));
        assertStatus(404, unknown);
        assertEquals("User not found", message(unknown));
    }

    @Test
    void caregiverWithoutRecordsCannotChangeHealthFieldsAndNothingIsSaved() throws Exception {
        givePatientHealthData();
        String url = PROFILE + "/user/" + patient.getSystemId();
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));

        assertForbidden(doPut(url, caregiver, body("firstName", "Changed", "bloodType", "A+")), NO_RECORDS);
        assertForbidden(doPut(url, caregiver, body("allergies", "[]")), NO_RECORDS);
        assertForbidden(doPut(url, caregiver, body("chronicConditions", "")), NO_RECORDS);
        assertForbidden(doPut(url, caregiver, body("bloodType", "")), NO_RECORDS);
        assertEquals("PATIENT", storedString("SELECT first_name FROM users WHERE system_id = ?", patient.getSystemId()));
        assertEquals("O+", storedString("SELECT blood_type FROM patients WHERE system_id = ?", patient.getSystemId()));
        assertEquals("[\"Penicillin\"]",
                storedString("SELECT allergies::text FROM patients WHERE system_id = ?", patient.getSystemId()));

        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setCanViewRecords(true);
        });
        MvcResult allowed = doPut(url, caregiver, body("bloodType", "ab-", "allergies", "[\"Latex\"]"));
        assertStatus(200, allowed);
        assertEquals("AB-", json(allowed).get("bloodType").asText());
        assertEquals(List.of("Latex"), listOf(json(allowed).get("allergies")));
        assertEquals("MONITOR", json(allowed).get("healthStatus").asText());
    }

    @Test
    void expiredAndRevokedGrantsBehaveLikeNoGrant() throws Exception {
        String url = PROFILE + "/user/" + patient.getSystemId();
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertForbidden(doGet(url, caregiver), NO_PROXY);
        assertForbidden(doPut(url, caregiver, body("firstName", "X")), NO_PROXY);
        assertForbidden(doGet(CONTACTS + "/patient/" + patient.getSystemId(), caregiver), NO_PROXY);

        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
            a.setIsActive(false);
        });
        assertForbidden(doGet(url, caregiver), NO_PROXY);
        assertForbidden(doPost(CONTACTS, caregiver,
                body("patientSystemId", patient.getSystemId(), "name", "A", "phone", "+263771111111")), NO_PROXY);
    }

    @Test
    void caregiverCannotChangeTheDependentsEmail() throws Exception {
        String url = PROFILE + "/user/" + patient.getSystemId();
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        MvcResult refused = doPut(url, caregiver, body("email", "new.address@example.com"));
        assertStatus(400, refused);
        assertEquals(EMAIL_REFUSED, message(refused));
        assertEquals("thomas@example.com", storedString("SELECT email FROM users WHERE system_id = ?", patient.getSystemId()));

        // Without a grant the access check answers first.
        assertForbidden(doPut(url, otherCaregiver, body("email", "new.address@example.com")), NO_PROXY);
    }

    @Test
    void caregiverOwnProfileCarriesRelationshipAndPhoneFallback() throws Exception {
        jdbc.update("UPDATE caregivers SET relationship = 'Daughter', phone = '+263773456789' WHERE system_id = ?",
                caregiver.getSystemId());
        jdbc.update("UPDATE users SET phone = NULL WHERE system_id = ?", caregiver.getSystemId());

        MvcResult read = doGet(PROFILE, caregiver);
        assertStatus(200, read);
        JsonNode profile = json(read);
        assertEquals("CGV-00001", profile.get("systemId").asText());
        assertEquals("Daughter", profile.get("relationship").asText());
        assertEquals("+263773456789", profile.get("phone").asText());
        assertTrue(profile.get("patientSystemId").isNull());
        assertTrue(profile.get("emergencyContacts").isNull());
        assertEquals(0, auditRows("READ", "PROFILE", caregiver.getSystemId()), "non-patient reads are not audited");

        assertStatus(200, doPut(PROFILE, caregiver, body("firstName", "Sarah")));
        assertEquals(1, auditRows("PROFILE_UPDATE", "ACCOUNT", caregiver.getSystemId()));
    }

    // ==================== EMERGENCY CONTACTS ====================

    @Test
    void emergencyContactCrudKeepsASinglePrimaryContact() throws Exception {
        JsonNode sarah = createContact(patient, body("name", " Sarah Dube ", "relationship", "Daughter",
                "phone", "+263773456789", "email", "sarah.dube@example.com", "isPrimary", true));
        assertEquals("Sarah Dube", sarah.get("name").asText());
        assertEquals(patient.getSystemId(), sarah.get("patientSystemId").asText());
        assertTrue(sarah.get("isPrimary").asBoolean());
        assertFalse(sarah.get("isCaregiver").asBoolean());
        int sarahId = sarah.get("contactId").asInt();

        JsonNode john = createContact(patient, body("name", "John Moyo", "phone", "+263771111111",
                "relationship", "", "email", "", "isPrimary", true));
        int johnId = john.get("contactId").asInt();
        assertTrue(john.get("relationship").isNull());
        assertTrue(john.get("email").isNull());

        JsonNode list = json(doGet(CONTACTS, patient));
        assertEquals(2, list.size());
        assertEquals(johnId, list.get(0).get("contactId").asInt(), "primary first");
        assertFalse(list.get(1).get("isPrimary").asBoolean(), "the previous primary was demoted");

        MvcResult swap = doPut(CONTACTS + "/" + sarahId, patient,
                body("isPrimary", true, "relationship", "", "patientSystemId", otherPatient.getSystemId()));
        assertStatus(200, swap);
        assertTrue(json(swap).get("isPrimary").asBoolean());
        assertTrue(json(swap).get("relationship").isNull());
        assertEquals(patient.getSystemId(), json(swap).get("patientSystemId").asText(), "a contact never moves");
        JsonNode afterSwap = json(doGet(CONTACTS, patient));
        assertEquals(sarahId, afterSwap.get(0).get("contactId").asInt());
        assertFalse(afterSwap.get(1).get("isPrimary").asBoolean());

        assertEquals(sarahId, json(doGet(CONTACTS + "/" + sarahId, patient)).get("contactId").asInt());

        assertStatus(204, doDelete(CONTACTS + "/" + sarahId, patient));
        JsonNode afterDelete = json(doGet(CONTACTS, patient));
        assertEquals(1, afterDelete.size());
        assertFalse(afterDelete.get(0).get("isPrimary").asBoolean(), "nobody is promoted");
        MvcResult again = doDelete(CONTACTS + "/" + sarahId, patient);
        assertStatus(404, again);
        assertEquals("Emergency contact not found", message(again));

        assertEquals(2, auditRows("CREATE", "EMERGENCY_CONTACT", patient.getSystemId()));
        assertEquals(1, auditRows("UPDATE", "EMERGENCY_CONTACT", patient.getSystemId()));
        assertEquals(1, auditRows("DELETE", "EMERGENCY_CONTACT", patient.getSystemId()));
        assertTrue(auditRows("READ", "EMERGENCY_CONTACT", patient.getSystemId()) >= 4);
    }

    @Test
    void contactOfAnotherPatientIsJudgedAgainstThatPatient() throws Exception {
        JsonNode marysContact = createContact(admin, body("patientSystemId", otherPatient.getSystemId(),
                "name", "Peter", "phone", "+263772222222"));
        String url = CONTACTS + "/" + marysContact.get("contactId").asInt();

        assertForbidden(doGet(url, patient), OWN_DATA_ONLY);
        assertForbidden(doPut(url, patient, body("name", "Hijacked")), OWN_DATA_ONLY);
        assertForbidden(doDelete(url, patient), OWN_DATA_ONLY);

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertForbidden(doGet(url, caregiver), NO_PROXY);
        assertForbidden(doPut(url, caregiver, body("name", "Hijacked")), NO_PROXY);
        assertForbidden(doDelete(url, caregiver), NO_PROXY);
        assertForbidden(doGet(url, doctor), NOT_CARE_TEAM);

        assertEquals("Peter", storedString("SELECT name FROM emergency_contacts WHERE contact_id = ?",
                marysContact.get("contactId").asInt()));
        assertEquals(200, doGet(url, otherDoctor).getResponse().getStatus());

        MvcResult missing = doGet(CONTACTS + "/999999", patient);
        assertStatus(404, missing);
        assertEquals("Emergency contact not found", message(missing));
        MvcResult notANumber = doGet(CONTACTS + "/abc", patient);
        assertStatus(400, notANumber);
        assertEquals("Invalid value for parameter 'contactId'", message(notANumber));
    }

    @Test
    void emergencyContactWriteRulesPerRole() throws Exception {
        Map<String, Object> forPatient = body("patientSystemId", patient.getSystemId(), "name", "Ann", "phone", "+263771111111");

        // Doctors read their patient's contacts but never write them.
        assertStatus(200, doGet(CONTACTS + "/patient/" + patient.getSystemId(), doctor));
        assertForbidden(doGet(CONTACTS + "/patient/" + patient.getSystemId(), otherDoctor), NOT_CARE_TEAM);
        assertForbidden(doPost(CONTACTS, doctor, forPatient), ROLE_DENIED);

        // Any active grant reads; writing needs profile management.
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertStatus(200, doGet(CONTACTS + "/patient/" + patient.getSystemId(), caregiver));
        assertForbidden(doPost(CONTACTS, caregiver, forPatient), NO_EDIT);

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        JsonNode created = createContact(caregiver, forPatient);
        assertEquals(patient.getSystemId(), created.get("patientSystemId").asText());
        MvcResult noPatient = doPost(CONTACTS, caregiver, body("name", "Ann", "phone", "+263771111111"));
        assertStatus(400, noPatient);
        assertEquals("Patient System ID is required", message(noPatient));

        // A patient names only themselves.
        assertForbidden(doPost(CONTACTS, patient,
                body("patientSystemId", otherPatient.getSystemId(), "name", "Ann", "phone", "+263771111111")), OWN_DATA_ONLY);

        // An admin names any patient; a non-patient id is 404.
        MvcResult notAPatient = doPost(CONTACTS, admin,
                body("patientSystemId", doctor.getSystemId(), "name", "Ann", "phone", "+263771111111"));
        assertStatus(404, notAPatient);
        assertEquals("Patient not found", message(notAPatient));
        assertEquals(0, json(doGet(CONTACTS + "/patient/" + doctor.getSystemId(), admin)).size());

        // The own-list endpoint is for patients only.
        MvcResult notPatient = doGet(CONTACTS, caregiver);
        assertStatus(400, notPatient);
        assertEquals("Only patients can have emergency contacts", message(notPatient));
    }

    @Test
    void createIsIdempotentAndCappedAtTenContacts() throws Exception {
        String key = "contact-" + clientId().substring(0, 8);
        Map<String, Object> request = contact("Sarah Dube", "+263773456789", false);
        MvcResult first = doPostWithHeaders(CONTACTS, patient, request, Map.of("Idempotency-Key", key));
        assertStatus(201, first);
        MvcResult replay = doPostWithHeaders(CONTACTS, patient, request, Map.of("Idempotency-Key", key));
        assertStatus(200, replay);
        assertEquals(json(first).get("contactId").asInt(), json(replay).get("contactId").asInt());
        assertEquals(1, json(doGet(CONTACTS, patient)).size());
        assertEquals(1, auditRows("CREATE", "EMERGENCY_CONTACT", patient.getSystemId()), "a replay writes no audit row");

        MvcResult badKey = doPostWithHeaders(CONTACTS, patient, request, Map.of("Idempotency-Key", "bad key!"));
        assertStatus(400, badKey);
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));

        for (int i = 2; i <= 10; i++) {
            createContact(patient, contact("Contact " + i, "+26377100000" + (i % 10), false));
        }
        MvcResult eleventh = doPost(CONTACTS, patient, contact("One too many", "+263771999999", false));
        assertStatus(409, eleventh);
        assertEquals("A patient can have at most 10 emergency contacts", message(eleventh));
        assertEquals(10, json(doGet(CONTACTS, patient)).size());
    }

    // ==================== SETTINGS ====================

    @Test
    void settingsBelongToTheAccountOwnerAndAdmins() throws Exception {
        MvcResult own = doGet(SETTINGS, patient);
        assertStatus(200, own);
        JsonNode settings = json(own);
        assertEquals(patient.getSystemId(), settings.get("systemId").asText());
        assertTrue(settings.get("userId").isNull());
        assertTrue(settings.get("timezone").isNull(), "untouched accounts follow the clinic zone");
        assertTrue(settings.get("billingNotifications").asBoolean());
        assertFalse(settings.get("carePlanUpdateAlerts").asBoolean());
        assertFalse(settings.get("quietHoursEnabled").asBoolean());
        assertTrue(settings.get("quietHoursStart").isNull());
        for (String removed : List.of("profileVisibility", "dataSharingEnabled", "language", "theme",
                "weeklySummary", "twoFactorEnabled")) {
            assertFalse(settings.has(removed), removed + " is no longer returned");
        }
        assertEquals(1, auditRows("SETTINGS_READ", "USER_SETTINGS", patient.getSystemId()));

        String patientSettings = SETTINGS + "/user/" + patient.getSystemId();
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertForbidden(doGet(SETTINGS + "/user/" + otherPatient.getSystemId(), patient), OWN_ACCOUNT_ONLY);
        assertForbidden(doGet(patientSettings, caregiver), OWN_ACCOUNT_ONLY);
        assertForbidden(doGet(patientSettings, doctor), OWN_ACCOUNT_ONLY);
        assertForbidden(doPut(patientSettings, caregiver, body("labResultAlerts", false)), OWN_ACCOUNT_ONLY);
        assertStatus(200, doGet(patientSettings, patient));

        MvcResult byAdmin = doPut(patientSettings, admin, body("smsNotifications", false));
        assertStatus(200, byAdmin);
        assertFalse(json(byAdmin).get("smsNotifications").asBoolean());
        assertFalse(json(doGet(SETTINGS, patient)).get("smsNotifications").asBoolean());
        assertEquals(1, auditRows("SETTINGS_UPDATE", "USER_SETTINGS", patient.getSystemId()));

        MvcResult unknown = doGet(SETTINGS + "/user/PAT-09999", admin);
        assertStatus(404, unknown);
        assertEquals("User not found", message(unknown));
    }

    @Test
    void unknownTimeZoneIsRefusedAndBlankMeansClinicTime() throws Exception {
        MvcResult unknown = doPatch(SETTINGS, patient, body("timezone", "Mars/Olympus_Mons", "labResultAlerts", false));
        assertStatus(400, unknown);
        assertEquals("Unknown time zone", message(unknown));
        assertTrue(json(unknown).path("code").isMissingNode());
        assertStatus(400, doPatch(SETTINGS, patient, body("timezone", "+02:00")));
        assertTrue(json(doGet(SETTINGS, patient)).get("labResultAlerts").asBoolean(), "a failed request saves nothing");

        MvcResult nairobi = doPatch(SETTINGS, patient, body("timezone", " Africa/Nairobi "));
        assertStatus(200, nairobi);
        assertEquals("Africa/Nairobi", json(nairobi).get("timezone").asText());

        MvcResult clinic = doPatch(SETTINGS, patient, body("timezone", ""));
        assertStatus(200, clinic);
        assertTrue(json(clinic).get("timezone").isNull());
        assertNull(storedString("SELECT timezone FROM user_settings WHERE system_id = ?", patient.getSystemId()));
    }

    @Test
    void quietHoursRoundTrip() throws Exception {
        MvcResult noTimes = doPatch(SETTINGS, caregiver, body("quietHoursEnabled", true));
        assertStatus(400, noTimes);
        assertEquals("Set a start and an end time to turn on quiet hours", message(noTimes));

        MvcResult on = doPatch(SETTINGS, caregiver,
                body("quietHoursEnabled", true, "quietHoursStart", "22:00", "quietHoursEnd", "07:00"));
        assertStatus(200, on);
        assertTrue(json(on).get("quietHoursEnabled").asBoolean());
        assertEquals("22:00", json(on).get("quietHoursStart").asText());
        assertEquals("07:00", json(on).get("quietHoursEnd").asText());

        MvcResult same = doPatch(SETTINGS, caregiver, body("quietHoursEnd", "22:00"));
        assertStatus(400, same);
        assertEquals("Quiet hours start and end must be different", message(same));

        MvcResult off = doPatch(SETTINGS, caregiver, body("quietHoursEnabled", false));
        assertStatus(200, off);
        assertFalse(json(off).get("quietHoursEnabled").asBoolean());
        assertEquals("22:00", json(off).get("quietHoursStart").asText(), "times are kept when switched off");

        MvcResult backOn = doPatch(SETTINGS, caregiver, body("quietHoursEnabled", true));
        assertStatus(200, backOn);
        assertEquals("07:00", json(backOn).get("quietHoursEnd").asText());
        assertEquals("07:00", json(doGet(SETTINGS, caregiver)).get("quietHoursEnd").asText());
    }

    @Test
    void removedSettingsFieldsAreIgnoredAndOldVariantsAreGone() throws Exception {
        MvcResult patched = doPatch(SETTINGS, patient, body("theme", "PURPLE", "twoFactorEnabled", true,
                "language", "xx", "profileVisibility", "NOPE", "weeklySummary", true,
                "dataSharingEnabled", Map.of("any", "shape"), "labResultAlerts", false, "billingNotifications", false));
        assertStatus(200, patched);
        assertFalse(json(patched).get("labResultAlerts").asBoolean());
        assertFalse(json(patched).get("billingNotifications").asBoolean());
        assertFalse(json(patched).has("theme"));
        assertEquals("LIGHT", storedString("SELECT theme FROM user_settings WHERE system_id = ?", patient.getSystemId()));
        assertEquals("false", storedString("SELECT two_factor_enabled::text FROM user_settings WHERE system_id = ?",
                patient.getSystemId()));

        // Deprecated aliases keep working with the same body and response.
        MvcResult alias = doPatch(SETTINGS + "/notifications", patient, body("messageNotifications", false));
        assertStatus(200, alias);
        assertFalse(json(alias).get("messageNotifications").asBoolean());
        assertFalse(json(alias).get("labResultAlerts").asBoolean(), "partial: other toggles unchanged");
        MvcResult put = doPut(SETTINGS, patient, body("pushNotifications", false));
        assertStatus(200, put);
        assertFalse(json(put).get("pushNotifications").asBoolean());

        for (String variant : List.of("/privacy", "/appearance", "/language?language=en", "/theme?theme=DARK",
                "/timezone?timezone=UTC", "/two-factor?enabled=true")) {
            assertStatus(404, doPatch(SETTINGS + variant, patient, body()));
        }
    }
}
