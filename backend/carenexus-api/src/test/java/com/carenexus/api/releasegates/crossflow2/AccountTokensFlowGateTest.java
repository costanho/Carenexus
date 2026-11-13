package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.account.password.PasswordTestSupport;
import com.carenexus.api.account.password.PasswordTestSupport.StoredEmail;
import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.account.retention.job.RetentionSweeper;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.CLINIC_ZONE;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.count;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cross-slice gate of account links: an administrator's reset request (be-admin-users) and a caregiver's invitation of
 * a managed dependent (be-caregiver-core) each go through AdminPasswordResetRequestedEvent to the password slice
 * (be-account-password), which stores one token and puts exactly one mail in the outbox (be-foundation-delivery-storage);
 * the link in that mail sets the password and the account signs in with it. The retention sweeper
 * (be-sessions-retention) then deletes used and expired reset, verification, email-change and MFA challenge rows
 * (API-NOTES: used_at set or expires_at passed, no grace; MFA challenges by expiry only) and nothing else.
 */
class AccountTokensFlowGateTest extends CommittedIntegrationTest {

    private static final String RESET = "/api/auth/password/reset";
    private static final String NEW_PASSWORD = "Harvest2026moon";
    private static final AtomicLong HASH_SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private RetentionSweeper retentionSweeper;

    private AuthApi api;

    @BeforeEach
    void setUpApi() {
        api = new AuthApi(mockMvc, objectMapper);
    }

    // ==================== HELPERS ====================

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private List<StoredEmail> mailsTo(String templateId, String address) {
        return PasswordTestSupport.emails(jdbc, templateId).stream().filter(mail -> address.equals(mail.to())).toList();
    }

