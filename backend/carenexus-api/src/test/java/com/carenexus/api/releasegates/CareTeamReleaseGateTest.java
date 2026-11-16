package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Release gates for the care-team API (be-care-team): who may read and change a patient's care team, assignment of
 * CareNexus doctors and external providers, the single-primary swap, soft removal (and what it does to access),
 * the legacy PRIMARY_DOCTOR fixture rows, idempotent assignment, the patient notification, the doctor's paged list,
 * the 400 envelopes and the audit rows. Runs with committed transactions (CommittedIntegrationTest).
 *
 * Fixtures: PAT-00001 (patient) has DOC-00001 (doctor) as a legacy PRIMARY_DOCTOR row; PAT-00002 has DOC-00002.
 */
class CareTeamReleaseGateTest extends CommittedIntegrationTest {

    private static final String ROLE_DENIED = "Your role is not authorized to perform this action";
    private static final String NOT_ON_TEAM = "You are not part of this patient's care team";
    private static final String OWN_DATA = "Patients can only access their own data";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";
    private static final String DUPLICATE = "This provider is already on the patient's care team";
    private static final List<String> MEMBER_FIELDS = List.of("assignedAt", "careTeamId", "facilityId", "facilityName",
            "isPrimary", "patientName", "patientSystemId", "providerName", "providerOrganisation", "providerPhone",
            "providerSystemId", "role", "source", "specialization");

    @Autowired
    private AppointmentRepository appointmentRepository;

    // ==================== HELPERS ====================

    private String teamUrl(User forPatient) {
        return "/api/patients/" + forPatient.getSystemId() + "/care-team";
    }

    private String memberUrl(User forPatient, int careTeamId) {
        return teamUrl(forPatient) + "/" + careTeamId;
    }

    private JsonNode list(User forPatient, User as) throws Exception {
        MvcResult result = doGet(teamUrl(forPatient), as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.isArray(), "the care team is a plain array");
        return body;
    }

    private static Map<String, Object> doctorBody(User provider, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("providerSystemId", provider.getSystemId());
        body.put("role", role);
        return body;
    }

    private static Map<String, Object> externalBody(String name, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("providerName", name);
        body.put("role", role);
        return body;
    }

    private JsonNode assignOk(User forPatient, User as, Map<String, Object> body) throws Exception {
        MvcResult result = doPost(teamUrl(forPatient), as, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int fixtureRowId(User forPatient) {
        return jdbc.queryForObject("SELECT care_team_id FROM care_team WHERE patient_system_id = ? "
                + "AND role = 'PRIMARY_DOCTOR' ORDER BY care_team_id LIMIT 1", Integer.class, forPatient.getSystemId());
    }

    private int facility(String name, boolean active) {
        return jdbc.queryForObject("INSERT INTO facilities (name, type, is_active) VALUES (?, 'CLINIC', ?) "
                + "RETURNING facility_id", Integer.class, name, active);
    }

    private void appointment(User withDoctor, User forPatient, String status) {
        appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId()).doctorSystemId(withDoctor.getSystemId())
                .scheduledAt(LocalDateTime.now().minusDays(3)).type("PHONE").status(status).build());
    }

