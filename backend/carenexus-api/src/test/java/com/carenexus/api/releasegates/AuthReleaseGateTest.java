package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate for authentication: what must hold before the API may face the internet.
 * <ul>
 *   <li>Only a token this server signed, for this issuer, that has not expired, for an account that is still
 *       active, is accepted. Everything else is 401 in the error envelope.</li>
 *   <li>The caller's role and identity come from the database, not from the token.</li>
 *   <li>Nobody can register an administrator; a clinician cannot sign in before approval.</li>
 *   <li>A refresh token works once; replaying one ends every session of the account.</li>
 *   <li>Deactivation takes effect on the next request.</li>
 * </ul>
 */
class AuthReleaseGateTest extends CommittedIntegrationTest {

    private static final String ISSUER = "carenexus-api";
    private static final String PASSWORD = "password123";

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ==================== ACCESS TOKENS ====================

    @Test
    void forgedExpiredOtherIssuerUnsignedAndTamperedTokens_areAll401() throws Exception {
        SecretKey realKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "an-attackers-own-signing-key-that-is-long-enough-for-hs512-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        String valid = token(realKey, ISSUER, now.plusSeconds(600), admin, "ADMIN");
        String forged = token(attackerKey, ISSUER, now.plusSeconds(600), admin, "ADMIN");
        String expired = token(realKey, ISSUER, now.minusSeconds(5), admin, "ADMIN");
        String otherIssuer = token(realKey, "some-other-service", now.plusSeconds(600), admin, "ADMIN");
        String noIssuer = token(realKey, null, now.plusSeconds(600), admin, "ADMIN");
        String unsigned = Jwts.builder().claims(claims(admin, "ADMIN")).subject(admin.getEmail()).issuer(ISSUER)
                .expiration(Date.from(now.plusSeconds(600))).compact();
        String tampered = withPayloadField(token(realKey, ISSUER, now.plusSeconds(600), patient, "PATIENT"),
                "systemId", admin.getSystemId());

        // The control: the same claims, properly signed, are accepted.
        assertEquals(200, withBearer("/api/users/me", valid).getResponse().getStatus());
        assertEquals(200, withBearer("/api/admin/users", valid).getResponse().getStatus());

        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("signed with another key", forged);
        rejected.put("expired", expired);
        rejected.put("issued by another service", otherIssuer);
        rejected.put("without an issuer", noIssuer);
        rejected.put("unsigned (alg none)", unsigned);
        rejected.put("payload changed after signing", tampered);
        rejected.put("not a JWT", "not-a-jwt");
        for (Map.Entry<String, String> entry : rejected.entrySet()) {
            for (String path : List.of("/api/users/me", "/api/admin/users")) {
                MvcResult result = withBearer(path, entry.getValue());
                assertEquals(401, result.getResponse().getStatus(), entry.getKey() + " on " + path);
                JsonNode body = json(result);
                assertEquals("Authentication required", body.path("message").asText(), entry.getKey());
                assertEquals("Unauthorized", body.path("error").asText());
                assertEquals(path, body.path("path").asText());
            }
        }

        // No token at all, and a refresh token used as a bearer token.
        assertEquals(401, mockMvc.perform(get("/api/users/me")).andReturn().getResponse().getStatus());
        String refreshToken = json(login(patient)).path("tokens").path("refreshToken").asText();
        assertEquals(401, withBearer("/api/users/me", refreshToken).getResponse().getStatus());
    }

    @Test
    void roleAndIdentity_comeFromTheDatabase_notFromTheToken() throws Exception {
        SecretKey realKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        // A correctly signed token whose role claim says ADMIN, for an account that is a patient.
        String inflated = token(realKey, ISSUER, Instant.now().plusSeconds(600), patient, "ADMIN");

        MvcResult me = withBearer("/api/users/me", inflated);
        assertEquals(200, me.getResponse().getStatus());
        assertEquals("patient", json(me).path("role").asText());
        assertEquals("PAT-00001", json(me).path("system_id").asText());
        assertEquals(403, withBearer("/api/admin/users", inflated).getResponse().getStatus());

        // Every role reads its own account, and only its own: the endpoint takes no id.
        for (User user : List.of(patient, doctor, caregiver, admin)) {
            JsonNode body = json(doGet("/api/users/me", user));
            assertEquals(user.getSystemId(), body.path("system_id").asText());
            assertEquals(user.getEmail(), body.path("email").asText());
            assertTrue(body.path("flags").isObject());
        }
        assertEquals("doctor", json(doGet("/api/users/me", doctor)).path("role").asText());
        assertEquals("proxy", json(doGet("/api/users/me", caregiver)).path("role").asText());
        assertEquals("admin", json(doGet("/api/users/me", admin)).path("role").asText());
    }

