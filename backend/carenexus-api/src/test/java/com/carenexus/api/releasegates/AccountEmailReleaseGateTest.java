package com.carenexus.api.releasegates;

import com.carenexus.api.account.email.AccountEmailTestSupport;
import com.carenexus.api.account.email.config.AccountEmailProperties;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static com.carenexus.api.account.email.AccountEmailTestSupport.auditRows;
import static com.carenexus.api.account.email.AccountEmailTestSupport.confirmationToken;
import static com.carenexus.api.account.email.AccountEmailTestSupport.count;
import static com.carenexus.api.account.email.AccountEmailTestSupport.verificationToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate of be-account-email: verification links are single use and expire, resend is generic, sign-in is
 * blocked until the email is verified only while the property is on, an email change needs the right password, its
 * token works once and confirming it ends every session. Caregiver grants play no part: these are account endpoints,
 * and a caregiver acts only on their own account.
 */
class AccountEmailReleaseGateTest extends CommittedIntegrationTest {

    private static final String PASSWORD = "Str0ng-Passw0rd";

    @Autowired private AccountEmailProperties properties;

    private AuthApi api;
    private boolean requireVerifiedBefore;

    @BeforeEach
    void setUp() {
        api = new AuthApi(mockMvc, objectMapper);
        requireVerifiedBefore = properties.isRequireVerifiedEmail();
    }

    @AfterEach
    void restore() {
        properties.setRequireVerifiedEmail(requireVerifiedBefore);
    }

    @Test
    void verificationLink_isSingleUse_expires_andSignInWaitsForItWhileRequired() throws Exception {
        properties.setRequireVerifiedEmail(true);
        MvcResult registered = api.register(AuthApi.registration("PATIENT", "gate.patient@example.com"));
        assertEquals(201, registered.getResponse().getStatus());
        String systemId = json(registered).path("user").path("system_id").asText();
        assertEquals("PAT-00003", systemId);

        MvcResult blocked = api.login("gate.patient@example.com", PASSWORD);
        assertEquals(403, blocked.getResponse().getStatus());
        assertEquals("EMAIL_NOT_VERIFIED", json(blocked).path("code").asText());

        // Expired link: refused.
        String expired = verificationToken(jdbc, "gate.patient@example.com");
        jdbc.update("UPDATE email_verification_tokens SET expires_at = now() - interval '1 second'");
        assertEquals("TOKEN_INVALID", json(verify(expired)).path("code").asText());

        // A new link works once.
        assertEquals(200, publicPost("/api/auth/email/resend", Map.of("email", "gate.patient@example.com"))
                .getResponse().getStatus());
        String token = verificationToken(jdbc, "gate.patient@example.com");
        assertEquals(200, verify(token).getResponse().getStatus());
        MvcResult reused = verify(token);
        assertEquals(400, reused.getResponse().getStatus());
        assertEquals("TOKEN_INVALID", json(reused).path("code").asText());
        assertEquals(1, auditRows(jdbc, "EMAIL_VERIFIED", systemId));

        MvcResult signedIn = api.login("gate.patient@example.com", PASSWORD);
        assertEquals(200, signedIn.getResponse().getStatus());
        assertTrue(json(signedIn).path("flags").path("emailVerified").asBoolean());
    }

    @Test
    void signIn_isAllowedForUnverifiedAccounts_whenThePropertyIsOff() throws Exception {
        properties.setRequireVerifiedEmail(false);
        for (String role : new String[]{"PATIENT", "PROXY"}) {
            String email = role.toLowerCase() + ".unverified@example.com";
            assertEquals(201, api.register(AuthApi.registration(role, email)).getResponse().getStatus());
            MvcResult login = api.login(email, PASSWORD);
            assertEquals(200, login.getResponse().getStatus(), role);
            assertFalse(json(login).path("flags").path("emailVerified").asBoolean());
        }
    }

    @Test
    void resend_isGeneric() throws Exception {
        api.register(AuthApi.registration("PATIENT", "resend.gate@example.com"));
        MvcResult known = publicPost("/api/auth/email/resend", Map.of("email", "resend.gate@example.com"));
        MvcResult unknown = publicPost("/api/auth/email/resend", Map.of("email", "unknown.gate@example.com"));
        MvcResult verified = publicPost("/api/auth/email/resend", Map.of("email", patient.getEmail()));
        assertEquals(200, known.getResponse().getStatus());
        assertEquals(known.getResponse().getContentAsString(), unknown.getResponse().getContentAsString());
        assertEquals(known.getResponse().getContentAsString(), verified.getResponse().getContentAsString());
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM outbound_messages WHERE recipient_address IN (?, ?)",
                "unknown.gate@example.com", patient.getEmail()));
    }

    @Test
    void emailChange_needsTheRightPassword_tokenWorksOnce_andConfirmingEndsEverySession() throws Exception {
        MvcResult wrongPassword = doPost("/api/users/me/email-change", patient,
                Map.of("currentPassword", "not-my-password-1", "newEmail", "thomas.gate@example.com"));
        assertEquals(400, wrongPassword.getResponse().getStatus());
        assertEquals("INVALID_CURRENT_PASSWORD", json(wrongPassword).path("code").asText());

        JsonNode session = json(api.login(patient.getEmail(), AuthApi.FIXTURE_PASSWORD));
        MvcResult requested = doPost("/api/users/me/email-change", patient,
                Map.of("currentPassword", AuthApi.FIXTURE_PASSWORD, "newEmail", "thomas.gate@example.com"));
        assertEquals(201, requested.getResponse().getStatus());
        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE_REQUESTED", patient.getSystemId()));

        String token = confirmationToken(jdbc, "thomas.gate@example.com");
        MvcResult confirmed = publicPost("/api/auth/email-change/confirm", Map.of("token", token));
        assertEquals(200, confirmed.getResponse().getStatus());
        assertEquals("thomas.gate@example.com", json(confirmed).path("email").asText());
        assertEquals(1, auditRows(jdbc, "EMAIL_CHANGE", patient.getSystemId()));

        assertEquals(401, api.refresh(session.path("tokens").path("refreshToken").asText()).getResponse().getStatus(),
                "the session that existed before the change is revoked");

        MvcResult reused = publicPost("/api/auth/email-change/confirm", Map.of("token", token));
        assertEquals(400, reused.getResponse().getStatus());
        assertEquals("TOKEN_INVALID", json(reused).path("code").asText());

        // Unrelated accounts of every role are untouched.
        for (User other : new User[]{otherPatient, doctor, otherDoctor, caregiver, otherCaregiver, admin}) {
            User reloaded = userRepository.findBySystemId(other.getSystemId()).orElseThrow();
            assertEquals(other.getEmail(), reloaded.getEmail(), other.getSystemId());
            assertEquals(204, doGet("/api/users/me/email-change", other).getResponse().getStatus());
        }
        assertEquals(1, AccountEmailTestSupport.countMessages(jdbc,
                AccountEmailTestSupport.TEMPLATE_CHANGED, "thomas@example.com"));
    }

    private MvcResult verify(String token) throws Exception {
        return publicPost("/api/auth/email/verify", Map.of("token", token));
    }

    private MvcResult publicPost(String url, Object body) throws Exception {
        return api.send(post(url), body, null);
    }
}
