package com.carenexus.api.releasegates;

import com.carenexus.api.account.mfa.MfaFixtures;
import com.carenexus.api.account.mfa.config.MfaProperties;
import com.carenexus.api.account.mfa.service.MfaErrors;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate of be-account-mfa: the self-service endpoints for every role (own account only), the illegal
 * transitions, the 400 envelope, the administrator's status and reset with the 403 of every other role, the audit
 * rows and the shared rate limit.
 */
class AccountMfaReleaseGateTest extends CommittedIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final String BASE = "/api/users/me/mfa";

    @Autowired
    private MfaProperties mfaProperties;

    @Autowired
    private RateLimitProperties rateLimitProperties;

    @Autowired
    private RateLimitGuard rateLimitGuard;

    private String savedRequiredRoles;

    @BeforeEach
    void rememberSettings() {
        savedRequiredRoles = mfaProperties.getMfaRequiredRoles();
    }

    @AfterEach
    void restoreSettings() {
        mfaProperties.setMfaRequiredRoles(savedRequiredRoles);
        rateLimitProperties.setEnabled(false);
        for (User user : List.of(patient, doctor, caregiver, admin, otherPatient)) {
            rateLimitGuard.reset("auth-mfa", user.getSystemId());
        }
    }

    // ==================== HELPERS ====================

    private String setup(User user) throws Exception {
        MvcResult result = doPost(BASE + "/setup", user, null);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("secret").asText();
    }

    private List<String> codes(MvcResult result) throws Exception {
        List<String> codes = new ArrayList<>();
        json(result).path("recoveryCodes").forEach(code -> codes.add(code.asText()));
        return codes;
    }

    private List<String> enable(User user, String secret) throws Exception {
        MvcResult result = doPost(BASE + "/enable", user, Map.of("code", MfaFixtures.currentCode(secret)));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return codes(result);
    }

    private JsonNode status(User user) throws Exception {
        MvcResult result = doGet(BASE, user);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private void assertError(MvcResult result, int status, String code, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        if (code != null) {
            assertEquals(code, body.path("code").asText(), body.toString());
        }
        if (message != null) {
            assertEquals(message, body.path("message").asText());
        }
    }

    private static Map<String, Object> disableBody(String password, String code, String recoveryCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("password", password);
        if (code != null) {
            body.put("code", code);
        }
        if (recoveryCode != null) {
            body.put("recoveryCode", recoveryCode);
        }
        return body;
    }

    // ==================== OWNER LIFE CYCLE, EVERY ROLE ====================

    @Test
    void everyRole_canEnrol_regenerate_andDisable_ownAccountOnly() throws Exception {
        for (User user : List.of(patient, doctor, caregiver, admin)) {
            String id = user.getSystemId();

            JsonNode off = status(user);
            assertFalse(off.path("enabled").asBoolean(), id);
            assertFalse(off.path("required").asBoolean(), id);
            assertEquals(0, off.path("recoveryCodesRemaining").asInt());
            assertTrue(off.has("enabledAt") && off.path("enabledAt").isNull());

            String secret = setup(user);
            assertTrue(secret.matches("^[A-Z2-7]{32}$"));
            assertEquals(secret, setup(user), "setup is idempotent while pending");
            assertFalse(status(user).path("enabled").asBoolean(), "pending is not enabled");

            String enableCode = MfaFixtures.currentCode(secret);
            MvcResult enabled = doPost(BASE + "/enable", user, Map.of("code", enableCode));
            assertEquals(200, enabled.getResponse().getStatus(), enabled.getResponse().getContentAsString());
            List<String> codes = codes(enabled);
            assertEquals(10, codes.size());
            codes.forEach(code -> assertTrue(code.matches(MfaFixtures.RECOVERY_CODE_REGEX), code));

            JsonNode on = status(user);
            assertTrue(on.path("enabled").asBoolean());
            assertEquals(10, on.path("recoveryCodesRemaining").asInt());
            assertTrue(on.path("enabledAt").asText().endsWith("Z"));

            // The code just used cannot be used again; the next step's code can.
            assertError(doPost(BASE + "/recovery-codes/regenerate", user, Map.of("code", enableCode)),
                    400, MfaErrors.CODE_INVALID, MfaErrors.M1_TOTP_INVALID);
            MvcResult regenerated = doPost(BASE + "/recovery-codes/regenerate", user,
                    Map.of("code", MfaFixtures.codeAtOffset(secret, 1)));
            assertEquals(200, regenerated.getResponse().getStatus(), regenerated.getResponse().getContentAsString());
            List<String> newCodes = codes(regenerated);
            assertEquals(10, newCodes.size());
            assertTrue(newCodes.stream().noneMatch(codes::contains), "all codes replaced");

            // Old recovery codes are void; a new one turns MFA off together with the password.
            assertError(doPost(BASE + "/disable", user, disableBody(PASSWORD, null, codes.get(0))),
                    400, MfaErrors.CODE_INVALID, MfaErrors.M2_RECOVERY_INVALID);
            MvcResult disabled = doPost(BASE + "/disable", user, disableBody(PASSWORD, null, newCodes.get(0)));
            assertEquals(200, disabled.getResponse().getStatus(), disabled.getResponse().getContentAsString());
            JsonNode after = json(disabled);
            assertFalse(after.path("enabled").asBoolean());
            assertEquals(0, after.path("recoveryCodesRemaining").asInt());
            assertTrue(after.path("enabledAt").isNull());

            assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM user_mfa WHERE system_id = ?", id));
            assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM mfa_recovery_codes WHERE system_id = ?", id));
            assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_ENABLED", id, "SUCCESS"), id);
            assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_RECOVERY_CODES_REGENERATED", id, "SUCCESS"), id);
            assertEquals(1, MfaFixtures.auditRows(jdbc, "MFA_DISABLED", id, "SUCCESS"), id);
            assertEquals(2, MfaFixtures.auditRows(jdbc, "MFA_FAILURE", id, "FAILURE"), id);
            assertEquals(id, jdbc.queryForObject("SELECT system_id FROM audit_log WHERE action = 'MFA_ENABLED' "
                    + "AND target_system_id = ?", String.class, id), "the owner is the actor");
        }
        // Nothing leaked to an unrelated account.
        assertFalse(status(otherPatient).path("enabled").asBoolean());
        assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE target_system_id = ?",
                otherPatient.getSystemId()));
    }

    @Test
    void enrolment_isPerAccount_andTheSessionFlagFollowsIt() throws Exception {
        String secret = setup(patient);
        enable(patient, secret);

        assertTrue(status(patient).path("enabled").asBoolean());
        assertFalse(status(otherPatient).path("enabled").asBoolean());
        // Another account's code cannot enable mine.
        String otherSecret = setup(otherPatient);
        assertNotEquals(secret, otherSecret);
        assertError(doPost(BASE + "/enable", otherPatient, Map.of("code", MfaFixtures.codeAtOffset(secret, 1))),
                400, MfaErrors.CODE_INVALID, MfaErrors.M1_TOTP_INVALID);

        MvcResult me = doGet("/api/users/me", patient);
        assertTrue(json(me).path("flags").path("mfaEnabled").asBoolean());
        MvcResult otherMe = doGet("/api/users/me", otherPatient);
        assertFalse(json(otherMe).path("flags").path("mfaEnabled").asBoolean());
    }

    @Test
    void disable_withATotpCode_andAWrongPassword() throws Exception {
        String secret = setup(doctor);
        enable(doctor, secret);
        MfaFixtures.forgetLastUsedStep(jdbc, doctor.getSystemId());

        assertError(doPost(BASE + "/disable", doctor, disableBody("wrong-password", MfaFixtures.currentCode(secret), null)),
                400, null, MfaErrors.M9_WRONG_PASSWORD);
        assertTrue(status(doctor).path("enabled").asBoolean());

        MvcResult ok = doPost(BASE + "/disable", doctor, disableBody(PASSWORD, MfaFixtures.currentCode(secret), null));
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        assertFalse(status(doctor).path("enabled").asBoolean());
    }

    // ==================== ILLEGAL TRANSITIONS ====================

    @Test
    void illegalTransitions_are409() throws Exception {
        assertError(doPost(BASE + "/enable", patient, Map.of("code", "123456")), 409, null,
                MfaErrors.M7_SETUP_NOT_STARTED);
        assertError(doPost(BASE + "/disable", patient, disableBody(PASSWORD, "123456", null)), 409, null,
                MfaErrors.M6_NOT_ENABLED);
        assertError(doPost(BASE + "/recovery-codes/regenerate", patient, Map.of("code", "123456")), 409, null,
                MfaErrors.M6_NOT_ENABLED);

        String secret = setup(patient);
        // Pending is not enabled: disable and regenerate are still refused.
        assertError(doPost(BASE + "/recovery-codes/regenerate", patient, Map.of("code", "123456")), 409, null,
                MfaErrors.M6_NOT_ENABLED);
        enable(patient, secret);

        assertError(doPost(BASE + "/setup", patient, null), 409, null, MfaErrors.M5_ALREADY_ENABLED);
        assertError(doPost(BASE + "/enable", patient, Map.of("code", MfaFixtures.codeAtOffset(secret, 1))), 409, null,
                MfaErrors.M5_ALREADY_ENABLED);
    }

    @Test
    void requiredRole_cannotDisable() throws Exception {
        String secret = setup(admin);
        enable(admin, secret);
        mfaProperties.setMfaRequiredRoles("ADMIN");

        JsonNode status = status(admin);
        assertTrue(status.path("required").asBoolean());
        assertError(doPost(BASE + "/disable", admin, disableBody(PASSWORD, MfaFixtures.codeAtOffset(secret, 1), null)),
                409, "MFA_REQUIRED", MfaErrors.M8_REQUIRED_FOR_ROLE);
        assertTrue(status(admin).path("enabled").asBoolean());
        assertEquals(0, MfaFixtures.auditRows(jdbc, "MFA_FAILURE", admin.getSystemId(), "FAILURE"),
                "refused before any credential is checked");
        // Other roles are not affected by the list.
        assertFalse(status(patient).path("required").asBoolean());
    }

    // ==================== VALIDATION ====================

    @Test
    void invalidBodies_are400WithTheEnvelope() throws Exception {
        MvcResult shortCode = doPost(BASE + "/enable", patient, Map.of("code", "12345"));
        assertError(shortCode, 400, "VALIDATION_FAILED", "code: must be 6 digits");
        JsonNode body = json(shortCode);
        assertEquals("code", body.path("errors").get(0).path("field").asText());
        assertEquals(BASE + "/enable", body.path("path").asText());
        assertTrue(body.path("timestamp").asText().endsWith("Z"));

        assertError(doPost(BASE + "/enable", patient, Map.of()), 400, "VALIDATION_FAILED", "code: must be 6 digits");
        assertError(doPost(BASE + "/recovery-codes/regenerate", patient, Map.of("code", "12 456")), 400,
                "VALIDATION_FAILED", "code: must be 6 digits");
        assertError(doPost(BASE + "/disable", patient, disableBody("", "123456", null)), 400, "VALIDATION_FAILED",
                "password: is required");
        assertError(doPost(BASE + "/disable", patient, disableBody(PASSWORD, "123456", "ABCDE-FGHJK")), 400,
                "VALIDATION_FAILED", MfaErrors.M10_CODE_OR_RECOVERY);
        assertError(doPost(BASE + "/disable", patient, disableBody(PASSWORD, null, null)), 400,
                "VALIDATION_FAILED", MfaErrors.M10_CODE_OR_RECOVERY);
        assertError(doPost(BASE + "/disable", patient, disableBody(PASSWORD, null, "AB")), 400,
                "VALIDATION_FAILED", "recoveryCode: must be a recovery code such as ABCDE-FGHJK");
    }

    @Test
    void unauthenticatedCalls_are401() throws Exception {
        assertEquals(401, mockMvc.perform(get(BASE)).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(post(BASE + "/setup")).andReturn().getResponse().getStatus());
        assertEquals(401, mockMvc.perform(get("/api/admin/users/" + patient.getSystemId() + "/mfa"))
                .andReturn().getResponse().getStatus());
    }

    // ==================== ADMINISTRATOR ====================

    @Test
    void admin_readsAndResetsAnotherAccount_andEveryOtherRoleIs403() throws Exception {
        String secret = setup(patient);
        enable(patient, secret);
        String url = "/api/admin/users/" + patient.getSystemId() + "/mfa";

        for (User notAdmin : List.of(patient, doctor, caregiver, otherPatient)) {
            assertError(doGet(url, notAdmin), 403, null, "You don't have permission to access this resource");
            assertError(doPost(url + "/reset", notAdmin, null), 403, null,
                    "You don't have permission to access this resource");
        }
        assertTrue(status(patient).path("enabled").asBoolean(), "nothing changed");

        JsonNode adminView = json(doGet(url, admin));
        assertTrue(adminView.path("enabled").asBoolean());
        assertEquals(10, adminView.path("recoveryCodesRemaining").asInt());
        assertFalse(adminView.path("required").asBoolean());

        MvcResult reset = doPost(url + "/reset", admin, null);
        assertEquals(200, reset.getResponse().getStatus(), reset.getResponse().getContentAsString());
        assertFalse(json(reset).path("enabled").asBoolean());
        assertFalse(status(patient).path("enabled").asBoolean());
        assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM mfa_recovery_codes WHERE system_id = ?",
                patient.getSystemId()));

        // Idempotent and audited on every call, with the administrator as actor.
        assertEquals(200, doPost(url + "/reset", admin, null).getResponse().getStatus());
        assertEquals(2, MfaFixtures.auditRows(jdbc, "MFA_RESET", patient.getSystemId(), "SUCCESS"));
        assertEquals(List.of(admin.getSystemId(), admin.getSystemId()), jdbc.queryForList(
                "SELECT system_id FROM audit_log WHERE action = 'MFA_RESET' AND target_system_id = ?", String.class,
                patient.getSystemId()));

        // The patient can enrol again from scratch, with a new secret.
        assertNotEquals(secret, setup(patient));
    }

    @Test
    void admin_resetOfARequiredRole_forcesEnrolmentAtTheNextSignIn_andSelfResetIsAllowed() throws Exception {
        String secret = setup(admin);
        enable(admin, secret);
        mfaProperties.setMfaRequiredRoles("ADMIN");
        String url = "/api/admin/users/" + admin.getSystemId() + "/mfa";

        JsonNode before = json(doGet(url, admin));
        assertTrue(before.path("required").asBoolean());
        assertTrue(before.path("enabled").asBoolean());

        MvcResult reset = doPost(url + "/reset", admin, null);
        assertEquals(200, reset.getResponse().getStatus());
        JsonNode after = json(reset);
        assertFalse(after.path("enabled").asBoolean());
        assertTrue(after.path("required").asBoolean());

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", admin.getEmail(), "password", PASSWORD))))
                .andReturn();
        JsonNode body = json(login);
        assertEquals("MFA_SETUP", body.path("challenge").path("type").asText(), body.toString());
        assertTrue(body.path("tokens").isNull());
    }

    @Test
    void admin_unknownOrMalformedSystemId() throws Exception {
        assertError(doGet("/api/admin/users/PAT-99999/mfa", admin), 404, null, MfaErrors.M11_USER_NOT_FOUND);
        assertError(doPost("/api/admin/users/PAT-99999/mfa/reset", admin, null), 404, null,
                MfaErrors.M11_USER_NOT_FOUND);
        MvcResult malformed = doGet("/api/admin/users/not-an-id/mfa", admin);
        assertEquals(400, malformed.getResponse().getStatus(), malformed.getResponse().getContentAsString());
        assertEquals("VALIDATION_FAILED", json(malformed).path("code").asText());
        assertTrue(json(malformed).path("message").asText().startsWith("systemId: "));
        assertEquals(0, MfaFixtures.count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'MFA_RESET'"));
    }

    // ==================== RATE LIMIT ====================

    @Test
    void enable_sharesTheAuthMfaBucket_andASuccessResetsIt() throws Exception {
        String secret = setup(caregiver);
        String wrong = MfaFixtures.wrongCode(secret);
        rateLimitProperties.setEnabled(true);
        rateLimitGuard.reset("auth-mfa", caregiver.getSystemId());

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(400, doPost(BASE + "/enable", caregiver, Map.of("code", wrong)).getResponse().getStatus());
        }
        // The fifth call is still allowed and succeeds, which clears the count.
        assertEquals(200, doPost(BASE + "/enable", caregiver, Map.of("code", MfaFixtures.currentCode(secret)))
                .getResponse().getStatus());
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(400, doPost(BASE + "/recovery-codes/regenerate", caregiver, Map.of("code", wrong))
                    .getResponse().getStatus());
        }
        MvcResult limited = doPost(BASE + "/recovery-codes/regenerate", caregiver,
                Map.of("code", MfaFixtures.codeAtOffset(secret, 1)));
        assertError(limited, 429, "RATE_LIMITED", null);
        assertTrue(Long.parseLong(limited.getResponse().getHeader("Retry-After")) > 0);
        // A different account has its own allowance.
        String otherSecret = setup(otherPatient);
        assertEquals(400, doPost(BASE + "/enable", otherPatient, Map.of("code", MfaFixtures.wrongCode(otherSecret)))
                .getResponse().getStatus());
    }
}