    private MvcResult reset(String token, String password) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("newPassword", password);
        return api.send(post(RESET), body, null);
    }

    private void assertSignsIn(String email, String password) throws Exception {
        MvcResult login = api.login(email, password);
        assertEquals(200, login.getResponse().getStatus(), login.getResponse().getContentAsString());
    }

    private static String nextHash() {
        return String.format("%064x", HASH_SEQ.incrementAndGet());
    }

    private static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC_ZONE).truncatedTo(ChronoUnit.SECONDS);
    }

    private static OffsetDateTime utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private int resetToken(User user, LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime usedAt) {
        return jdbc.queryForObject("INSERT INTO password_reset_tokens (system_id, token_hash, expires_at, used_at, "
                        + "created_at) VALUES (?, ?, ?, ?, ?) RETURNING token_id", Integer.class,
                user.getSystemId(), nextHash(), expiresAt, usedAt, createdAt);
    }

    private int verificationToken(User user, OffsetDateTime expiresAt, OffsetDateTime usedAt) {
        return jdbc.queryForObject("INSERT INTO email_verification_tokens (system_id, email, token_hash, expires_at, "
                        + "used_at) VALUES (?, ?, ?, ?, ?) RETURNING token_id", Integer.class,
                user.getSystemId(), user.getEmail(), nextHash(), expiresAt, usedAt);
    }

    private int emailChange(User user, OffsetDateTime expiresAt, OffsetDateTime usedAt, OffsetDateTime cancelledAt) {
        return jdbc.queryForObject("INSERT INTO email_change_requests (system_id, new_email, token_hash, expires_at, "
                        + "used_at, cancelled_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING request_id", Integer.class,
                user.getSystemId(), "new." + nextHash().substring(56) + "@example.com", nextHash(), expiresAt, usedAt,
                cancelledAt);
    }

    private int mfaChallenge(User user, LocalDateTime expiresAt) {
        return jdbc.queryForObject("INSERT INTO mfa_challenges (challenge_hash, system_id, purpose, expires_at) "
                + "VALUES (?, ?, 'LOGIN', ?) RETURNING challenge_id", Integer.class, nextHash(), user.getSystemId(),
                expiresAt);
    }

    private List<Integer> ids(String table, String idColumn) {
        return jdbc.queryForList("SELECT " + idColumn + " FROM " + table + " ORDER BY " + idColumn, Integer.class);
    }

    private Map<String, Integer> rowCounts(List<String> tables) {
        Map<String, Integer> counts = new HashMap<>();
        for (String table : tables) {
            counts.put(table, count(jdbc, "SELECT COUNT(*) FROM " + table));
        }
        return counts;
    }

    // ==================== ADMINISTRATOR RESET ====================

    @Test
    void administratorReset_putsOneResetMailInTheOutbox_andItsLinkSetsThePassword() throws Exception {
        JsonNode answer = ok(doPost("/api/admin/users/" + patient.getSystemId() + "/password-reset", admin, null), 200);
        assertNotNull(answer);

        List<StoredEmail> mails = mailsTo(PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail());
        assertEquals(1, mails.size(), mails.toString());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"), "nothing else was queued");
        StoredEmail mail = mails.get(0);
        assertEquals(patient.getSystemId(), mail.recipientSystemId());
        assertTrue(mail.body().lines().anyMatch(("http://localhost:8081/auth/reset-password?token=" + mail.token())::equals),
                "the link stands alone on one line");
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ? "
                + "AND requested_by_system_id = ? AND used_at IS NULL", patient.getSystemId(), admin.getSystemId()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'ADMIN_PASSWORD_RESET' "
                + "AND system_id = ? AND target_system_id = ?", admin.getSystemId(), patient.getSystemId()));
        // Nothing is changed before the link is used
        assertSignsIn(patient.getEmail(), AuthApi.FIXTURE_PASSWORD);

        assertEquals(200, reset(mail.token(), NEW_PASSWORD).getResponse().getStatus());
        assertSignsIn(patient.getEmail(), NEW_PASSWORD);
        assertEquals(401, api.login(patient.getEmail(), AuthApi.FIXTURE_PASSWORD).getResponse().getStatus());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ? "
                + "AND used_at IS NOT NULL", patient.getSystemId()));
        // The link works once
        assertEquals(400, reset(mail.token(), "Another2026sun").getResponse().getStatus());
        assertEquals(1, mailsTo(PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail()).size());
    }

    // ==================== CAREGIVER INVITATION ====================

    @Test
    void caregiverInvitesAManagedDependent_oneInvitationMail_andItsLinkSetsTheFirstPassword() throws Exception {
        Map<String, Object> dependent = new HashMap<>();
        dependent.put("firstName", "Rudo");
        dependent.put("lastName", "Moyo");
        dependent.put("relationship", "mother");
        dependent.put("guardianAttested", true);
        dependent.put("accessLevel", "FULL_ACCESS");
        JsonNode created = ok(doPost("/api/caregiver/dependents", caregiver, dependent), 201);
        int accessId = created.path("accessId").asInt();
        String dependentId = jdbc.queryForObject("SELECT patient_system_id FROM dependent_access WHERE access_id = ?",
                String.class, accessId);
        assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE system_id = ?", String.class, dependentId),
                "a managed dependent has no sign-in yet");

        String email = "rudo.moyo@example.com";
        ok(doPost("/api/caregiver/dependents/" + accessId + "/invite", caregiver, Map.of("email", email)), 200);

        List<StoredEmail> invites = mailsTo(PasswordResetTokenService.TEMPLATE_INVITE, email);
        assertEquals(1, invites.size(), invites.toString());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM outbound_messages"), "nothing else was queued");
        assertTrue(PasswordTestSupport.emails(jdbc, PasswordResetTokenService.TEMPLATE_RESET).isEmpty());
        StoredEmail invite = invites.get(0);
        assertEquals(dependentId, invite.recipientSystemId());
        assertTrue(invite.dedupeKey().startsWith("PASSWORD_INVITE:"));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM password_reset_tokens WHERE system_id = ? "
                + "AND requested_by_system_id = ?", dependentId, caregiver.getSystemId()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = 'DEPENDENT_INVITE' "
                + "AND system_id = ?", caregiver.getSystemId()));

        // Another caregiver cannot invite this dependent
        assertEquals(403, doPost("/api/caregiver/dependents/" + accessId + "/invite", otherCaregiver,
                Map.of("email", "someone@example.com")).getResponse().getStatus());

        assertEquals(200, reset(invite.token(), NEW_PASSWORD).getResponse().getStatus());
        assertNotNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE system_id = ?", String.class,
                dependentId));
        assertSignsIn(email, NEW_PASSWORD);
        // Once the dependent can sign in, the caregiver can no longer invite
        assertEquals(409, doPost("/api/caregiver/dependents/" + accessId + "/invite", caregiver,
                Map.of("email", email)).getResponse().getStatus());
        assertEquals(1, mailsTo(PasswordResetTokenService.TEMPLATE_INVITE, email).size());
    }

    // ==================== RETENTION ====================

    @Test
    void retentionSweeper_deletesUsedAndExpiredAccountLinks_andNothingElse() throws Exception {
        // A real link that was used: requested by the administrator, redeemed by the patient
        ok(doPost("/api/admin/users/" + patient.getSystemId() + "/password-reset", admin, null), 200);
        String token = mailsTo(PasswordResetTokenService.TEMPLATE_RESET, patient.getEmail()).get(0).token();
        assertEquals(200, reset(token, NEW_PASSWORD).getResponse().getStatus());
        int usedByFlow = jdbc.queryForObject("SELECT token_id FROM password_reset_tokens WHERE system_id = ?",
                Integer.class, patient.getSystemId());
        assertSignsIn(patient.getEmail(), NEW_PASSWORD);

        LocalDateTime now = clinicNow();
        OffsetDateTime utc = utcNow();
        // password_reset_tokens: used, expired, live
        int resetExpired = resetToken(doctor, now.minusHours(2), now.minusMinutes(1), null);
        int resetUsedLive = resetToken(doctor, now.minusMinutes(10), now.plusMinutes(30), now.minusMinutes(5));
        int resetLive = resetToken(otherPatient, now.minusMinutes(10), now.plusMinutes(30), null);
        // email_verification_tokens: used, expired, live
        int verifyUsed = verificationToken(caregiver, utc.plusHours(20), utc.minusHours(1));
        int verifyExpired = verificationToken(otherCaregiver, utc.minusMinutes(1), null);
        int verifyLive = verificationToken(otherDoctor, utc.plusHours(20), null);
        // email_change_requests: used, expired, cancelled but not yet expired (kept until it expires), live
        int changeUsed = emailChange(patient, utc.plusHours(20), utc.minusHours(1), null);
        int changeExpired = emailChange(doctor, utc.minusMinutes(1), null, null);
        int changeCancelled = emailChange(otherPatient, utc.plusHours(20), null, utc.minusHours(1));
        int changeLive = emailChange(caregiver, utc.plusHours(20), null, null);
        // mfa_challenges: expired, live
        int mfaExpired = mfaChallenge(admin, now.minusMinutes(1));
        int mfaLive = mfaChallenge(doctor, now.plusMinutes(4));

        List<String> untouchedTables = List.of("users", "patients", "outbound_messages", "audit_log",
                "refresh_tokens", "notifications", "dependent_access", "care_team", "system_settings");
        Map<String, Integer> before = rowCounts(untouchedTables);
        assertTrue(before.get("refresh_tokens") > 0, "the sign-ins left live refresh tokens");

        Map<String, Integer> report = retentionSweeper.sweepWithReport();

        assertEquals(3, report.get(RetentionSweeper.PASSWORD_RESET_TOKENS), report.toString());
        assertEquals(2, report.get(RetentionSweeper.EMAIL_VERIFICATION_TOKENS), report.toString());
        assertEquals(2, report.get(RetentionSweeper.EMAIL_CHANGE_REQUESTS), report.toString());
        assertEquals(1, report.get(RetentionSweeper.MFA_CHALLENGES), report.toString());
        for (String other : List.of(RetentionSweeper.REFRESH_TOKENS, RetentionSweeper.NOTIFICATIONS,
                RetentionSweeper.OUTBOUND_MESSAGES)) {
            assertEquals(0, report.getOrDefault(other, 0), other + " in " + report);
        }

        assertEquals(List.of(resetLive), ids("password_reset_tokens", "token_id"));
        assertFalse(ids("password_reset_tokens", "token_id").contains(usedByFlow));
        assertFalse(ids("password_reset_tokens", "token_id").contains(resetExpired));
        assertFalse(ids("password_reset_tokens", "token_id").contains(resetUsedLive));
        assertEquals(List.of(verifyLive), ids("email_verification_tokens", "token_id"));
        assertFalse(ids("email_verification_tokens", "token_id").contains(verifyUsed));
        assertFalse(ids("email_verification_tokens", "token_id").contains(verifyExpired));
        assertEquals(List.of(changeCancelled, changeLive), ids("email_change_requests", "request_id"));
        assertFalse(ids("email_change_requests", "request_id").contains(changeUsed));
        assertFalse(ids("email_change_requests", "request_id").contains(changeExpired));
        assertEquals(List.of(mfaLive), ids("mfa_challenges", "challenge_id"));
        assertFalse(ids("mfa_challenges", "challenge_id").contains(mfaExpired));

        // Nothing else was touched: accounts, the outbox, the audit trail, sessions, notifications, grants, settings
        assertEquals(before, rowCounts(untouchedTables));
        assertSignsIn(patient.getEmail(), NEW_PASSWORD);

        // A second pass finds nothing
        Map<String, Integer> again = retentionSweeper.sweepWithReport();
        assertEquals(0, again.values().stream().mapToInt(Integer::intValue).sum(), again.toString());
    }
}
