package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Release gates for be-caregiver-core: managed dependents (no sign-in details, guardian attestation, shared phone,
 * invitation), atomic and idempotent creation, flag normalisation, one consent-ledger event per grant change, the
 * windowed and paged activity feed, role refusals, the deprecated patient routes and the list audit rows.
 * Committed transactions (CommittedIntegrationTest): listeners and rollbacks behave as in production.
 */
@RecordApplicationEvents
class CaregiverCoreReleaseGateTest extends CommittedIntegrationTest {

    private static final String DEPENDENTS = "/api/caregiver/dependents";
    private static final String ACTIVITY = "/api/caregiver/dependents/activity";
    private static final String ACCESS = "/api/patients/me/caregiver-access";
    private static final String LEGACY = "/api/patients/me/caregivers";
    private static final String STRONG_PASSWORD = "Str0ngPassw0rd!";

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    // ==================== HELPERS ====================

    private Map<String, Object> managedBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Rudo");
        body.put("lastName", "Moyo");
        body.put("relationship", "mother");
        body.put("guardianAttested", true);
        body.put("dateOfBirth", "1948-03-02");
        body.put("gender", "female");
        body.put("bloodType", "o+");
        body.put("accessLevel", "FULL_ACCESS");
        return body;
    }

    private Map<String, Object> signInBody(String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", "Grace");
        body.put("lastName", "Dube");
        body.put("email", email);
        body.put("password", STRONG_PASSWORD);
        body.put("relationship", "mother");
        return body;
    }

    private JsonNode created(MvcResult result) throws Exception {
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<CaregiverGrantRecordedEvent> ledger() {
        return applicationEvents.stream(CaregiverGrantRecordedEvent.class).toList();
    }

    private List<String> ledgerKinds() {
        return ledger().stream().map(CaregiverGrantRecordedEvent::kind).toList();
    }

    private void assert403(MvcResult result, String what) throws Exception {
        assertEquals(403, result.getResponse().getStatus(), what + ": " + result.getResponse().getContentAsString());
    }

    private Appointment appointment(User forPatient, LocalDateTime at, String status) {
        return appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId()).doctorSystemId(doctor.getSystemId())
                .scheduledAt(at.withSecond(0).withNano(0)).type("IN_PERSON").status(status)
                .reasonForVisit("Review").build());
    }

    private Consultation consultation(Appointment appointment, String status, LocalDateTime createdAt) {
        Consultation saved = consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(appointment.getPatientSystemId())
                .doctorSystemId(appointment.getDoctorSystemId()).chiefComplaint("Headache").status(status).build());
        jdbc.update("UPDATE consultations SET created_at = ? WHERE consultation_id = ?", createdAt,
                saved.getConsultationId());
        return saved;
    }

    private List<Integer> ids(JsonNode rows, String field) {
        List<Integer> ids = new ArrayList<>();
        rows.forEach(r -> ids.add(r.path(field).asInt()));
        return ids;
    }

    // ==================== ROLES ====================

    @Test
    void everyCaregiverRoute_refusesOtherRoles() throws Exception {
        for (User user : List.of(patient, doctor, admin)) {
            String role = user.getRole();
            assert403(doGet(DEPENDENTS, user), role + " GET dependents");
            assert403(doGet(ACTIVITY, user), role + " GET activity");
            assert403(doGet(ACTIVITY + "?page=0&size=20", user), role + " GET activity paged");
            assert403(doPost(DEPENDENTS, user, managedBody()), role + " POST dependents");
            assert403(doPatch(DEPENDENTS + "/1", user, Map.of("canViewRecords", false)), role + " PATCH dependent");
            assert403(doPost(DEPENDENTS + "/1/invite", user, Map.of("email", "x@example.com")), role + " POST invite");
        }
        assertEquals(0, count("dependent_access"));
        // The patient-side list is the patient's only: an administrator is refused by the controller.
        assert403(doGet(ACCESS, admin), "ADMIN GET caregiver-access");
        assert403(doGet(ACCESS, caregiver), "CAREGIVER GET caregiver-access");
    }

    // ==================== CREATE: MANAGED, SIGN-IN, ROLLBACK, IDEMPOTENCY ====================

    @Test
    void managedDependent_isCreatedWithAttestation_andCannotSignIn() throws Exception {
        JsonNode body = created(doPost(DEPENDENTS, caregiver, managedBody()));
        assertEquals("PAT-00003", body.path("patientSystemId").asText());
        assertEquals(caregiver.getSystemId(), body.path("authorizedBySystemId").asText());
        assertEquals("ACTIVE", body.path("accessStatus").asText());
        assertTrue(body.path("managed").asBoolean(), body.toString());
        assertTrue(body.path("canInvite").asBoolean(), body.toString());
        assertFalse(body.path("guardianAttestedAt").isNull(), body.toString());
        assertTrue(body.path("patientEmail").isNull());
        assertEquals("Rudo Moyo", body.path("patientName").asText());
        assertEquals("FEMALE", body.path("patientGender").asText());
        assertEquals("O+", body.path("patientBloodType").asText());
        assertEquals("STABLE", body.path("patientHealthStatus").asText());

        Map<String, Object> user = jdbc.queryForMap(
                "SELECT email, password_hash, role, is_active FROM users WHERE system_id = 'PAT-00003'");
        assertNull(user.get("email"));
        assertNull(user.get("password_hash"));
        assertEquals("PATIENT", user.get("role"));
        assertNotNull(jdbc.queryForObject("SELECT guardian_attested_at FROM dependent_access WHERE patient_system_id = 'PAT-00003'",
                java.sql.Timestamp.class));

        // UserRegisteredEvent names the caregiver as creator; one GRANTED ledger event carries the attestation.
        UserRegisteredEvent registered = applicationEvents.stream(UserRegisteredEvent.class).findFirst().orElseThrow();
        assertEquals(caregiver.getSystemId(), registered.createdBySystemId());
        assertEquals(List.of(new CaregiverGrantRecordedEvent(body.path("accessId").asInt(), "PAT-00003",
                caregiver.getSystemId(), caregiver.getSystemId(), CaregiverGrantRecordedEvent.GRANTED, true)), ledger());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'CREATE' "
                + "AND resource_type = 'DEPENDENT_ACCESS' AND patient_system_id = 'PAT-00003'", Integer.class));

        // Invited (email stored) but no password yet: sign-in is refused like any wrong password.
        MvcResult invite = doPost(DEPENDENTS + "/" + body.path("accessId").asInt() + "/invite", caregiver,
                Map.of("email", "  Rudo.Moyo@Example.com "));
        assertEquals(200, invite.getResponse().getStatus(), invite.getResponse().getContentAsString());
        MvcResult login = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/auth/login").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "rudo.moyo@example.com", "password", STRONG_PASSWORD))))
                .andReturn();
        assertEquals(401, login.getResponse().getStatus(), login.getResponse().getContentAsString());
    }

    @Test
    void attestation_isRequiredForAManagedDependent_andOptionalWithSignInDetails() throws Exception {
        Map<String, Object> unattested = managedBody();
        unattested.remove("guardianAttested");
        MvcResult refused = doPost(DEPENDENTS, caregiver, unattested);
        assertEquals(400, refused.getResponse().getStatus());
        assertEquals("Confirm that you are this person's parent or legal guardian", message(refused));
        unattested.put("guardianAttested", false);
        assertEquals(400, doPost(DEPENDENTS, caregiver, unattested).getResponse().getStatus());

        // Exactly one of email and password is a 400 (blank counts as absent).
        Map<String, Object> emailOnly = managedBody();
        emailOnly.put("email", "half@example.com");
        emailOnly.put("password", "   ");
        MvcResult half = doPost(DEPENDENTS, caregiver, emailOnly);
        assertEquals(400, half.getResponse().getStatus());
        assertEquals("Email and password must be provided together, or both left empty", message(half));
        assertEquals(0, count("dependent_access"));

        // With sign-in details (the native form) attestation is not needed and nothing is recorded.
        JsonNode withLogin = created(doPost(DEPENDENTS, caregiver, signInBody("grace.dube@example.com")));
        assertFalse(withLogin.path("managed").asBoolean());
        assertFalse(withLogin.path("canInvite").asBoolean());
        assertTrue(withLogin.path("guardianAttestedAt").isNull());
        assertEquals("grace.dube@example.com", withLogin.path("patientEmail").asText());
        // ... and recorded when sent.
        Map<String, Object> attested = signInBody("ruth.dube@example.com");
        attested.put("guardianAttested", true);
        assertFalse(created(doPost(DEPENDENTS, caregiver, attested)).path("guardianAttestedAt").isNull());
    }

    @Test
    void duplicateEmail_is409_andLeavesNoRows() throws Exception {
        int users = count("users");
        int patients = count("patients");
        int grants = count("dependent_access");

        MvcResult duplicate = doPost(DEPENDENTS, caregiver, signInBody(patient.getEmail().toUpperCase()));
        assertEquals(409, duplicate.getResponse().getStatus(), duplicate.getResponse().getContentAsString());
        assertEquals("Email already registered", message(duplicate));

        assertEquals(users, count("users"));
        assertEquals(patients, count("patients"));
        assertEquals(grants, count("dependent_access"));
        assertTrue(ledger().isEmpty());
    }

    @Test
    void sharedPhone_isAcceptedAsTheContactPhone_andAnUnusedPhoneIsTheAccountPhone() throws Exception {
        Map<String, Object> shared = managedBody();
        shared.put("phone", patient.getPhone());
        JsonNode body = created(doPost(DEPENDENTS, caregiver, shared));
        String dependentId = body.path("patientSystemId").asText();
        assertEquals(patient.getPhone(), body.path("patientPhone").asText());
        assertNull(jdbc.queryForObject("SELECT phone FROM users WHERE system_id = ?", String.class, dependentId));
        assertEquals(patient.getPhone(), jdbc.queryForObject(
                "SELECT contact_phone FROM patients WHERE system_id = ?", String.class, dependentId));

        // A second dependent on the same family number is fine as well.
        Map<String, Object> sibling = managedBody();
        sibling.put("firstName", "Tendai");
        sibling.put("phone", patient.getPhone());
        created(doPost(DEPENDENTS, caregiver, sibling));

        Map<String, Object> own = managedBody();
        own.put("firstName", "Chipo");
        own.put("phone", "+263 77 123 4567");
        JsonNode ownBody = created(doPost(DEPENDENTS, caregiver, own));
        assertEquals("+263 77 123 4567", jdbc.queryForObject("SELECT phone FROM users WHERE system_id = ?",
                String.class, ownBody.path("patientSystemId").asText()));
        assertEquals("+263 77 123 4567", ownBody.path("patientPhone").asText());
    }

    @Test
    void createDependent_validation_usesTheErrorEnvelope() throws Exception {
        Map<String, Object> noName = managedBody();
        noName.put("firstName", "   ");
        MvcResult blank = doPost(DEPENDENTS, caregiver, noName);
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("firstName: First name is required", message(blank));
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());

        Map<String, Object> badPhone = managedBody();
        badPhone.put("phone", "call me");
        assertEquals("phone: Enter a valid phone number", message(doPost(DEPENDENTS, caregiver, badPhone)));

        Map<String, Object> badLevel = managedBody();
        badLevel.put("accessLevel", "SUPERUSER");
        assertEquals("accessLevel: Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM",
                message(doPost(DEPENDENTS, caregiver, badLevel)));

        Map<String, Object> badEmail = signInBody("not-an-email");
        assertEquals("email: Please enter a valid email address", message(doPost(DEPENDENTS, caregiver, badEmail)));

        Map<String, Object> tooMany = managedBody();
        List<String> allergies = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            allergies.add("Allergy " + i);
        }
        tooMany.put("allergies", allergies);
        MvcResult list = doPost(DEPENDENTS, caregiver, tooMany);
        assertEquals(400, list.getResponse().getStatus());
        assertTrue(message(list).startsWith("allergies: "), message(list));

        Map<String, Object> weak = signInBody("weak@example.com");
        weak.put("password", "short");
        MvcResult weakResult = doPost(DEPENDENTS, caregiver, weak);
        assertEquals(400, weakResult.getResponse().getStatus());
        assertEquals("Password must be at least 8 characters and contain at least one letter and one number",
                message(weakResult));

        Map<String, Object> futureBirth = managedBody();
        futureBirth.put("dateOfBirth", LocalDate.now().plusDays(3).toString());
        assertEquals("Date of birth cannot be in the future", message(doPost(DEPENDENTS, caregiver, futureBirth)));

        // healthStatus is ignored: a new patient always starts STABLE.
        Map<String, Object> critical = managedBody();
        critical.put("healthStatus", "CRITICAL");
        String dependentId = created(doPost(DEPENDENTS, caregiver, critical)).path("patientSystemId").asText();
        assertEquals("STABLE", jdbc.queryForObject("SELECT health_status FROM patients WHERE system_id = ?",
                String.class, dependentId));

        // A caregiver account without a caregiver profile cannot add a dependent.
        jdbc.update("DELETE FROM caregivers WHERE system_id = ?", otherCaregiver.getSystemId());
        MvcResult noProfile = doPost(DEPENDENTS, otherCaregiver, managedBody());
        assertEquals(403, noProfile.getResponse().getStatus());
        assertEquals("Only proxy/caregiver accounts can add a dependent", message(noProfile));
    }

    @Test
    void idempotencyKey_replaysTheFirstResponse_andCreatesOnce() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", "add-dependent-0001");
        MvcResult first = doPostWithHeaders(DEPENDENTS, caregiver, managedBody(), headers);
        JsonNode firstBody = created(first);
        MvcResult replay = doPostWithHeaders(DEPENDENTS, caregiver, managedBody(), headers);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(firstBody.path("accessId").asInt(), json(replay).path("accessId").asInt());
        assertEquals(1, count("dependent_access"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE first_name = 'Rudo'", Integer.class));

        MvcResult badKey = doPostWithHeaders(DEPENDENTS, caregiver, managedBody(), Map.of("Idempotency-Key", "bad key!"));
        assertEquals(400, badKey.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));

        // The patient's grant accepts the header too.
        Map<String, String> grantKey = Map.of("Idempotency-Key", "grant-access-0001");
        Map<String, Object> grant = Map.of("caregiverEmail", caregiver.getEmail(), "relationship", "daughter");
        assertEquals(201, doPostWithHeaders(ACCESS, patient, grant, grantKey).getResponse().getStatus());
        assertEquals(200, doPostWithHeaders(ACCESS, patient, grant, grantKey).getResponse().getStatus());
    }

    // ==================== NORMALISATION ====================

    @Test
    void flags_areNormalisedToTheAccessLevel_onEverySave() throws Exception {
        JsonNode full = created(doPost(DEPENDENTS, caregiver, managedBody()));
        for (String flag : List.of("canViewRecords", "canViewAppointments", "canViewMedications", "canViewLabResults",
                "canViewImaging", "canViewConsultations", "canScheduleAppointments", "canManageCarePlan",
                "canViewBilling", "canManageBilling")) {
            assertTrue(full.path(flag).asBoolean(), flag + " stored true under FULL_ACCESS");
        }
        assertFalse(full.path("canMessageCareTeam").asBoolean(), "FULL_ACCESS never implies communication");

        Map<String, Object> viewOnly = managedBody();
        viewOnly.put("firstName", "Viewer");
        viewOnly.put("accessLevel", "VIEW_ONLY");
        viewOnly.put("canViewRecords", true);
        viewOnly.put("canScheduleAppointments", true);
        viewOnly.put("canManageCarePlan", true);
        viewOnly.put("canManageBilling", true);
        JsonNode view = created(doPost(DEPENDENTS, caregiver, viewOnly));
        assertTrue(view.path("canViewRecords").asBoolean());
        assertFalse(view.path("canScheduleAppointments").asBoolean());
        assertFalse(view.path("canManageCarePlan").asBoolean());
        assertFalse(view.path("canManageBilling").asBoolean());

        // Patient grant and patient PATCH normalise too; an ignored flag is not an error.
        MvcResult granted = doPost(ACCESS, patient, Map.of("caregiverEmail", caregiver.getEmail(),
                "relationship", "daughter", "accessLevel", "VIEW_ONLY", "canManageCarePlan", true, "canViewLabResults", true));
        JsonNode grant = created(granted);
        assertFalse(grant.path("canManageCarePlan").asBoolean());
        assertTrue(grant.path("canViewLabResults").asBoolean());
        int accessId = grant.path("accessId").asInt();

        MvcResult toFull = doPatch(ACCESS + "/" + accessId, patient, Map.of("accessLevel", "full_access", "canViewImaging", false));
        assertEquals(200, toFull.getResponse().getStatus(), toFull.getResponse().getContentAsString());
        assertTrue(json(toFull).path("canViewImaging").asBoolean());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT can_manage_billing AND can_view_imaging AND can_manage_care_plan "
                + "FROM dependent_access WHERE access_id = ?", Boolean.class, accessId));

        MvcResult toCustom = doPatch(ACCESS + "/" + accessId, patient, Map.of("accessLevel", "CUSTOM", "canManageBilling", false));
        assertEquals(200, toCustom.getResponse().getStatus());
        assertFalse(json(toCustom).path("canManageBilling").asBoolean());
        assertTrue(json(toCustom).path("canManageCarePlan").asBoolean(), "omitted flags keep their stored value");
    }

    // ==================== CONSENT LEDGER EVENTS ====================

    @Test
    void oneLedgerEventPerChangingRequest_withThePrecedenceOfTheContract() throws Exception {
        MvcResult granted = doPost(ACCESS, patient, Map.of("caregiverEmail", caregiver.getEmail(),
                "relationship", "daughter", "accessLevel", "CUSTOM", "canViewRecords", true, "canViewLabResults", true,
                "expiresAt", LocalDate.now().plusDays(10).toString()));
        int accessId = created(granted).path("accessId").asInt();
        String url = ACCESS + "/" + accessId;

        // A caregiver request (stored by an admin through the deprecated route) waits for the patient.
        assertEquals(200, doPatch(LEGACY + "/" + accessId, admin, Map.of("canJoinCalls", true)).getResponse().getStatus());
        assertEquals(200, doPatch(url, patient, Map.of("canJoinCalls", true,
                "expiresAt", LocalDate.now().plusDays(60).toString())).getResponse().getStatus());
        assertEquals(200, doPatch(url, patient, Map.of("expiresAt", "")).getResponse().getStatus());
        assertEquals(200, doPatch(url, patient, Map.of("canViewImaging", true)).getResponse().getStatus());
        assertEquals(200, doPatch(url, patient, Map.of("canViewImaging", true)).getResponse().getStatus()); // no-op
        assertEquals(200, doPatch(url, patient, Map.of()).getResponse().getStatus());                       // no-op
        assertEquals(200, doPatch(DEPENDENTS + "/" + accessId, caregiver, Map.of("canViewLabResults", false))
                .getResponse().getStatus());
        assertEquals(200, doPatch(DEPENDENTS + "/" + accessId, caregiver, Map.of("canViewLabResults", false))
                .getResponse().getStatus());                                                                    // no-op
        assertEquals(204, doDelete(url, patient).getResponse().getStatus());
        assertEquals(204, doDelete(url, patient).getResponse().getStatus());                                // repeat

        assertEquals(List.of(CaregiverGrantRecordedEvent.GRANTED, CaregiverGrantRecordedEvent.PERMISSIONS_CHANGED,
                        CaregiverGrantRecordedEvent.COMMUNICATION_APPROVED, CaregiverGrantRecordedEvent.EXTENDED,
                        CaregiverGrantRecordedEvent.PERMISSIONS_CHANGED, CaregiverGrantRecordedEvent.NARROWED,
                        CaregiverGrantRecordedEvent.REVOKED),
                ledgerKinds());
        List<String> actors = ledger().stream().map(CaregiverGrantRecordedEvent::actorSystemId).toList();
        assertEquals(List.of(patient.getSystemId(), admin.getSystemId(), patient.getSystemId(), patient.getSystemId(),
                patient.getSystemId(), caregiver.getSystemId(), patient.getSystemId()), actors);
        ledger().forEach(e -> assertFalse(e.guardianAttested()));

        // Re-granting the revoked row is GRANTED again, on the same access id.
        int regranted = created(doPost(ACCESS, patient, Map.of("caregiverEmail", caregiver.getEmail(),
                "relationship", "daughter"))).path("accessId").asInt();
        assertEquals(accessId, regranted);
        assertEquals(CaregiverGrantRecordedEvent.GRANTED, ledgerKinds().get(ledgerKinds().size() - 1));
    }

    @Test
    void reGrantOfARevokedDependent_clearsTheAttestation() throws Exception {
        JsonNode managed = created(doPost(DEPENDENTS, caregiver, managedBody()));
        int accessId = managed.path("accessId").asInt();
        String dependentId = managed.path("patientSystemId").asText();
        // The dependent's own sign-in is not needed to manage grants in this test: revoke as admin.
        assertEquals(204, doDelete(ACCESS + "/" + accessId, admin).getResponse().getStatus());
        assertTrue(ledger().get(ledger().size() - 1).guardianAttested(), "REVOKED still carries the attestation");

        User dependent = userRepository.findBySystemId(dependentId).orElseThrow();
        MvcResult regrant = doPost(ACCESS, dependent, Map.of("caregiverEmail", caregiver.getEmail(), "relationship", "son"));
        JsonNode body = created(regrant);
        assertEquals(accessId, body.path("accessId").asInt());
        assertTrue(body.path("guardianAttestedAt").isNull());
        assertEquals(dependentId, body.path("authorizedBySystemId").asText());
        assertFalse(ledger().get(ledger().size() - 1).guardianAttested());
    }

    // ==================== CAREGIVER PATCH ====================

    @Test
    void caregiverPatch_onlyNarrows_andRefusesExpiredOrForeignGrants() throws Exception {
        DependentAccess grant = grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().plusDays(20));
        });
        String url = DEPENDENTS + "/" + grant.getAccessId();

        MvcResult extend = doPatch(url, caregiver, Map.of("expiresAt", LocalDate.now().plusDays(40).toString()));
        assert403(extend, "extend");
        assertEquals("You cannot extend your own access. Ask the patient to extend it.", message(extend));
        assert403(doPatch(url, caregiver, Map.of("expiresAt", "")), "remove expiry");
        MvcResult widen = doPatch(url, caregiver, Map.of("accessLevel", "FULL_ACCESS"));
        assert403(widen, "widen");
        assertEquals("You can only reduce your own access. Ask the patient to grant additional permissions.", message(widen));
        MvcResult foreign = doPatch(url, otherCaregiver, Map.of("canViewRecords", false));
        assert403(foreign, "other caregiver");
        assertEquals("Not authorized to update this access record", message(foreign));
        assertEquals(404, doPatch(DEPENDENTS + "/999999", caregiver, Map.of("canViewRecords", false)).getResponse().getStatus());

        // A past expiry ends the grant now.
        MvcResult lapse = doPatch(url, caregiver, Map.of("expiresAt", LocalDateTime.now().minusMinutes(1).withNano(0).toString()));
        assertEquals(200, lapse.getResponse().getStatus(), lapse.getResponse().getContentAsString());
        assertEquals("EXPIRED", json(lapse).path("accessStatus").asText());
        MvcResult after = doPatch(url, caregiver, Map.of("canViewRecords", false));
        assert403(after, "expired grant");
        assertEquals("This access has expired or been revoked. Ask the patient to grant access again.", message(after));
    }

    // ==================== INVITE ====================

    @Test
    void invite_storesTheEmail_publishesTheResetRequest_andEnforcesItsRules() throws Exception {
        JsonNode managed = created(doPost(DEPENDENTS, caregiver, managedBody()));
        int accessId = managed.path("accessId").asInt();
        String dependentId = managed.path("patientSystemId").asText();
        String url = DEPENDENTS + "/" + accessId + "/invite";

        MvcResult invalid = doPost(url, caregiver, Map.of("email", "nope"));
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("email: Please enter a valid email address", message(invalid));
        assertEquals("email: Email is required", message(doPost(url, caregiver, Map.of())));
        assertEquals(404, doPost(DEPENDENTS + "/999999/invite", caregiver, Map.of("email", "a@example.com"))
                .getResponse().getStatus());
        MvcResult foreign = doPost(url, otherCaregiver, Map.of("email", "rudo@example.com"));
        assert403(foreign, "other caregiver");
        assertEquals("Not authorized to update this access record", message(foreign));
        MvcResult taken = doPost(url, caregiver, Map.of("email", patient.getEmail()));
        assertEquals(409, taken.getResponse().getStatus());
        assertEquals("Email already registered", message(taken));
        assertTrue(applicationEvents.stream(AdminPasswordResetRequestedEvent.class).findAny().isEmpty());

        MvcResult sent = doPost(url, caregiver, Map.of("email", "Rudo@Example.com"));
        assertEquals(200, sent.getResponse().getStatus(), sent.getResponse().getContentAsString());
        JsonNode body = json(sent);
        assertEquals("rudo@example.com", body.path("patientEmail").asText());
        assertTrue(body.path("managed").asBoolean());
        assertTrue(body.path("canInvite").asBoolean());
        assertEquals("rudo@example.com", jdbc.queryForObject("SELECT email FROM users WHERE system_id = ?",
                String.class, dependentId));
        assertEquals(List.of(new AdminPasswordResetRequestedEvent(dependentId, caregiver.getSystemId())),
                applicationEvents.stream(AdminPasswordResetRequestedEvent.class).toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'DEPENDENT_INVITE' "
                + "AND system_id = ? AND target_system_id = ?", Integer.class, caregiver.getSystemId(), dependentId));

        // Repeatable: re-sends and may correct the address.
        assertEquals(200, doPost(url, caregiver, Map.of("email", "rudo.moyo@example.com")).getResponse().getStatus());
        assertEquals(2, applicationEvents.stream(AdminPasswordResetRequestedEvent.class).count());

        // Once the patient has a password the invitation is over.
        jdbc.update("UPDATE users SET password_hash = 'x' WHERE system_id = ?", dependentId);
        MvcResult hasPassword = doPost(url, caregiver, Map.of("email", "rudo.moyo@example.com"));
        assertEquals(409, hasPassword.getResponse().getStatus());
        assertEquals("This person already has sign-in details", message(hasPassword));
        JsonNode listed = json(doGet(DEPENDENTS, caregiver)).get(0);
        assertFalse(listed.path("managed").asBoolean());
        assertFalse(listed.path("canInvite").asBoolean());
    }

    @Test
    void invite_isOnlyForTheCreatingCaregiver_ofAnActiveGrant() throws Exception {
        // A grant the patient authorised: the caregiver did not create this account.
        DependentAccess patientGrant = grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        MvcResult notCreator = doPost(DEPENDENTS + "/" + patientGrant.getAccessId() + "/invite", caregiver,
                Map.of("email", "new.address@example.com"));
        assert403(notCreator, "not the creator");
        assertEquals("Only the caregiver who created this account can send the invitation", message(notCreator));

        JsonNode managed = created(doPost(DEPENDENTS, caregiver, managedBody()));
        int accessId = managed.path("accessId").asInt();
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE access_id = ?",
                LocalDateTime.now().minusMinutes(1), accessId);
        MvcResult expired = doPost(DEPENDENTS + "/" + accessId + "/invite", caregiver, Map.of("email", "rudo@example.com"));
        assert403(expired, "expired grant");
        assertEquals("This access has expired or been revoked. Ask the patient to grant access again.", message(expired));

        // A dependent created with sign-in details already has a password.
        JsonNode withLogin = created(doPost(DEPENDENTS, caregiver, signInBody("grace.dube@example.com")));
        MvcResult hasLogin = doPost(DEPENDENTS + "/" + withLogin.path("accessId").asInt() + "/invite", caregiver,
                Map.of("email", "grace.dube@example.com"));
        assertEquals(409, hasLogin.getResponse().getStatus());
    }

    // ==================== LISTS ====================

    @Test
    void dependentsList_showsActiveGrantsOldestFirst_hidesHealthWithoutRecords_andIsAudited() throws Exception {
        grantAccess(caregiver, otherPatient, a -> a.setCanViewAppointments(true));
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        jdbc.update("UPDATE patients SET blood_type = 'A+', date_of_birth = '1950-01-01' WHERE system_id IN (?, ?)",
                patient.getSystemId(), otherPatient.getSystemId());
        // An expired grant is not listed.
        User third = createAndSaveUser(8, "PAT-00009", "third@example.com", "PATIENT");
        grantAccess(caregiver, third, a -> a.setExpiresAt(LocalDateTime.now().minusDays(1)));

        JsonNode list = json(doGet(DEPENDENTS, caregiver));
        assertEquals(2, list.size(), list.toString());
        assertEquals(otherPatient.getSystemId(), list.get(0).path("patientSystemId").asText());
        assertTrue(list.get(0).path("patientBloodType").isNull(), "no RECORDS: health fields hidden");
        assertTrue(list.get(0).path("caregiverName").isNull(), "caregiver view leaves the caregiver's own name out");
        assertEquals(patient.getSystemId(), list.get(1).path("patientSystemId").asText());
        assertEquals("A+", list.get(1).path("patientBloodType").asText());
        assertEquals(patient.getPhone(), list.get(1).path("patientPhone").asText());
        assertFalse(list.get(1).path("canInvite").asBoolean(), "patient-authorised grant: no invitation");

        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT patient_system_id) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'PROFILE' AND system_id = ?", Integer.class, caregiver.getSystemId()));
    }

    @Test
    void patientLists_newAndDeprecated_shareTheRules() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(otherCaregiver, patient, a -> a.setExpiresAt(LocalDateTime.now().minusDays(1)));

        JsonNode all = json(doGet(ACCESS, patient));
        assertEquals(2, all.size(), "active and expired");
        all.forEach(g -> {
            assertFalse(g.path("caregiverName").isNull(), "patient view names the caregiver");
            assertFalse(g.path("canInvite").asBoolean());
        });
        JsonNode active = json(doGet(LEGACY, patient));
        assertEquals(1, active.size(), "deprecated list: ACTIVE only");
        assertEquals(caregiver.getSystemId(), active.get(0).path("caregiverSystemId").asText());
        assertEquals(0, json(doGet(ACCESS, otherPatient)).size());
        assert403(doGet(LEGACY, admin), "ADMIN on deprecated list");

        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PROFILE' "
                + "AND system_id = ? AND patient_system_id = ?", Integer.class, patient.getSystemId(), patient.getSystemId()) >= 1);

        // The deprecated PATCH and DELETE delegate to the same rules.
        int accessId = all.get(0).path("accessId").asInt();
        assert403(doPatch(LEGACY + "/" + accessId, otherPatient, Map.of("canViewRecords", false)), "other patient");
        assertEquals(200, doPatch(LEGACY + "/" + accessId, patient, Map.of("canViewImaging", true)).getResponse().getStatus());
        assertEquals(204, doDelete(LEGACY + "/" + accessId, patient).getResponse().getStatus());
        MvcResult revoked = doPatch(ACCESS + "/" + accessId, patient, Map.of("canViewImaging", false));
        assertEquals(409, revoked.getResponse().getStatus());
        assertEquals("This access has been revoked", message(revoked));
    }

    // ==================== ACTIVITY FEED ====================

    @Test
    void activityFeed_windowAndPaging_inTheDatabase() throws Exception {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanViewConsultations(true);
        });
        grantAccess(caregiver, otherPatient, a -> a.setCanViewConsultations(true)); // no appointments permission
        LocalDateTime now = LocalDateTime.now();
        Appointment old = appointment(patient, now.minusDays(100), "COMPLETED");
        Appointment recent = appointment(patient, now.minusDays(10), "COMPLETED");
        Appointment soon = appointment(patient, now.plusDays(5).withHour(10).withMinute(0), "SCHEDULED");
        Appointment later = appointment(patient, now.plusDays(200).withHour(11).withMinute(0), "SCHEDULED");
        appointment(otherPatient, now.plusDays(3).withHour(9).withMinute(0), "SCHEDULED");
        Consultation finished = consultation(recent, "COMPLETED", now.minusDays(10));
        // V35: one non-cancelled consultation per appointment, so the hidden draft belongs to another visit.
        consultation(soon, "DRAFT", now.minusDays(9));
        consultation(old, "COMPLETED", now.minusDays(100));

        // Legacy form: default window, newest first, no paging envelope.
        MvcResult legacy = doGet(ACTIVITY, caregiver);
        assertEquals(200, legacy.getResponse().getStatus(), legacy.getResponse().getContentAsString());
        JsonNode legacyBody = json(legacy);
        assertTrue(legacyBody.path("appointments").isArray());
        assertEquals(List.of(later.getAppointmentId(), soon.getAppointmentId(), recent.getAppointmentId()),
                ids(legacyBody.path("appointments"), "appointmentId"));
        assertEquals("PATIENT User1", legacyBody.path("appointments").get(0).path("patientName").asText());
        assertEquals(List.of(finished.getConsultationId()), ids(legacyBody.path("consultations"), "consultationId"));
        assertEquals("IN_PERSON", legacyBody.path("consultations").get(0).path("appointmentType").asText());

        // Paged form.
        JsonNode page0 = json(doGet(ACTIVITY + "?page=0&size=2", caregiver));
        assertEquals(LocalDate.now().minusDays(90).toString(), page0.path("from").asText());
        assertTrue(page0.path("to").isNull());
        JsonNode appointments = page0.path("appointments");
        assertEquals(3, appointments.path("totalElements").asInt());
        assertEquals(2, appointments.path("totalPages").asInt());
        assertEquals(2, appointments.path("size").asInt());
        assertEquals(List.of(later.getAppointmentId(), soon.getAppointmentId()), ids(appointments.path("content"), "appointmentId"));
        assertEquals(1, page0.path("consultations").path("totalElements").asInt());
        JsonNode page1 = json(doGet(ACTIVITY + "?page=1&size=2", caregiver));
        assertEquals(List.of(recent.getAppointmentId()), ids(page1.path("appointments").path("content"), "appointmentId"));
        assertEquals(0, page1.path("consultations").path("content").size());

        // Explicit window, inclusive dates.
        String from = LocalDate.now().minusDays(120).toString();
        String to = LocalDate.now().toString();
        JsonNode window = json(doGet(ACTIVITY + "?from=" + from + "&to=" + to, caregiver));
        assertEquals(List.of(recent.getAppointmentId(), old.getAppointmentId()),
                ids(window.path("appointments").path("content"), "appointmentId"));
        assertEquals(2, window.path("consultations").path("totalElements").asInt());
        assertEquals(to, window.path("to").asText());

        // Size clamp and parameter errors.
        assertEquals(100, json(doGet(ACTIVITY + "?size=1000", caregiver)).path("appointments").path("size").asInt());
        MvcResult reversed = doGet(ACTIVITY + "?from=2026-01-10&to=2026-01-01", caregiver);
        assertEquals(400, reversed.getResponse().getStatus());
        assertEquals("The 'to' date must not be before the 'from' date", message(reversed));
        MvcResult badDate = doGet(ACTIVITY + "?from=10-01-2026", caregiver);
        assertEquals(400, badDate.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'from'", message(badDate));

        // Appointments and consultations are audited as list reads of the dependent.
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'APPOINTMENT' "
                + "AND system_id = ? AND patient_system_id = ?", Integer.class, caregiver.getSystemId(), patient.getSystemId()) >= 1);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'CONSULTATION' "
                + "AND system_id = ? AND patient_system_id = ?", Integer.class, caregiver.getSystemId(), patient.getSystemId()) >= 1);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE resource_type = 'APPOINTMENT' "
                + "AND patient_system_id = ?", Integer.class, otherPatient.getSystemId()));

        // Without the permission (or with an expired grant) a dependent is silently left out.
        assertEquals(0, json(doGet(ACTIVITY + "?page=0", otherCaregiver)).path("appointments").path("totalElements").asInt());
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE caregiver_system_id = ?",
                LocalDateTime.now().minusMinutes(1), caregiver.getSystemId());
        JsonNode expired = json(doGet(ACTIVITY + "?page=0", caregiver));
        assertEquals(0, expired.path("appointments").path("totalElements").asInt());
        assertEquals(0, expired.path("consultations").path("totalElements").asInt());
    }
}