    // ==================== REGISTRATION ====================

    @Test
    void administratorsCannotBeRegistered_andEveryRoleIsStoredWithItsProfileRow() throws Exception {
        MvcResult adminAttempt = register("admin", "gate.admin@example.com");
        assertEquals(400, adminAttempt.getResponse().getStatus());
        assertEquals("Invalid role. Allowed roles are patient, doctor and proxy.", message(adminAttempt));
        // Not even with a signed-in administrator's token: the endpoint ignores the caller.
        MvcResult asAdmin = doPost("/api/auth/register", admin, registration("ADMIN", "gate.admin@example.com"));
        assertEquals(400, asAdmin.getResponse().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'"));

        assertEquals(201, register("patient", "gate.patient@example.com").getResponse().getStatus());
        assertEquals(201, register("doctor", "gate.doctor@example.com").getResponse().getStatus());
        assertEquals(201, register("proxy", "gate.proxy@example.com").getResponse().getStatus());

        assertEquals(1, count("SELECT COUNT(*) FROM patients p JOIN users u ON u.system_id = p.system_id "
                + "WHERE u.email = 'gate.patient@example.com' AND u.role = 'PATIENT' AND u.is_active"));
        assertEquals(1, count("SELECT COUNT(*) FROM doctors d JOIN users u ON u.system_id = d.system_id "
                + "WHERE u.email = 'gate.doctor@example.com' AND u.role = 'DOCTOR' AND NOT u.is_active "
                + "AND NOT d.is_active AND d.verification_status = 'PENDING'"));
        assertEquals(1, count("SELECT COUNT(*) FROM caregivers c JOIN users u ON u.system_id = c.system_id "
                + "WHERE u.email = 'gate.proxy@example.com' AND u.role = 'CAREGIVER' AND u.is_active"));
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log WHERE action = 'REGISTER' AND resource_type = 'ACCOUNT' "
                + "AND status = 'SUCCESS' AND system_id = target_system_id"));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens"), "registration issues no session");

