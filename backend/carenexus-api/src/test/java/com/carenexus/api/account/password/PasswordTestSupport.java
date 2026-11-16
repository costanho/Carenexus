package com.carenexus.api.account.password;

import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.auth.provider.HumanVerification;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.event.PasswordChangedEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test support of the password slice: a recorder of {@link PasswordChangedEvent}s (import with
 * {@code @Import(PasswordTestSupport.class)}; the classes that do share one application context), outbox readers
 * and a way to run code with a fixed clock in {@link PasswordResetTokenService}.
 */
@TestConfiguration
public class PasswordTestSupport {

    /** How e2e specs and tests find the link in a stored message body (API contract 5.3). */
    public static final Pattern LINK_TOKEN = Pattern.compile("/auth/reset-password\\?token=([A-Za-z0-9_-]+)");

    @Bean
    public PasswordEventRecorder passwordEventRecorder() {
        return new PasswordEventRecorder();
    }

    /** Replaces the default (no-op) verification for the classes that import this support. */
    @Bean
    @Primary
    public SwitchableHumanVerification switchableHumanVerification() {
        return new SwitchableHumanVerification();
    }

    /**
     * A human-verification provider that passes by default (like {@code none}) and records each call; with
     * {@link #refuse} set it refuses the way a real provider does.
     */
    public static class SwitchableHumanVerification implements HumanVerification {
        public final List<String> calls = new CopyOnWriteArrayList<>();
        public volatile boolean refuse;

        @Override
        public void verify(String action, String token, String ip) {
            calls.add(action + "|" + token + "|" + ip);
            if (refuse) {
                throw new BadRequestException(CODE_FAILED, "Human verification failed. Please try again.");
            }
        }

        public void reset() {
            calls.clear();
            refuse = false;
        }
    }

    /** Records every {@link PasswordChangedEvent}; tests clear it before use. */
    public static class PasswordEventRecorder {
        public final List<PasswordChangedEvent> changed = new CopyOnWriteArrayList<>();

        @EventListener
        public void onPasswordChanged(PasswordChangedEvent event) {
            changed.add(event);
        }
    }

    /** One stored outbox email. */
    public record StoredEmail(int messageId, String to, String recipientSystemId, String templateId, String subject,
                              String body, String dedupeKey) {

        /** The raw token of the link in the body (the body holds exactly one). */
        public String token() {
            Matcher matcher = LINK_TOKEN.matcher(body);
            if (!matcher.find()) {
                throw new AssertionError("No reset link in the body of message " + messageId);
            }
            String token = matcher.group(1);
            if (matcher.find()) {
                throw new AssertionError("More than one reset link in the body of message " + messageId);
            }
            return token;
        }
    }

    /** Email rows of the outbox for one template, oldest first. */
    public static List<StoredEmail> emails(JdbcTemplate jdbc, String templateId) {
        return jdbc.query("SELECT message_id, recipient_address, recipient_system_id, template_id, subject, body, "
                        + "dedupe_key FROM outbound_messages WHERE channel = 'EMAIL' AND template_id = ? "
                        + "ORDER BY message_id",
                (rs, i) -> new StoredEmail(rs.getInt("message_id"), rs.getString("recipient_address"),
                        rs.getString("recipient_system_id"), rs.getString("template_id"), rs.getString("subject"),
                        rs.getString("body"), rs.getString("dedupe_key")),
                templateId);
    }

    /** The newest email of {@code templateId} sent to {@code address}. */
    public static Optional<StoredEmail> latestEmail(JdbcTemplate jdbc, String templateId, String address) {
        List<StoredEmail> all = emails(jdbc, templateId).stream().filter(e -> address.equals(e.to())).toList();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    public static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Audit rows of the password actions, as "ACTION actor->target STATUS", oldest first. */
    public static List<String> auditRows(JdbcTemplate jdbc) {
        return jdbc.query("SELECT action, system_id, target_system_id, status, resource_type FROM audit_log "
                        + "WHERE action IN ('PASSWORD_RESET', 'PASSWORD_CHANGE', 'ADMIN_PASSWORD_RESET') ORDER BY audit_id",
                (rs, i) -> rs.getString("action") + " " + rs.getString("system_id") + "->"
                        + rs.getString("target_system_id") + " " + rs.getString("status") + " "
                        + rs.getString("resource_type"));
    }

    /**
     * Runs {@code body} while {@link PasswordResetTokenService} reads the time from {@code clock}, and restores the
     * application clock afterwards (the bean is shared by every test class of the context).
     */
    public static <T> T withClock(PasswordResetTokenService service, Clock clock, Callable<T> body) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        Object original = ReflectionTestUtils.getField(target, "clock");
        ReflectionTestUtils.setField(target, "clock", clock);
        try {
            return body.call();
        } finally {
            ReflectionTestUtils.setField(target, "clock", original);
        }
    }

    /** A JSON body with the entries in the given order (nulls kept). */
    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