    private int auditRows(String action, Integer resourceId, String patientSystemId) {
        if (resourceId == null) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'CARE_TEAM' "
                    + "AND resource_id IS NULL AND patient_system_id = ? AND status = 'SUCCESS'", Integer.class,
                    action, patientSystemId);
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'CARE_TEAM' "
                + "AND resource_id = ? AND patient_system_id = ? AND status = 'SUCCESS'", Integer.class,
                action, resourceId, patientSystemId);
    }

    private int activePrimaryRows(User forPatient) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? AND is_active AND is_primary",
                Integer.class, forPatient.getSystemId());
    }

    private static JsonNode find(JsonNode members, String field, String value) {
        for (JsonNode member : members) {
            if (value.equals(member.path(field).asText())) {
                return member;
            }
        }
        return null;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    // ==================== READ ====================

    @Test
    void list_legacyPrimaryDoctorReadsAsDoctorAndPrimary_forEveryAllowedReader() throws Exception {
        JsonNode members = list(patient, patient);
        assertEquals(1, members.size());
        JsonNode member = members.get(0);
        assertEquals(MEMBER_FIELDS, fieldNames(member), "every field is present, null ones included");
        assertEquals(fixtureRowId(patient), member.path("careTeamId").asInt());
        assertEquals("ASSIGNED", member.path("source").asText());
        assertEquals("DOCTOR", member.path("role").asText(), "stored PRIMARY_DOCTOR reads as DOCTOR");
        assertTrue(member.path("isPrimary").asBoolean(), "stored PRIMARY_DOCTOR reads as primary");
        assertFalse(member.has("primary"), "the flag is serialised as isPrimary only");
        assertEquals(doctor.getSystemId(), member.path("providerSystemId").asText());
        assertEquals("DOCTOR User2", member.path("providerName").asText());
        assertTrue(member.path("providerPhone").isNull(), "a doctor's phone is never returned");
        assertEquals("General Practice", member.path("specialization").asText());
        assertEquals("PATIENT User1", member.path("patientName").asText());
        assertTrue(member.path("assignedAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?"),
                "clinic wall-clock without offset: " + member.path("assignedAt").asText());
        assertEquals(1, auditRows("READ", null, patient.getSystemId()));

        // The same list through /me, for the doctor on the row, for an admin, and for a caregiver with any active grant
        MvcResult mine = doGet("/api/patients/me/care-team", patient);
        assertEquals(200, mine.getResponse().getStatus());
        assertEquals(members, json(mine));
        assertEquals(members, list(patient, doctor));
        assertEquals(members, list(patient, admin));
        grantAccess(caregiver, patient, null);
        assertEquals(members, list(patient, caregiver));

        // The legacy fixture row still grants the doctor access to the patient's data
        assertEquals(200, doGet(teamUrl(patient), doctor).getResponse().getStatus());
    }

    @Test
    void list_refusesEveryUnrelatedCaller() throws Exception {
        MvcResult otherPatientCall = doGet(teamUrl(patient), otherPatient);
        assertEquals(403, otherPatientCall.getResponse().getStatus());
        assertEquals(OWN_DATA, message(otherPatientCall));

        MvcResult unrelatedDoctor = doGet(teamUrl(patient), otherDoctor);
        assertEquals(403, unrelatedDoctor.getResponse().getStatus());
        assertEquals(NOT_ON_TEAM, message(unrelatedDoctor));

        MvcResult noGrant = doGet(teamUrl(patient), caregiver);
        assertEquals(403, noGrant.getResponse().getStatus());
        assertEquals(NO_PROXY, message(noGrant));

        grantAccess(caregiver, patient, a -> a.setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        MvcResult expired = doGet(teamUrl(patient), caregiver);
        assertEquals(403, expired.getResponse().getStatus());
        assertEquals(NO_PROXY, message(expired));

        grantAccess(caregiver, patient, a -> {
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        });
        assertEquals(403, doGet(teamUrl(patient), caregiver).getResponse().getStatus());

        // another caregiver's grant for someone else does not help
        grantAccess(otherCaregiver, otherPatient, null);
        assertEquals(403, doGet(teamUrl(patient), otherCaregiver).getResponse().getStatus());

        MvcResult unknown = doGet("/api/patients/PAT-99999/care-team", admin);
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("Patient not found", message(unknown));
        assertEquals(403, doGet("/api/patients/PAT-99999/care-team", doctor).getResponse().getStatus());

        for (User notPatient : List.of(doctor, caregiver, admin)) {
            MvcResult me = doGet("/api/patients/me/care-team", notPatient);
            assertEquals(403, me.getResponse().getStatus());
            assertEquals(ROLE_DENIED, message(me));
        }
        assertEquals(401, mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(teamUrl(patient))).andReturn().getResponse().getStatus());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND patient_system_id = ?", Integer.class, patient.getSystemId()) > 0, "denials are audited");
    }

    @Test
    void list_derivedDoctorsFromAppointmentsAndConsultations_afterAssignedMembers() throws Exception {
        User third = createAndSaveUser(8, "DOC-00003", "third.doc@example.com", "DOCTOR");
        User inactive = createAndSaveUser(9, "DOC-00004", "inactive.doc@example.com", "DOCTOR");
        appointment(otherDoctor, patient, "COMPLETED");
        appointment(third, patient, "SCHEDULED");
        appointment(inactive, patient, "SCHEDULED");
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", inactive.getSystemId());
        User cancelledOnly = createAndSaveUser(10, "DOC-00005", "cancelled.doc@example.com", "DOCTOR");
        appointment(cancelledOnly, patient, "CANCELLED");
        // the assigned doctor also has an appointment: listed once, as ASSIGNED
        appointment(doctor, patient, "COMPLETED");

        JsonNode members = list(patient, patient);
        assertEquals(3, members.size(), members.toString());
        assertEquals("ASSIGNED", members.get(0).path("source").asText());
        assertEquals(doctor.getSystemId(), members.get(0).path("providerSystemId").asText());
        // DERIVED ordered by name: "DOCTOR User5" (DOC-00002) before "DOCTOR User8" (DOC-00003)
        assertEquals(otherDoctor.getSystemId(), members.get(1).path("providerSystemId").asText());
        assertEquals(third.getSystemId(), members.get(2).path("providerSystemId").asText());
        for (int i = 1; i < 3; i++) {
            JsonNode derived = members.get(i);
            assertEquals("DERIVED", derived.path("source").asText());
            assertTrue(derived.path("careTeamId").isNull());
            assertEquals("DOCTOR", derived.path("role").asText());
            assertFalse(derived.path("isPrimary").asBoolean());
            assertTrue(derived.path("assignedAt").isNull());
            assertTrue(derived.path("facilityId").isNull());
        }
        assertNotNull(members.get(1).path("specialization").asText());
    }

    // ==================== ASSIGN ====================

    @Test
    void assign_doctorAddsSpecialist_withFacility_notifiesPatient_andGrantsAccess() throws Exception {
        int facilityId = facility("Avondale Medical Centre", true);
        assertEquals(403, doGet(teamUrl(patient), otherDoctor).getResponse().getStatus(), "no access before");

        Map<String, Object> body = doctorBody(otherDoctor, "specialist");
        body.put("facilityId", facilityId);
        JsonNode created = assignOk(patient, doctor, body);
        assertEquals(MEMBER_FIELDS, fieldNames(created));
        int id = created.path("careTeamId").asInt();
        assertEquals("ASSIGNED", created.path("source").asText());
        assertEquals("SPECIALIST", created.path("role").asText(), "role is upper-cased");
        assertFalse(created.path("isPrimary").asBoolean());
        assertEquals(otherDoctor.getSystemId(), created.path("providerSystemId").asText());
        assertEquals("DOCTOR User5", created.path("providerName").asText());
        assertEquals(facilityId, created.path("facilityId").asInt());
        assertEquals("Avondale Medical Centre", created.path("facilityName").asText());
        assertEquals("PATIENT User1", created.path("patientName").asText());

        assertEquals("SPECIALIST", jdbc.queryForObject("SELECT role FROM care_team WHERE care_team_id = ?", String.class, id));
        assertEquals(1, auditRows("CREATE", id, patient.getSystemId()));

        // the specialist now reaches the patient's data
        assertEquals(200, doGet(teamUrl(patient), otherDoctor).getResponse().getStatus());

        // one notification, to the patient only
        Map<String, Object> note = jdbc.queryForMap("SELECT system_id, type, category, target, title, message, action_url, "
                + "resource_type, resource_id, actor_system_id FROM notifications WHERE dedupe_key = ?", "CARE_TEAM_ASSIGNED:" + id);
        assertEquals(patient.getSystemId(), note.get("system_id"));
        assertEquals("SYSTEM", note.get("type"));
        assertEquals("ACCESS_CHANGE", note.get("category"));
        assertEquals("CARE_TEAM", note.get("target"));
        assertEquals("Care team updated", note.get("title"));
        assertEquals("Dr. DOCTOR User5 was added to your care team as Specialist.", note.get("message"));
        assertEquals("/dashboard/patient?section=care-team&id=" + id, note.get("action_url"));
        assertEquals("CARE_TEAM", note.get("resource_type"));
        assertEquals(id, ((Number) note.get("resource_id")).intValue());
        assertEquals(doctor.getSystemId(), note.get("actor_system_id"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));

        // the doctor's own list shows the new assignment
        MvcResult own = doGet("/api/doctors/care-team", otherDoctor);
        assertEquals(200, own.getResponse().getStatus());
        JsonNode page = json(own);
        assertEquals(2, page.path("totalElements").asInt(), "own patient PAT-00002 plus the new assignment");
        assertEquals(id, page.path("content").get(0).path("careTeamId").asInt(), "newest first");
    }

    @Test
    void assign_duplicateProvider_is409_whateverTheRole() throws Exception {
        MvcResult duplicate = doPost(teamUrl(patient), admin, doctorBody(doctor, "SPECIALIST"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals(DUPLICATE, message(duplicate), "the legacy fixture row counts");

        assignOk(patient, doctor, doctorBody(otherDoctor, "DOCTOR"));
        MvcResult again = doPost(teamUrl(patient), doctor, doctorBody(otherDoctor, "DOCTOR"));
        assertEquals(409, again.getResponse().getStatus());
        assertEquals(DUPLICATE, message(again));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? "
                + "AND provider_system_id = ? AND is_active", Integer.class, patient.getSystemId(), otherDoctor.getSystemId()));
    }

    @Test
    void assign_externalProviderByName() throws Exception {
        Map<String, Object> body = externalBody("  Greenwood Pharmacy ", "PHARMACY");
        body.put("providerPhone", "+263 242 700 123");
        body.put("providerOrganisation", "Greenwood Health Group");
        JsonNode created = assignOk(patient, admin, body);
        assertTrue(created.path("providerSystemId").isNull());
        assertEquals("Greenwood Pharmacy", created.path("providerName").asText(), "trimmed");
        assertEquals("+263 242 700 123", created.path("providerPhone").asText());
        assertEquals("Greenwood Health Group", created.path("providerOrganisation").asText());
        assertEquals("PHARMACY", created.path("role").asText());
        assertTrue(created.path("specialization").isNull());
        assertEquals("Greenwood Pharmacy was added to your care team as Pharmacy.",
                jdbc.queryForObject("SELECT message FROM notifications WHERE system_id = ?", String.class, patient.getSystemId()));

        // same name (any case) and role again: 409; same name with another role is a different member
        MvcResult duplicate = doPost(teamUrl(patient), admin, externalBody("greenwood pharmacy", "PHARMACY"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals(DUPLICATE, message(duplicate));
        assignOk(patient, admin, externalBody("Greenwood Pharmacy", "LAB"));

        // a related doctor may add any of the six roles for an external provider
        for (String role : List.of("NURSE", "THERAPIST", "DOCTOR", "SPECIALIST")) {
            assignOk(patient, doctor, externalBody("External " + role, role));
        }
        JsonNode members = list(patient, patient);
        assertEquals(7, members.size());
        assertNotNull(find(members, "providerName", "External THERAPIST"));
        // a DB-level guarantee: an external row without a name cannot exist
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE provider_system_id IS NULL "
                + "AND (provider_name IS NULL OR btrim(provider_name) = '')", Integer.class));
    }

    @Test
    void assign_roleMatrix() throws Exception {
        Map<String, Object> body = externalBody("Hope Nursing", "NURSE");
        for (User refused : List.of(patient, caregiver)) {
            if (refused == caregiver) {
                grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
            }
            MvcResult result = doPost(teamUrl(patient), refused, body);
            assertEquals(403, result.getResponse().getStatus());
            assertEquals(ROLE_DENIED, message(result));
        }
        MvcResult unrelated = doPost(teamUrl(patient), otherDoctor, body);
        assertEquals(403, unrelated.getResponse().getStatus());
        assertEquals(NOT_ON_TEAM, message(unrelated));

        MvcResult unknown = doPost("/api/patients/PAT-99999/care-team", admin, body);
        assertEquals(404, unknown.getResponse().getStatus());
        assertEquals("Patient not found", message(unknown));

        // a doctor related only through an appointment may assign, an admin always may
        appointment(otherDoctor, patient, "SCHEDULED");
        assignOk(patient, otherDoctor, body);
        assignOk(patient, admin, externalBody("Hope Labs", "LAB"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE provider_name = 'Hope Nursing' "
                + "AND patient_system_id = ? AND is_active", Integer.class, patient.getSystemId()));
    }

    @Test
    void assign_validationAndBusinessRules_return400() throws Exception {
        String url = teamUrl(patient);

        MvcResult noRole = doPost(url, doctor, Map.of("providerName", "X Clinic"));
        assertEquals(400, noRole.getResponse().getStatus());
        assertEquals("role: is required", message(noRole));
        assertEquals("VALIDATION_FAILED", json(noRole).path("code").asText());
        assertEquals("role", json(noRole).path("errors").get(0).path("field").asText());

        for (String role : List.of("PRIMARY_DOCTOR", "CAREGIVER", "SURGEON")) {
            MvcResult bad = doPost(url, doctor, externalBody("X Clinic", role));
            assertEquals(400, bad.getResponse().getStatus());
            assertEquals("role: must be one of DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST, THERAPIST", message(bad));
        }

        MvcResult badId = doPost(url, doctor, doctorBody(otherDoctor, "DOCTOR"));
        assertEquals(201, badId.getResponse().getStatus(), "sanity: a valid body is accepted");
        Map<String, Object> malformedId = new HashMap<>(Map.of("providerSystemId", "doctor-2", "role", "DOCTOR"));
        assertEquals("providerSystemId: must be a valid system id", message(doPost(url, doctor, malformedId)));

        Map<String, Object> longName = externalBody("x".repeat(151), "LAB");
        assertEquals("providerName: must be at most 150 characters", message(doPost(url, doctor, longName)));

        Map<String, Object> badPhone = externalBody("Phone Lab", "LAB");
        badPhone.put("providerPhone", "call me");
        assertEquals("providerPhone: must be a valid phone number", message(doPost(url, doctor, badPhone)));
        badPhone.put("providerPhone", "+263 242 700 123 4567 89");
        MvcResult longPhone = doPost(url, doctor, badPhone);
        assertEquals("providerPhone: must be a valid phone number", message(longPhone));
        assertEquals(1, json(longPhone).path("errors").size(), "one error for a phone that is too long");

        Map<String, Object> longOrg = externalBody("Org Lab", "LAB");
        longOrg.put("providerOrganisation", "o".repeat(201));
        assertEquals("providerOrganisation: must be at most 200 characters", message(doPost(url, doctor, longOrg)));

        Map<String, Object> badFacility = externalBody("Facility Lab", "LAB");
        badFacility.put("facilityId", 0);
        assertEquals("facilityId: must be a positive number", message(doPost(url, doctor, badFacility)));

        MvcResult notBoolean = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(url).header("Authorization", tokenFor(doctor)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerName\":\"Bool Lab\",\"role\":\"LAB\",\"isPrimary\":\"yes\"}")).andReturn();
        assertEquals(400, notBoolean.getResponse().getStatus());
        assertEquals("Malformed request body", message(notBoolean));

        // business rules (plain sentences, no errors list)
        MvcResult neither = doPost(url, doctor, Map.of("role", "LAB", "providerName", "   "));
        assertEquals(400, neither.getResponse().getStatus());
        assertEquals("Provide providerSystemId or providerName", message(neither));
        assertTrue(json(neither).path("errors").isMissingNode());

        Map<String, Object> both = doctorBody(otherDoctor, "DOCTOR");
        both.put("providerOrganisation", "Somewhere");
        assertEquals("Provide either providerSystemId or the external provider fields, not both",
                message(doPost(url, doctor, both)));

        User pending = createAndSaveUser(8, "DOC-00003", "pending.doc@example.com", "DOCTOR");
        jdbc.update("UPDATE doctors SET verification_status = 'PENDING' WHERE system_id = ?", pending.getSystemId());
        assertEquals("The selected provider is not an active, approved doctor",
                message(doPost(url, doctor, doctorBody(pending, "DOCTOR"))));
        assertEquals("The selected provider is not an active, approved doctor",
                message(doPost(url, doctor, doctorBody(caregiver, "DOCTOR"))), "a caregiver account is not a doctor");
        Map<String, Object> unknownDoctor = new HashMap<>(Map.of("providerSystemId", "DOC-99999", "role", "DOCTOR"));
        assertEquals("The selected provider is not an active, approved doctor", message(doPost(url, doctor, unknownDoctor)));
        User deactivated = createAndSaveUser(9, "DOC-00004", "off.doc@example.com", "DOCTOR");
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", deactivated.getSystemId());
        assertEquals("The selected provider is not an active, approved doctor",
                message(doPost(url, doctor, doctorBody(deactivated, "SPECIALIST"))));

        User fresh = createAndSaveUser(10, "DOC-00005", "fresh.doc@example.com", "DOCTOR");
        MvcResult nurseDoctor = doPost(url, doctor, doctorBody(fresh, "NURSE"));
        assertEquals(400, nurseDoctor.getResponse().getStatus());
        assertEquals("A CareNexus doctor can only be assigned as DOCTOR or SPECIALIST", message(nurseDoctor));

        int closed = facility("Closed Clinic", false);
        Map<String, Object> inactiveFacility = doctorBody(fresh, "DOCTOR");
        inactiveFacility.put("facilityId", closed);
        assertEquals("The selected facility does not exist or is inactive", message(doPost(url, doctor, inactiveFacility)));
        inactiveFacility.put("facilityId", 987654);
        assertEquals("The selected facility does not exist or is inactive", message(doPost(url, doctor, inactiveFacility)));

        MvcResult badKey = doPostWithHeaders(url, doctor, doctorBody(fresh, "DOCTOR"), Map.of("Idempotency-Key", "short"));
        assertEquals(400, badKey.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));

        // nothing above was written except the sanity row
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM care_team", Integer.class));
    }

    @Test
    void assign_replayWithSameIdempotencyKey_returns200_andWritesNothing() throws Exception {
        String key = "care-team-" + clientId().substring(0, 8);
        Map<String, Object> body = doctorBody(otherDoctor, "DOCTOR");
        MvcResult first = doPostWithHeaders(teamUrl(patient), doctor, body, Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        MvcResult replay = doPostWithHeaders(teamUrl(patient), doctor, body, Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(json(first), json(replay));
        int id = json(first).path("careTeamId").asInt();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE provider_system_id = ? "
                + "AND patient_system_id = ?", Integer.class, otherDoctor.getSystemId(), patient.getSystemId()));
        assertEquals(1, auditRows("CREATE", id, patient.getSystemId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));

        // the same key for another patient is not a replay of this patient's row
        MvcResult otherPatientCall = doPostWithHeaders(teamUrl(otherPatient), admin, externalBody("Lab A", "LAB"),
                Map.of("Idempotency-Key", key));
        assertEquals(201, otherPatientCall.getResponse().getStatus());
        assertEquals(otherPatient.getSystemId(), json(otherPatientCall).path("patientSystemId").asText());
    }

    // ==================== PRIMARY ====================

    @Test
    void primary_swapOnAssignAndPatch_keepsOnePrimary_andNormalisesTheLegacyRow() throws Exception {
        int legacyId = fixtureRowId(patient);
        Map<String, Object> body = doctorBody(otherDoctor, "DOCTOR");
        body.put("isPrimary", true);
        JsonNode created = assignOk(patient, doctor, body);
        int newId = created.path("careTeamId").asInt();
        assertTrue(created.path("isPrimary").asBoolean());

        // the legacy PRIMARY_DOCTOR row was demoted and rewritten to DOCTOR (it keeps granting access)
        assertEquals("DOCTOR", jdbc.queryForObject("SELECT role FROM care_team WHERE care_team_id = ?", String.class, legacyId));
        assertEquals(1, activePrimaryRows(patient));
        assertEquals(1, auditRows("UPDATE", legacyId, patient.getSystemId()));
        JsonNode members = list(patient, patient);
        assertEquals(newId, members.get(0).path("careTeamId").asInt(), "primary first");
        assertFalse(find(members, "careTeamId", String.valueOf(legacyId)).path("isPrimary").asBoolean());
        assertEquals(200, doGet(teamUrl(patient), doctor).getResponse().getStatus());

        // the patient swaps the primary back
        MvcResult swap = doPatch(memberUrl(patient, legacyId), patient, Map.of("isPrimary", true));
        assertEquals(200, swap.getResponse().getStatus(), swap.getResponse().getContentAsString());
        assertTrue(json(swap).path("isPrimary").asBoolean());
        assertEquals(1, activePrimaryRows(patient));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_primary FROM care_team WHERE care_team_id = ?",
                Boolean.class, legacyId));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_primary FROM care_team WHERE care_team_id = ?",
                Boolean.class, newId));
        assertEquals(1, auditRows("UPDATE", newId, patient.getSystemId()));

        // no-op: same value again writes nothing
        int updatesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE'", Integer.class);
        MvcResult same = doPatch(memberUrl(patient, legacyId), patient, Map.of("isPrimary", true));
        assertEquals(200, same.getResponse().getStatus());
        assertEquals(updatesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE'", Integer.class));

        // clearing the primary leaves the patient without one
        assertEquals(200, doPatch(memberUrl(patient, legacyId), patient, Map.of("isPrimary", false)).getResponse().getStatus());
        assertEquals(0, activePrimaryRows(patient));
        for (JsonNode member : list(patient, patient)) {
            assertFalse(member.path("isPrimary").asBoolean());
        }

        // an external provider may be primary too
        Map<String, Object> external = externalBody("Primary Nurse", "NURSE");
        external.put("isPrimary", true);
        assignOk(patient, admin, external);
        assertEquals(1, activePrimaryRows(patient));
    }

    @Test
    void patch_legacyRowWithRoleChange_isRewrittenAndStaysPrimary() throws Exception {
        int legacyId = fixtureRowId(patient);
        MvcResult result = doPatch(memberUrl(patient, legacyId), doctor, Map.of("role", "specialist"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("SPECIALIST", json(result).path("role").asText());
        assertTrue(json(result).path("isPrimary").asBoolean());
        assertEquals("SPECIALIST", jdbc.queryForObject("SELECT role FROM care_team WHERE care_team_id = ?", String.class, legacyId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT is_primary FROM care_team WHERE care_team_id = ?",
                Boolean.class, legacyId));
        assertEquals(1, auditRows("UPDATE", legacyId, patient.getSystemId()));
        // SPECIALIST still grants access
        assertEquals(200, doGet(teamUrl(patient), doctor).getResponse().getStatus());
    }

    @Test
    void patch_roleMatrixAndRules() throws Exception {
        int legacyId = fixtureRowId(patient);
        int externalId = assignOk(patient, admin, externalBody("Rule Pharmacy", "PHARMACY")).path("careTeamId").asInt();

        MvcResult patientRole = doPatch(memberUrl(patient, externalId), patient, Map.of("role", "LAB"));
        assertEquals(403, patientRole.getResponse().getStatus());
        assertEquals("Patients can only change the primary care team member", message(patientRole));

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        MvcResult caregiverCall = doPatch(memberUrl(patient, externalId), caregiver, Map.of("isPrimary", true));
        assertEquals(403, caregiverCall.getResponse().getStatus());
        assertEquals(ROLE_DENIED, message(caregiverCall));

        MvcResult otherPatientCall = doPatch(memberUrl(patient, externalId), otherPatient, Map.of("isPrimary", true));
        assertEquals(403, otherPatientCall.getResponse().getStatus());
        assertEquals(OWN_DATA, message(otherPatientCall));

        MvcResult unrelated = doPatch(memberUrl(patient, externalId), otherDoctor, Map.of("role", "LAB"));
        assertEquals(403, unrelated.getResponse().getStatus());
        assertEquals(NOT_ON_TEAM, message(unrelated));

        MvcResult empty = doPatch(memberUrl(patient, externalId), doctor, Map.of());
        assertEquals(400, empty.getResponse().getStatus());
        assertEquals("Provide role or isPrimary", message(empty));

        MvcResult noBody = mockMvc.perform(patch(memberUrl(patient, externalId)).header("Authorization", tokenFor(doctor))
                .contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertEquals(400, noBody.getResponse().getStatus());
        assertEquals("Malformed request body", message(noBody));

        MvcResult badRole = doPatch(memberUrl(patient, externalId), doctor, Map.of("role", "PRIMARY_DOCTOR"));
        assertEquals(400, badRole.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(badRole).path("code").asText());

        MvcResult doctorAsNurse = doPatch(memberUrl(patient, legacyId), doctor, Map.of("role", "NURSE"));
        assertEquals(400, doctorAsNurse.getResponse().getStatus());
        assertEquals("A CareNexus doctor can only be assigned as DOCTOR or SPECIALIST", message(doctorAsNurse));

        MvcResult badId = mockMvc.perform(patch(teamUrl(patient) + "/abc").header("Authorization", tokenFor(doctor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isPrimary\":true}")).andReturn();
        assertEquals(400, badId.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'careTeamId'", message(badId));

        // 404: unknown id, a row of another patient, a removed row, a legacy caregiver row
        assertEquals(404, doPatch(memberUrl(patient, 987654), admin, Map.of("isPrimary", true)).getResponse().getStatus());
        int otherRow = fixtureRowId(otherPatient);
        MvcResult foreign = doPatch(memberUrl(patient, otherRow), admin, Map.of("isPrimary", true));
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals("Care team member not found", message(foreign));
        CareTeam caregiverRow = careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId()).providerSystemId(caregiver.getSystemId())
                .role("CAREGIVER").assignedAt(LocalDateTime.now()).isActive(true).build());
        assertEquals(404, doPatch(memberUrl(patient, caregiverRow.getCareTeamId()), admin, Map.of("isPrimary", true))
                .getResponse().getStatus());
        assertEquals(404, doDelete(memberUrl(patient, caregiverRow.getCareTeamId()), admin).getResponse().getStatus());
        assertNull(find(list(patient, admin), "providerSystemId", caregiver.getSystemId()),
                "legacy caregiver rows are invisible");

        // external role change by the doctor and the admin
        MvcResult changed = doPatch(memberUrl(patient, externalId), doctor, Map.of("role", "LAB", "isPrimary", false));
        assertEquals(200, changed.getResponse().getStatus());
        assertEquals("LAB", json(changed).path("role").asText());
        assertEquals(200, doPatch(memberUrl(patient, externalId), admin, Map.of("role", "THERAPIST")).getResponse().getStatus());
    }

    // ==================== REMOVE ====================

    @Test
    void remove_byAssignedDoctor_endsAccess_andIsIdempotent() throws Exception {
        int id = assignOk(patient, doctor, doctorBody(otherDoctor, "SPECIALIST")).path("careTeamId").asInt();
        assertEquals(200, doGet(teamUrl(patient), otherDoctor).getResponse().getStatus());

        // another doctor (even the related one) cannot remove it; a caregiver never
        MvcResult notProvider = doDelete(memberUrl(patient, id), doctor);
        assertEquals(403, notProvider.getResponse().getStatus());
        assertEquals("Only the patient, the assigned provider or an administrator can remove a care team member",
                message(notProvider));
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        MvcResult caregiverCall = doDelete(memberUrl(patient, id), caregiver);
        assertEquals(403, caregiverCall.getResponse().getStatus());
        assertEquals(ROLE_DENIED, message(caregiverCall));
        MvcResult otherPatientCall = doDelete(memberUrl(patient, id), otherPatient);
        assertEquals(403, otherPatientCall.getResponse().getStatus());
        assertEquals(OWN_DATA, message(otherPatientCall));
        assertEquals(404, doDelete(memberUrl(patient, 987654), otherDoctor).getResponse().getStatus());

        assertEquals(204, doDelete(memberUrl(patient, id), otherDoctor).getResponse().getStatus());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT is_active FROM care_team WHERE care_team_id = ?",
                Boolean.class, id));
        assertNotNull(jdbc.queryForObject("SELECT unassigned_at FROM care_team WHERE care_team_id = ?",
                LocalDateTime.class, id));
        assertEquals(1, auditRows("DELETE", id, patient.getSystemId()));

        // no appointment or consultation: access ends at once
        MvcResult after = doGet(teamUrl(patient), otherDoctor);
        assertEquals(403, after.getResponse().getStatus());
        assertEquals(NOT_ON_TEAM, message(after));

        // repeating is a no-op 204 (admin; the patient)
        assertEquals(204, doDelete(memberUrl(patient, id), admin).getResponse().getStatus());
        assertEquals(204, doDelete(memberUrl(patient, id), patient).getResponse().getStatus());
        assertEquals(1, auditRows("DELETE", id, patient.getSystemId()));
        assertEquals(404, doPatch(memberUrl(patient, id), admin, Map.of("isPrimary", true)).getResponse().getStatus(),
                "a removed member cannot be patched");

        // a new assignment creates a new row
        int again = assignOk(patient, doctor, doctorBody(otherDoctor, "DOCTOR")).path("careTeamId").asInt();
        assertTrue(again != id);
    }

    @Test
    void remove_keepsAppointmentDerivedAccess_andTheDoctorReappearsAsDerived() throws Exception {
        int legacyId = fixtureRowId(patient);
        appointment(doctor, patient, "COMPLETED");

        assertEquals(204, doDelete(memberUrl(patient, legacyId), patient).getResponse().getStatus());
        assertEquals(1, auditRows("DELETE", legacyId, patient.getSystemId()));

        // still related through the appointment
        JsonNode members = list(patient, doctor);
        assertEquals(1, members.size());
        assertEquals("DERIVED", members.get(0).path("source").asText());
        assertEquals(doctor.getSystemId(), members.get(0).path("providerSystemId").asText());
        assertTrue(members.get(0).path("careTeamId").isNull());
        assertFalse(members.get(0).path("isPrimary").asBoolean(), "removing the primary leaves no primary");

        // the doctor may assign themselves again (related doctor)
        assignOk(patient, doctor, doctorBody(doctor, "DOCTOR"));
        assertEquals("ASSIGNED", list(patient, patient).get(0).path("source").asText());

        // an external member can be removed by the patient and the admin only
        int externalId = assignOk(patient, admin, externalBody("Remove Me Lab", "LAB")).path("careTeamId").asInt();
        assertEquals(403, doDelete(memberUrl(patient, externalId), doctor).getResponse().getStatus());
        assertEquals(204, doDelete(memberUrl(patient, externalId), admin).getResponse().getStatus());
    }

    // ==================== DOCTOR LIST ====================

    @Test
    void doctorList_pagedOwnAssignments_doctorOnly() throws Exception {
        // five more patients for the doctor
        List<User> more = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User p = createAndSaveUser(20 + i, String.format("PAT-%05d", 10 + i), "p" + i + "@example.com", "PATIENT");
            more.add(p);
            appointment(doctor, p, "SCHEDULED");
            assignOk(p, doctor, doctorBody(doctor, "DOCTOR"));
        }
        MvcResult page0 = doGet("/api/doctors/care-team?page=0&size=2", doctor);
        assertEquals(200, page0.getResponse().getStatus());
        JsonNode body = json(page0);
        assertEquals(List.of("content", "page", "size", "totalElements", "totalPages"), fieldNames(body));
        assertEquals(6, body.path("totalElements").asInt());
        assertEquals(3, body.path("totalPages").asInt());
        assertEquals(2, body.path("size").asInt());
        assertEquals(2, body.path("content").size());
        assertEquals(more.get(4).getSystemId(), body.path("content").get(0).path("patientSystemId").asText(), "newest first");
        for (JsonNode row : body.path("content")) {
            assertEquals("ASSIGNED", row.path("source").asText());
            assertEquals(doctor.getSystemId(), row.path("providerSystemId").asText());
        }
        assertEquals(1, auditRows("READ", null, more.get(4).getSystemId()));
        assertEquals(1, auditRows("READ", null, more.get(3).getSystemId()));

        JsonNode clamped = json(doGet("/api/doctors/care-team?page=-3&size=500", doctor));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(6, clamped.path("content").size());
        assertEquals("DOCTOR", find(clamped.path("content"), "patientSystemId", patient.getSystemId()).path("role").asText(),
                "the legacy row reads as DOCTOR");

        MvcResult badSize = doGet("/api/doctors/care-team?size=lots", doctor);
        assertEquals(400, badSize.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'size'", message(badSize));

        MvcResult adminCall = doGet("/api/doctors/care-team", admin);
        assertEquals(403, adminCall.getResponse().getStatus());
        assertEquals(ROLE_DENIED, message(adminCall));
        for (User refused : List.of(patient, caregiver)) {
            MvcResult result = doGet("/api/doctors/care-team", refused);
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("You don't have permission to access this resource", message(result));
        }

        JsonNode empty = json(doGet("/api/doctors/care-team", createAndSaveUser(30, "DOC-00009", "lonely@example.com", "DOCTOR")));
        assertEquals(0, empty.path("totalElements").asInt());
        assertEquals(0, empty.path("content").size());
    }
}