        // A clinician cannot sign in before an administrator approved the account; the others can.
        MvcResult pendingDoctor = login("gate.doctor@example.com", "Str0ng-Passw0rd");
        assertEquals(403, pendingDoctor.getResponse().getStatus());
        assertEquals("ACCOUNT_PENDING_VERIFICATION", json(pendingDoctor).path("code").asText());
        assertEquals(200, login("gate.patient@example.com", "Str0ng-Passw0rd").getResponse().getStatus());
        assertEquals("proxy", json(login("gate.proxy@example.com", "Str0ng-Passw0rd")).path("user").path("role").asText());
    }

    @Test
    void duplicateAccount_is409_andAnInvalidField_is400WithTheValidationEnvelope() throws Exception {
        MvcResult duplicate = register("patient", "thomas@example.com");
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("Email already registered", message(duplicate));

        Map<String, Object> invalid = registration("patient", "gate.invalid@example.com");
        invalid.put("password", "weak");
        MvcResult result = postJson("/api/auth/register", invalid);
        assertEquals(400, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("VALIDATION_FAILED", body.path("code").asText());
        assertEquals("Bad Request", body.path("error").asText());
        assertEquals("/api/auth/register", body.path("path").asText());
        assertEquals("password", body.path("errors").get(0).path("field").asText());
        assertTrue(body.path("message").asText().startsWith("password: "));
        assertTrue(body.path("timestamp").asText().endsWith("Z"), "an ISO instant in UTC");
        assertDoesNotThrow(() -> Instant.parse(body.path("timestamp").asText()));
        assertEquals(0, count("SELECT COUNT(*) FROM users WHERE email = 'gate.invalid@example.com'"));
    }

    // ==================== SESSIONS ====================

    @Test
    void aRefreshTokenWorksOnce_andReplayingItEndsEverySessionOfTheAccount() throws Exception {
        JsonNode first = json(login(patient));
        JsonNode otherDevice = json(login(patient));
        JsonNode unrelated = json(login(otherPatient));
        String stolen = first.path("tokens").path("refreshToken").asText();

        JsonNode rotated = json(refresh(stolen));
        assertEquals(first.path("tokens").path("sessionId").asText(), rotated.path("tokens").path("sessionId").asText());

        MvcResult replay = refresh(stolen);
        assertEquals(401, replay.getResponse().getStatus());
        assertEquals("Refresh token reuse detected. Please sign in again.", message(replay));

        // The family, and every other session of that account, is gone; other accounts are untouched.
        assertEquals(401, refresh(rotated.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(401, refresh(otherDevice.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(200, refresh(unrelated.path("tokens").path("refreshToken").asText()).getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'PAT-00001' AND NOT is_revoked"));

        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'TOKEN_REUSE' AND status = 'FAILURE' "
                + "AND target_system_id = 'PAT-00001'"));
        assertEquals(3, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN' AND status = 'SUCCESS'"));
        // No token, password or email in the audit log.
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE COALESCE(error_message, '') LIKE '%@%' "
                + "OR COALESCE(request_path, '') LIKE '%" + stolen + "%'"));
    }

    @Test
    void deactivationIsImmediate_forTheAccessTokenAndTheRefreshToken() throws Exception {
        JsonNode session = json(login(doctor));
        String accessToken = session.path("tokens").path("accessToken").asText();
        assertEquals(200, withBearer("/api/users/me", accessToken).getResponse().getStatus());

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", doctor.getSystemId());

        assertEquals(401, withBearer("/api/users/me", accessToken).getResponse().getStatus());
        MvcResult refresh = refresh(session.path("tokens").path("refreshToken").asText());
        assertEquals(401, refresh.getResponse().getStatus());
        assertEquals("User account is inactive", message(refresh));
        // And the password no longer opens a session.
        MvcResult login = login(doctor);
        assertEquals(400, login.getResponse().getStatus());
        assertEquals("Account is inactive", message(login));
        assertFalse(json(login).has("tokens"));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'DOC-00001' AND NOT is_revoked"));
    }

    // ==================== HELPERS ====================

    private String token(SecretKey key, String issuer, Instant expiresAt, User user, String roleClaim) {
        var builder = Jwts.builder()
                .claims(claims(user, roleClaim))
                .subject(user.getEmail())
                .issuedAt(Date.from(expiresAt.minusSeconds(900)))
                .expiration(Date.from(expiresAt));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.signWith(key, Jwts.SIG.HS512).compact();
    }

    private static Map<String, Object> claims(User user, String roleClaim) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", user.getUserId());
        claims.put("systemId", user.getSystemId());
        claims.put("role", roleClaim);
        claims.put("email", user.getEmail());
        return claims;
    }

    /** The same header and signature with one payload field changed: the signature no longer matches. */
    private String withPayloadField(String jwt, String field, String value) throws Exception {
        String[] parts = jwt.split("\\.");
        ObjectNode payload = (ObjectNode) objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        payload.put(field, value);
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload));
        return parts[0] + "." + changed + "." + parts[2];
    }

    private MvcResult withBearer(String path, String bearerToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + bearerToken)).andReturn();
    }

    private MvcResult postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private MvcResult login(User user) throws Exception {
        return login(user.getEmail(), PASSWORD);
    }

    private MvcResult login(String email, String password) throws Exception {
        return postJson("/api/auth/login", Map.of("email", email, "password", password));
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return postJson("/api/auth/refresh", Map.of("refreshToken", refreshToken));
    }

    private MvcResult register(String role, String email) throws Exception {
        return postJson("/api/auth/register", registration(role, email));
    }

    private static Map<String, Object> registration(String role, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Gate");
        body.put("lastName", "Check");
        body.put("email", email);
        body.put("password", "Str0ng-Passw0rd");
        body.put("role", role);
        return body;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
