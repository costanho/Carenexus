package com.carenexus.api.account.email;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixtures of the account email tests: reading links out of the outbox the way e2e specs do, and shaping accounts.
 */
public final class AccountEmailTestSupport {

    public static final String TEMPLATE_VERIFY = "account-email-verify";
    public static final String TEMPLATE_CHANGE_CONFIRM = "account-email-change-confirm";
    public static final String TEMPLATE_CHANGE_NOTICE = "account-email-change-notice";
    public static final String TEMPLATE_CHANGED = "account-email-changed";

    public static final Pattern VERIFY_LINK = Pattern.compile("/auth/verify-email\\?token=([A-Za-z0-9_-]+)");
    public static final Pattern CONFIRM_LINK = Pattern.compile("/auth/confirm-email-change\\?token=([A-Za-z0-9_-]+)");

    private AccountEmailTestSupport() {
    }

    /** Body of the newest message of {@code templateId} queued to {@code address}, or null when there is none. */
    public static String newestBody(JdbcTemplate jdbc, String templateId, String address) {
        List<String> bodies = jdbc.queryForList("SELECT body FROM outbound_messages WHERE template_id = ? "
                + "AND recipient_address = ? ORDER BY message_id DESC LIMIT 1", String.class, templateId, address);
        return bodies.isEmpty() ? null : bodies.get(0);
    }

    public static int countMessages(JdbcTemplate jdbc, String templateId, String address) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages WHERE template_id = ? "
                + "AND recipient_address = ?", Integer.class, templateId, address);
        return count == null ? 0 : count;
    }

    /** The token of the newest verification link sent to {@code address}. */
    public static String verificationToken(JdbcTemplate jdbc, String address) {
        return token(newestBody(jdbc, TEMPLATE_VERIFY, address), VERIFY_LINK);
    }

    /** The token of the newest email change confirmation link sent to {@code address}. */
    public static String confirmationToken(JdbcTemplate jdbc, String address) {
        return token(newestBody(jdbc, TEMPLATE_CHANGE_CONFIRM, address), CONFIRM_LINK);
    }

    private static String token(String body, Pattern link) {
        if (body == null) {
            throw new AssertionError("No message with a link was queued");
        }
        Matcher matcher = link.matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("The message has no link");
        }
        return matcher.group(1);
    }

    /** Marks the account's email as not verified (fixture users are verified). */
    public static User unverify(UserRepository users, User user) {
        User fresh = users.findBySystemId(user.getSystemId()).orElseThrow();
        fresh.setEmailVerifiedAt(null);
        return users.saveAndFlush(fresh);
    }

    public static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    public static int auditRows(JdbcTemplate jdbc, String action, String targetSystemId) {
        return count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE action = ? AND target_system_id = ?",
                action, targetSystemId);
    }
}
