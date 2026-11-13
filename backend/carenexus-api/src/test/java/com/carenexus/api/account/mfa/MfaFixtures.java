package com.carenexus.api.account.mfa;

import com.carenexus.api.account.mfa.crypto.Base32;
import com.carenexus.api.account.mfa.service.TotpService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test helpers of the MFA module: an authenticator app in code, and direct database tweaks that stand in for time
 * passing (tests run against the real clock).
 */
public final class MfaFixtures {

    /** Matches a recovery code as the server returns it. */
    public static final String RECOVERY_CODE_REGEX = "^[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}$";

    private MfaFixtures() {
    }

    /** The current 30-second step of the real clock (the application's Clock is the system clock in tests). */
    public static long currentStep() {
        return Math.floorDiv(System.currentTimeMillis(), TotpService.PERIOD_SECONDS * 1000L);
    }

    /** The code an authenticator app shows now for this Base32 secret. */
    public static String currentCode(String base32Secret) {
        return codeAtOffset(base32Secret, 0);
    }

    /** The code for the current step plus {@code offset} steps (-1, 0 and +1 are accepted by the server). */
    public static String codeAtOffset(String base32Secret, int offset) {
        return TotpService.codeAt(Base32.decode(base32Secret), currentStep() + offset, TotpService.DIGITS);
    }

    /** A 6-digit code that is none of the codes the server accepts now. */
    public static String wrongCode(String base32Secret) {
        for (int candidate = 0; candidate < 1_000_000; candidate += 7919) {
            String code = String.format("%06d", candidate);
            boolean accepted = false;
            for (int offset = -2; offset <= 2; offset++) {
                accepted |= code.equals(codeAtOffset(base32Secret, offset));
            }
            if (!accepted) {
                return code;
            }
        }
        throw new IllegalStateException("no wrong code found");
    }

    /** Lets the next code be accepted again, as if the 30-second step had passed (replay protection only). */
    public static void forgetLastUsedStep(JdbcTemplate jdbc, String systemId) {
        jdbc.update("UPDATE user_mfa SET last_used_step = NULL WHERE system_id = ?", systemId);
    }

    /** Moves every challenge of the account into the past (clinic wall-clock, like the application writes it). */
    public static void expireChallenges(JdbcTemplate jdbc, String systemId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneId.of("Africa/Harare"));
        jdbc.update("UPDATE mfa_challenges SET expires_at = ?, created_at = ? WHERE system_id = ?",
                java.sql.Timestamp.valueOf(now.minusSeconds(1)), java.sql.Timestamp.valueOf(now.minusSeconds(301)),
                systemId);
    }

    public static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** Audit rows of one MFA action about one account. */
    public static int auditRows(JdbcTemplate jdbc, String action, String targetSystemId, String status) {
        return count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_system_id = ? AND status = ? "
                + "AND resource_type = 'ACCOUNT'", action, targetSystemId, status);
    }
}
