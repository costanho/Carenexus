package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.auth.support.AuthTestHooks;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /api/auth/register and the account-creation facade (AuthService.createActiveUserAccount), against the real
 * schema with committed transactions, so roll-backs and listeners behave as in production.
 */
@Import(AuthTestHooks.class)
class AuthRegistrationIntegrationTest extends CommittedIntegrationTest {

    private static final String INVALID_ROLE = "Invalid role. Allowed roles are patient, doctor and proxy.";

    @Autowired private AuthService authService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RateLimitProperties rateLimitProperties;
    @Autowired private RateLimitGuard rateLimitGuard;
    @Autowired private AuthTestHooks.FirstGate firstGate;
    @Autowired private AuthTestHooks.SecondGate secondGate;
    @Autowired private AuthTestHooks.TestFlags testFlags;
    @Autowired private AuthTestHooks.FailingFlags failingFlags;
    @Autowired private AuthTestHooks.EventRecorder recorder;

    private AuthApi api;

    @BeforeEach
    void setUp() {
        AuthTestHooks.reset(firstGate, secondGate, testFlags, failingFlags, recorder);
        api = new AuthApi(mockMvc, objectMapper);
    }

    // ==================== ROLE CASCADE ====================

    @Test
    void patientRegistration_createsTheUserAndThePatientRow_andReturnsNoTokens() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "registration-test-agent")
                .content(objectMapper.writeValueAsString(AuthApi.registration("patient", "new.patient@example.com"))))
                .andReturn();

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("Account created successfully. Please log in.", body.path("message").asText());
        assertExplicitNull(body, "tokens");
        assertExplicitNull(body, "flags");
        assertExplicitNull(body, "challenge");
        assertEquals("PAT-00003", body.path("user").path("system_id").asText());
        assertEquals("Tendai Moyo", body.path("user").path("name").asText());
        assertEquals("new.patient@example.com", body.path("user").path("email").asText());
        assertEquals("patient", body.path("user").path("role").asText());
        assertTrue(body.path("user").path("user_id").isInt());

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE system_id = 'PAT-00003'");
        assertEquals("PATIENT", user.get("role"));
        assertEquals(Boolean.TRUE, user.get("is_active"));
        assertNull(user.get("email_verified_at"), "a self-registered email is not verified yet");
        assertEquals(Boolean.FALSE, user.get("must_change_password"));
        assertNull(user.get("phone"));
        assertEquals("STABLE", jdbc.queryForObject(
                "SELECT health_status FROM patients WHERE system_id = 'PAT-00003'", String.class));

        // Audit: REGISTER / ACCOUNT / SUCCESS with the new account as actor and target.
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER' AND resource_type = 'ACCOUNT' "
                + "AND status = 'SUCCESS' AND system_id = 'PAT-00003' AND target_system_id = 'PAT-00003'"));

        // Event: published once, self-registration (no creator), request context carried.
        assertEquals(1, recorder.registered.size());
        UserRegisteredEvent event = recorder.registered.get(0);
        assertEquals("PAT-00003", event.systemId());
        assertEquals("PATIENT", event.role());
        assertEquals("new.patient@example.com", event.email());
        assertNull(event.createdBySystemId());
        assertEquals("127.0.0.1", event.ip());
        assertEquals("registration-test-agent", event.userAgent());
    }

    @Test
    void proxyAndCaregiver_areBothStoredAsCaregiver_andReportedAsProxy() throws Exception {
        MvcResult proxy = api.register(AuthApi.registration("proxy", "proxy.one@example.com"));
        MvcResult caregiverRole = api.register(AuthApi.registration("CAREGIVER", "proxy.two@example.com"));

        assertEquals(201, proxy.getResponse().getStatus(), proxy.getResponse().getContentAsString());
        assertEquals(201, caregiverRole.getResponse().getStatus());
        assertEquals("proxy", json(proxy).path("user").path("role").asText());
        assertEquals("proxy", json(caregiverRole).path("user").path("role").asText());
        assertEquals("CGV-00003", json(proxy).path("user").path("system_id").asText());
        assertEquals("CGV-00004", json(caregiverRole).path("user").path("system_id").asText());

        assertEquals(2, count("SELECT COUNT(*) FROM users WHERE role = 'CAREGIVER' AND system_id IN ('CGV-00003', 'CGV-00004')"));
        assertEquals(2, count("SELECT COUNT(*) FROM caregivers WHERE system_id IN ('CGV-00003', 'CGV-00004')"));
        assertEquals("CAREGIVER", recorder.registered.get(0).role(), "the event carries the stored role");
    }

    @Test
    void doctorRegistration_storesTheLicenceDetails_isPending_andCannotSignIn() throws Exception {
        Map<String, Object> request = AuthApi.registration("doctor", "new.doctor@example.com");
        request.put("licenseNo", "  MDPCZ-12345 ");
        request.put("specialization", "Cardiology");
        request.put("licensingBody", "Medical and Dental Practitioners Council of Zimbabwe");
        request.put("licenseCountry", "zw");

        MvcResult result = api.register(request);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("Account created. Your clinician account is pending verification; you will be able to sign in "
                + "once an administrator has approved it.", body.path("message").asText());
        assertEquals("doctor", body.path("user").path("role").asText());
        assertEquals("DOC-00003", body.path("user").path("system_id").asText());
        assertExplicitNull(body, "tokens");

        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT is_active FROM users WHERE system_id = 'DOC-00003'", Boolean.class));
        Map<String, Object> doctorRow = jdbc.queryForMap("SELECT * FROM doctors WHERE system_id = 'DOC-00003'");
        assertEquals("PENDING", doctorRow.get("verification_status"));
        assertEquals(Boolean.FALSE, doctorRow.get("is_active"));
        assertEquals("MDPCZ-12345", doctorRow.get("license_no"), "stored trimmed, case kept");
        assertEquals("Cardiology", doctorRow.get("specialization"));
        assertEquals("Medical and Dental Practitioners Council of Zimbabwe", doctorRow.get("licensing_body"));
        assertEquals("ZW", doctorRow.get("license_country"), "stored upper-case");
        assertNull(doctorRow.get("verified_at"));

        // The right password is refused with a code until an administrator approves the clinician.
        MvcResult login = api.login("new.doctor@example.com", "Str0ng-Passw0rd");
        assertEquals(403, login.getResponse().getStatus(), login.getResponse().getContentAsString());
        assertEquals("ACCOUNT_PENDING_VERIFICATION", json(login).path("code").asText());
        assertEquals("Your clinician account is pending verification. You can sign in once an administrator approves it.",
                message(login));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'DOC-00003'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'FAILURE' "
                + "AND error_message = 'Clinician pending verification'"));

        // A wrong password for the same account is an ordinary 401: the state is not revealed.
        MvcResult wrong = api.login("new.doctor@example.com", "Wr0ng-Passw0rd");
        assertEquals(401, wrong.getResponse().getStatus());
        assertEquals("Invalid email or password", message(wrong));
    }

    @Test
    void doctorRegistration_withoutLicenceFields_isAcceptedAsPending() throws Exception {
        // The native screen sends no clinician fields.
        MvcResult result = api.register(AuthApi.registration("DOCTOR", "native.doctor@example.com"));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        Map<String, Object> doctorRow = jdbc.queryForMap("SELECT * FROM doctors WHERE system_id = 'DOC-00003'");
        assertEquals("PENDING", doctorRow.get("verification_status"));
        assertNull(doctorRow.get("license_no"));
        assertNull(doctorRow.get("specialization"));
        assertNull(doctorRow.get("licensing_body"));
        assertNull(doctorRow.get("license_country"));

        // Several doctors without a licence number do not collide on the unique column.
        assertEquals(201, api.register(AuthApi.registration("doctor", "native.doctor2@example.com")).getResponse().getStatus());
    }

    @Test
    void clinicianFields_areIgnoredForOtherRoles() throws Exception {
        Map<String, Object> patientRequest = AuthApi.registration("patient", "not.a.doctor@example.com");
        patientRequest.put("licenseNo", "LIC-777");
        patientRequest.put("specialization", "Cardiology");
        patientRequest.put("licenseCountry", "ZW");
        assertEquals(201, api.register(patientRequest).getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM doctors WHERE license_no = 'LIC-777'"));

        // The number was not taken by the patient: a doctor can still register with it.
        Map<String, Object> doctorRequest = AuthApi.registration("doctor", "real.doctor@example.com");
        doctorRequest.put("licenseNo", "LIC-777");
        assertEquals(201, api.register(doctorRequest).getResponse().getStatus());
    }

    // ==================== REFUSED ROLES ====================

    @Test
    void adminRole_cannotBeSelfRegistered() throws Exception {
        long usersBefore = count("SELECT COUNT(*) FROM users");

        for (String role : List.of("ADMIN", "admin", "Admin")) {
            MvcResult result = api.register(AuthApi.registration(role, "wannabe.admin@example.com"));
            assertEquals(400, result.getResponse().getStatus(), role);
            assertEquals(INVALID_ROLE, message(result));
            assertFalse(json(result).has("code"), "a refused role carries no code");
        }
        MvcResult unknown = api.register(AuthApi.registration("nurse", "nurse@example.com"));
        assertEquals(400, unknown.getResponse().getStatus());
        assertEquals(INVALID_ROLE, message(unknown));

        assertEquals(usersBefore, count("SELECT COUNT(*) FROM users"));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND system_id <> 'ADM-00001'"));
        assertTrue(recorder.registered.isEmpty());
    }

    // ==================== DUPLICATES ====================

    @Test
    void duplicateEmail_is409_whateverTheLetterCase() throws Exception {
        MvcResult result = api.register(AuthApi.registration("patient", "  THOMAS@Example.com "));

        assertEquals(409, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("Email already registered", message(result));
        assertEquals("Conflict", json(result).path("error").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE LOWER(email) = 'thomas@example.com'"));
    }

    @Test
    void duplicatePhone_is409() throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "same.phone@example.com");
        request.put("phone", patient.getPhone());

        MvcResult result = api.register(request);

        assertEquals(409, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("Phone number already registered", message(result));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'same.phone@example.com'"));
    }

    @Test
    void duplicateLicenceNumber_is409_whateverTheLetterCase() throws Exception {
        Map<String, Object> first = AuthApi.registration("doctor", "licensed.one@example.com");
        first.put("licenseNo", "Abc-123");
        assertEquals(201, api.register(first).getResponse().getStatus());

        Map<String, Object> second = AuthApi.registration("doctor", "licensed.two@example.com");
        second.put("licenseNo", "ABC-123");
        MvcResult result = api.register(second);

        assertEquals(409, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("Licence number already registered", message(result));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'licensed.two@example.com'"));
    }

    // ==================== VALIDATION ====================

    @Test
    void weakPassword_is400_withTheFieldError() throws Exception {
        String policy = "Password must be at least 8 characters and contain at least one letter and one number";
        for (String weak : List.of("Sh0rt", "onlyletters", "1234567890")) {
            Map<String, Object> request = AuthApi.registration("patient", "weak.password@example.com");
            request.put("password", weak);
            MvcResult result = api.register(request);

            assertEquals(400, result.getResponse().getStatus(), weak);
            JsonNode body = json(result);
            assertEquals("VALIDATION_FAILED", body.path("code").asText());
            assertEquals("password: " + policy, body.path("message").asText());
            assertEquals(1, body.path("errors").size());
            assertEquals("password", body.path("errors").get(0).path("field").asText());
            assertEquals(policy, body.path("errors").get(0).path("message").asText());
        }

        Map<String, Object> tooLong = AuthApi.registration("patient", "weak.password@example.com");
        tooLong.put("password", "Passw0rd-" + "x".repeat(70));
        assertEquals("password: Password must be at most 72 characters", message(api.register(tooLong)));

        Map<String, Object> blank = AuthApi.registration("patient", "weak.password@example.com");
        blank.put("password", "   ");
        assertEquals("password: Password is required", message(api.register(blank)));

        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'weak.password@example.com'"));
    }

    @Test
    void emptyBody_reportsEveryRequiredField_sortedByFieldName() throws Exception {
        MvcResult result = api.register(Map.of());

        assertEquals(400, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText());
        assertEquals("email: Email is required", body.path("message").asText());
        List<String> fields = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        body.path("errors").forEach(error -> {
            fields.add(error.path("field").asText());
            messages.add(error.path("message").asText());
        });
        assertEquals(List.of("email", "firstName", "lastName", "password", "role"), fields);
        assertEquals(List.of("Email is required", "First name is required", "Last name is required",
                "Password is required", "Role is required"), messages);
    }

    @Test
    void eachFieldRule_hasItsOwnMessage() throws Exception {
        assertFieldError("firstName", "x".repeat(101), "First name must be at most 100 characters");
        assertFieldError("lastName", "x".repeat(101), "Last name must be at most 100 characters");
        assertFieldError("firstName", "   ", "First name is required");
        assertFieldError("email", "not-an-email", "Please enter a valid email address");
        assertFieldError("email", "a".repeat(140) + "@example.com", "Please enter a valid email address");
        assertFieldError("phone", "+263 (77) 123-45-67 .8", "Phone number must be at most 20 characters");
        assertFieldError("phone", "call me", "Please enter a valid phone number");
        assertFieldError("relationship", "x".repeat(81), "Relationship must be at most 80 characters");
        assertFieldError("licenseNo", "x".repeat(51), "Licence number must be at most 50 characters");
        assertFieldError("specialization", "x".repeat(151), "Specialization must be at most 150 characters");
        assertFieldError("licensingBody", "x".repeat(151), "Licensing body must be at most 150 characters");
        assertFieldError("licenseCountry", "ZWE", "Licence country must be a two-letter country code");
        assertFieldError("licenseCountry", "Z1", "Licence country must be a two-letter country code");
        assertFieldError("caregiverSystemId", "x".repeat(21), "must be at most 20 characters");
        assertFieldError("acceptedTermsVersion", "x".repeat(21), "must be at most 20 characters");
        assertFieldError("acceptedPrivacyVersion", "x".repeat(21), "must be at most 20 characters");
        assertFieldError("humanVerificationToken", "x".repeat(4097), "must be at most 4096 characters");

        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'field.rule@example.com'"));
    }

    @Test
    void malformedJson_is400_withoutACode() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content("{\"firstName\": ")).andReturn();

        assertEquals(400, result.getResponse().getStatus());
        assertEquals("Malformed request body", message(result));
        assertFalse(json(result).has("code"));
    }

    @Test
    void textIsTrimmed_emailIsLowerCased_andBlankOptionalFieldsCountAsAbsent() throws Exception {
        Map<String, Object> request = AuthApi.registration("  Patient ", "  MiXed.Case@Example.COM ");
        request.put("firstName", "  Rudo ");
        request.put("lastName", " Sithole  ");
        request.put("phone", "   ");
        request.put("caregiverSystemId", "");
        request.put("licenseCountry", " ");

        MvcResult result = api.register(request);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE system_id = 'PAT-00003'");
        assertEquals("Rudo", user.get("first_name"));
        assertEquals("Sithole", user.get("last_name"));
        assertEquals("mixed.case@example.com", user.get("email"));
        assertNull(user.get("phone"));
        assertEquals(0, count("SELECT COUNT(*) FROM dependent_access WHERE patient_system_id = 'PAT-00003'"));
    }

    // ==================== LEGAL VERSIONS AND HUMAN VERIFICATION ====================

    @Test
    void registrationWithoutLegalVersions_succeeds_andTheEventCarriesNone() throws Exception {
        // The native screen sends no versions: absent means "not accepted yet".
        MvcResult result = api.register(AuthApi.registration("patient", "no.versions@example.com"));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        UserRegisteredEvent event = recorder.registered.get(0);
        assertNull(event.acceptedTermsVersion());
        assertNull(event.acceptedPrivacyVersion());
    }

    @Test
    void legalVersions_areCarriedIntoTheEventUnchanged() throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "with.versions@example.com");
        request.put("acceptedTermsVersion", "1");
        request.put("acceptedPrivacyVersion", "1");

        assertEquals(201, api.register(request).getResponse().getStatus());

        UserRegisteredEvent event = recorder.registered.get(0);
        assertEquals("1", event.acceptedTermsVersion());
        assertEquals("1", event.acceptedPrivacyVersion());
    }

    @Test
    void humanVerificationToken_isAcceptedAndIgnoredByTheDefaultProvider() throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "with.token@example.com");
        request.put("humanVerificationToken", "widget-token-" + "t".repeat(200));

        assertEquals(201, api.register(request).getResponse().getStatus());
    }

    // ==================== CAREGIVER LINK ====================

    @Test
    void caregiverLink_createsAViewOnlyGrantAuthorisedByTheNewPatient_andRecordsIt() throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "linked.patient@example.com");
        request.put("caregiverSystemId", caregiver.getSystemId());
        request.put("relationship", " son ");

        MvcResult result = api.register(request);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        Map<String, Object> grant = jdbc.queryForMap(
                "SELECT * FROM dependent_access WHERE patient_system_id = 'PAT-00003'");
        assertEquals(caregiver.getSystemId(), grant.get("caregiver_system_id"));
        assertEquals("PAT-00003", grant.get("authorized_by_system_id"));
        assertEquals("VIEW_ONLY", grant.get("access_level"));
        assertEquals("son", grant.get("relationship"));
        assertEquals(Boolean.TRUE, grant.get("is_active"));

        assertEquals(1, recorder.grants.size());
        CaregiverGrantRecordedEvent event = recorder.grants.get(0);
        assertEquals(Integer.valueOf(((Number) grant.get("access_id")).intValue()), event.accessId());
        assertEquals("PAT-00003", event.patientSystemId());
        assertEquals(caregiver.getSystemId(), event.caregiverSystemId());
        assertEquals("PAT-00003", event.actorSystemId());
        assertEquals(CaregiverGrantRecordedEvent.GRANTED, event.kind());
        assertFalse(event.guardianAttested());
    }

    @Test
    void caregiverLink_defaultsTheRelationship() throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "linked.default@example.com");
        request.put("caregiverSystemId", caregiver.getSystemId());

        assertEquals(201, api.register(request).getResponse().getStatus());
        assertEquals("caregiver", jdbc.queryForObject(
                "SELECT relationship FROM dependent_access WHERE patient_system_id = 'PAT-00003'", String.class));
    }

    @Test
    void caregiverLink_isRefusedForOtherRoles_andForAnythingThatIsNotAnActiveCaregiver() throws Exception {
        long usersBefore = count("SELECT COUNT(*) FROM users");

        Map<String, Object> doctorRequest = AuthApi.registration("doctor", "linked.doctor@example.com");
        doctorRequest.put("caregiverSystemId", caregiver.getSystemId());
        MvcResult wrongRole = api.register(doctorRequest);
        assertEquals(400, wrongRole.getResponse().getStatus());
        assertEquals("A caregiver link can only be supplied when registering a patient", message(wrongRole));

        String invalid = "Invalid caregiver: no active proxy/caregiver account exists with that ID";
        // Unknown id, a patient's id, a doctor's id.
        for (String id : List.of("CGV-99999", otherPatient.getSystemId(), doctor.getSystemId())) {
            Map<String, Object> request = AuthApi.registration("patient", "linked.invalid@example.com");
            request.put("caregiverSystemId", id);
            MvcResult result = api.register(request);
            assertEquals(400, result.getResponse().getStatus(), id);
            assertEquals(invalid, message(result));
        }
        // A deactivated caregiver.
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", otherCaregiver.getSystemId());
        Map<String, Object> request = AuthApi.registration("patient", "linked.invalid@example.com");
        request.put("caregiverSystemId", otherCaregiver.getSystemId());
        assertEquals(invalid, message(api.register(request)));

        assertEquals(usersBefore, count("SELECT COUNT(*) FROM users"));
        assertEquals(0, count("SELECT COUNT(*) FROM dependent_access"));
    }

    @Test
    void failureAfterTheRowsWereWritten_rollsBackTheUserThePatientAndTheCaregiverLink() throws Exception {
        long usersBefore = count("SELECT COUNT(*) FROM users");
        long patientsBefore = count("SELECT COUNT(*) FROM patients");
        // A listener of UserRegisteredEvent refuses this registration after user, patient and grant were saved.
        recorder.abortRegistrationOfEmail = "aborted@example.com";
        Map<String, Object> request = AuthApi.registration("patient", "aborted@example.com");
        request.put("caregiverSystemId", caregiver.getSystemId());
        request.put("relationship", "son");

        MvcResult result = api.register(request);

        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("LEGAL_ACCEPTANCE_REQUIRED", json(result).path("code").asText());
        assertEquals(1, recorder.registered.size(), "the rows existed when the listener ran");
        assertEquals(1, recorder.grants.size());

        assertEquals(usersBefore, count("SELECT COUNT(*) FROM users"));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'aborted@example.com'"));
        assertEquals(patientsBefore, count("SELECT COUNT(*) FROM patients"));
        assertEquals(0, count("SELECT COUNT(*) FROM dependent_access"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER'"));

        // Nothing is left behind: the same registration succeeds once the listener accepts it.
        recorder.abortRegistrationOfEmail = null;
        assertEquals(201, api.register(request).getResponse().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM dependent_access"));
    }

    // ==================== RATE LIMIT ====================

    @Test
    void registrationsFromOneAddress_areLimited_with429AndRetryAfter() throws Exception {
        AuthApi flood = api.from("10.25.0.11");
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(3);
        small.setWindowSeconds(3600L);
        RateLimitProperties.Bucket previousAuthRegisterIp = rateLimitProperties.getBuckets().put("auth-register-ip", small);
        rateLimitProperties.setEnabled(true);
        try {
            // A request that fails Bean Validation is not counted.
            for (int i = 0; i < 5; i++) {
                assertEquals(400, flood.register(Map.of()).getResponse().getStatus());
            }
            // Every request that passed validation is counted, whatever its outcome (201, 409, 400 for the role).
            assertEquals(201, flood.register(AuthApi.registration("patient", "flood.one@example.com")).getResponse().getStatus());
            assertEquals(409, flood.register(AuthApi.registration("patient", "flood.one@example.com")).getResponse().getStatus());
            assertEquals(400, flood.register(AuthApi.registration("admin", "flood.two@example.com")).getResponse().getStatus());

            MvcResult limited = flood.register(AuthApi.registration("patient", "flood.three@example.com"));
            assertEquals(429, limited.getResponse().getStatus(), limited.getResponse().getContentAsString());
            assertEquals("RATE_LIMITED", json(limited).path("code").asText());
            long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
            assertTrue(retryAfter >= 1 && retryAfter <= 3600, "Retry-After was " + retryAfter);
            assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'flood.three@example.com'"));

            // Another address is not affected.
            assertEquals(201, api.from("10.25.0.12")
                    .register(AuthApi.registration("patient", "flood.three@example.com")).getResponse().getStatus());
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previousAuthRegisterIp != null) { rateLimitProperties.getBuckets().put("auth-register-ip", previousAuthRegisterIp); } else { rateLimitProperties.getBuckets().remove("auth-register-ip"); }
            rateLimitGuard.reset("auth-register-ip", "10.25.0.11");
            rateLimitGuard.reset("auth-register-ip", "10.25.0.12");
        }
    }

    // ==================== FACADE: createActiveUserAccount ====================

    @Test
    void createActiveUserAccount_mustRunInsideTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> authService.createActiveUserAccount(
                "Managed", "Dependent", "outside.tx@example.com", "Str0ng-Passw0rd", null, "PATIENT"));
        assertThrows(IllegalTransactionStateException.class, () -> authService.createActiveUserAccount(
                "Managed", "Dependent", "outside.tx@example.com", "Str0ng-Passw0rd", null, "PATIENT", false, false));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'outside.tx@example.com'"));
    }

    @Test
    void sixArgumentMethod_createsAnActiveVerifiedAccount_andStillRequiresCredentials() {
        authenticateAs(caregiver);
        User created = inTransaction(() -> authService.createActiveUserAccount(
                " Tariro ", "Dube", " Tariro.Dube@Example.com ", "Str0ng-Passw0rd", " +263771000001 ", "patient"));

        assertEquals("PAT-00003", created.getSystemId());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE system_id = 'PAT-00003'");
        assertEquals("Tariro", row.get("first_name"));
        assertEquals("tariro.dube@example.com", row.get("email"));
        assertEquals("+263771000001", row.get("phone"));
        assertEquals("PATIENT", row.get("role"));
        assertEquals(Boolean.TRUE, row.get("is_active"));
        assertNotNull(row.get("email_verified_at"), "an account created for somebody counts as verified");
        assertEquals(Boolean.FALSE, row.get("must_change_password"));
        assertNotNull(row.get("password_hash"));
        // Only the users row: the caller creates the role profile.
        assertEquals(0, count("SELECT COUNT(*) FROM patients WHERE system_id = 'PAT-00003'"));

        UserRegisteredEvent event = recorder.registered.get(0);
        assertEquals(caregiver.getSystemId(), event.createdBySystemId());
        assertEquals("PATIENT", event.role());
        assertNull(event.acceptedTermsVersion());
        assertNull(event.ip());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER' AND status = 'SUCCESS' "
                + "AND system_id = '" + caregiver.getSystemId() + "' AND target_system_id = 'PAT-00003'"));

        // As before, this method never creates an account without credentials.
        assertEquals("Email is required", assertThrows(BadRequestException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("No", "Email", null, null, null, "PATIENT"))).getMessage());
        assertEquals("Password is required", assertThrows(BadRequestException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("No", "Password", "no.password@example.com", null, null, "PATIENT"))).getMessage());
        assertEquals("Invalid role. Allowed roles are patient, doctor and proxy.",
                assertThrows(BadRequestException.class, () -> inTransaction(() -> authService.createActiveUserAccount(
                        "No", "Admin", "no.admin@example.com", "Str0ng-Passw0rd", null, "ADMIN"))).getMessage());
        assertEquals("Email already registered", assertThrows(ConflictException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("Dup", "Email", "THOMAS@example.com", "Str0ng-Passw0rd", null, "PATIENT"))).getMessage());
        assertEquals("Phone number already registered", assertThrows(ConflictException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("Dup", "Phone", "dup.phone@example.com", "Str0ng-Passw0rd",
                        patient.getPhone(), "PATIENT"))).getMessage());
    }

    @Test
    void overload_createsAnAdministratorOnlyWhenAllowed() {
        BadRequestException refused = assertThrows(BadRequestException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("New", "Admin", "second.admin@example.com", "Str0ng-Passw0rd",
                        null, "ADMIN", true, false)));
        assertEquals(INVALID_ROLE, refused.getMessage());
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'second.admin@example.com'"));

        authenticateAs(admin);
        User created = inTransaction(() -> authService.createActiveUserAccount(
                "New", "Admin", "second.admin@example.com", "Str0ng-Passw0rd", null, "admin", true, true));

        assertEquals("ADM-00002", created.getSystemId());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE system_id = 'ADM-00002'");
        assertEquals("ADMIN", row.get("role"));
        assertEquals(Boolean.TRUE, row.get("is_active"));
        assertEquals(Boolean.TRUE, row.get("must_change_password"), "a temporary password must be changed");
        assertNotNull(row.get("email_verified_at"));
        assertEquals(admin.getSystemId(), recorder.registered.get(0).createdBySystemId());
        assertEquals("ADMIN", recorder.registered.get(0).role());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER' AND system_id = 'ADM-00001' "
                + "AND target_system_id = 'ADM-00002' AND actor_role = 'ADMIN'"));
    }

    @Test
    void overload_withoutASignedInCaller_recordsTheSystemAsCreator() {
        User created = inTransaction(() -> authService.createActiveUserAccount(
                "Job", "Created", "job.created@example.com", "Str0ng-Passw0rd", null, "CAREGIVER", false, false));

        assertEquals("SYSTEM", recorder.registered.get(0).createdBySystemId());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE system_id = ?", Boolean.class, created.getSystemId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER' AND system_id IS NULL "
                + "AND target_system_id = '" + created.getSystemId() + "'"));
    }

    @Test
    void overload_managedAccountWithoutCredentials_isCreated_andCannotSignIn() throws Exception {
        authenticateAs(caregiver);
        User managed = inTransaction(() -> authService.createActiveUserAccount(
                "Elias", "Dube", null, null, null, "PATIENT", true, false));
        // A second one: no email and no phone, so nothing collides.
        User second = inTransaction(() -> authService.createActiveUserAccount(
                "Agnes", "Dube", "  ", "", "  ", "PATIENT", false, false));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE system_id = ?", managed.getSystemId());
        assertNull(row.get("email"));
        assertNull(row.get("password_hash"));
        assertNull(row.get("phone"));
        assertEquals(Boolean.TRUE, row.get("is_active"));
        assertEquals(Boolean.FALSE, row.get("must_change_password"), "there is no password to change");
        assertNotNull(row.get("email_verified_at"));
        assertNull(recorder.registered.get(0).email());
        assertEquals(caregiver.getSystemId(), recorder.registered.get(0).createdBySystemId());
        assertEquals("PAT-00004", second.getSystemId());

        // Exactly one of the two is a mistake, not a managed account.
        assertEquals("Password is required", assertThrows(BadRequestException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("Half", "Account", "half@example.com", null, null, "PATIENT", false, false))).getMessage());
        assertEquals("Email is required", assertThrows(BadRequestException.class, () -> inTransaction(() ->
                authService.createActiveUserAccount("Half", "Account", null, "Str0ng-Passw0rd", null, "PATIENT", false, false))).getMessage());

        // Even once the account has an email it has no password: every sign-in attempt is an ordinary 401.
        jdbc.update("UPDATE users SET email = 'elias.dube@example.com' WHERE system_id = ?", managed.getSystemId());
        for (String attempt : List.of("Str0ng-Passw0rd", "null", " ")) {
            MvcResult login = api.login("elias.dube@example.com", attempt);
            assertEquals(401, login.getResponse().getStatus(), login.getResponse().getContentAsString());
            assertEquals("Invalid email or password", message(login));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = '" + managed.getSystemId() + "'"));
    }

    // ==================== HELPERS ====================

    private void assertFieldError(String field, String value, String expectedMessage) throws Exception {
        Map<String, Object> request = AuthApi.registration("patient", "field.rule@example.com");
        request.put(field, value);
        MvcResult result = api.register(request);

        assertEquals(400, result.getResponse().getStatus(), field + ": " + result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText(), field);
        assertEquals(field + ": " + expectedMessage, body.path("message").asText());
        assertEquals(1, body.path("errors").size(), field + " must be reported once: " + body.path("errors"));
        assertEquals(field, body.path("errors").get(0).path("field").asText());
        assertEquals(expectedMessage, body.path("errors").get(0).path("message").asText());
    }

    private static void assertExplicitNull(JsonNode body, String member) {
        assertTrue(body.has(member), member + " must be present");
        assertTrue(body.get(member).isNull(), member + " must be null");
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
