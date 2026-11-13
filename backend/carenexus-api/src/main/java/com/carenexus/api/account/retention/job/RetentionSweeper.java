package com.carenexus.api.account.retention.job;

import com.carenexus.api.account.retention.config.RetentionProperties;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.job.JobSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deletes operational rows whose retention has run out (job {@code retention}, every
 * {@code carenexus.jobs.retention.interval-ms}, default one hour).
 *
 * <pre>
 * data class                 table                      a row is deleted when
 * refresh tokens             refresh_tokens             expires_at, or revoked_at of a revoked row, is older than
 *                                                       tokens-days (30); the first row of a session that still has
 *                                                       an active token is kept (it holds the sign-in time)
 * password reset tokens      password_reset_tokens      used_at is set or expires_at has passed
 * email verification tokens  email_verification_tokens  used_at is set or expires_at has passed
 * email change requests      email_change_requests      used_at is set or expires_at has passed
 * MFA challenges             mfa_challenges             expires_at has passed
 * idempotency keys           idempotency_keys           expires_at has passed (IdempotencyService.purgeExpired)
 * notifications              notifications              read and created more than read-notifications-days (180)
 *                                                       ago, or expires_at has passed (read or unread)
 * outbound messages          outbound_messages          SENT or SKIPPED and sent_at older than outbound-messages-days (30)
 * call jobs                  call_jobs                  SUCCEEDED, FAILED or CANCELLED and completed_at older than
 *                                                       call-records-days (30)
 * provider webhook events    provider_events            received_at older than call-records-days (30)
 * </pre>
 * Never touched: audit_log, clinical tables, users, unread notifications without a passed expiry, PENDING and FAILED
 * outbound messages, PENDING and RUNNING call jobs, call sessions and participants, push devices, MFA secrets and
 * recovery codes, legal acceptances, consent records, account requests.
 *
 * Every cutoff comes from the injected {@link Clock}: {@code LocalDateTime.now(clock)} for {@code TIMESTAMP} columns
 * and {@code clock.instant()} for {@code TIMESTAMPTZ} columns (the column type is read from the catalogue, so a
 * table owned by another module is compared correctly whichever type it uses). Each table is guarded with
 * {@code to_regclass}: a table that does not exist (yet) is skipped silently.
 *
 * Rows are deleted in batches of {@value #BATCH_SIZE} per statement, each batch in its own transaction with
 * {@code FOR UPDATE SKIP LOCKED}, until a data class is drained (at most {@value #MAX_BATCHES_PER_CLASS} batches per
 * class and run; the rest waits for the next run). The whole pass runs inside {@link JobSupport#runExclusive}, so
 * only one API instance sweeps at a time. A failing data class is logged and the others still run. The sweeper
 * writes no audit rows and publishes no events or notifications.
 */
@Slf4j
@Component
public class RetentionSweeper {

    public static final String JOB_NAME = "retention";

    static final int BATCH_SIZE = 200;
    static final int MAX_BATCHES_PER_CLASS = 500;

    // Data classes, reported by sweepWithReport()
    public static final String REFRESH_TOKENS = "refresh_tokens";
    public static final String PASSWORD_RESET_TOKENS = "password_reset_tokens";
    public static final String EMAIL_VERIFICATION_TOKENS = "email_verification_tokens";
    public static final String EMAIL_CHANGE_REQUESTS = "email_change_requests";
    public static final String MFA_CHALLENGES = "mfa_challenges";
    public static final String IDEMPOTENCY_KEYS = "idempotency_keys";
    public static final String NOTIFICATIONS = "notifications";
    public static final String OUTBOUND_MESSAGES = "outbound_messages";
    public static final String CALL_JOBS = "call_jobs";
    public static final String PROVIDER_EVENTS = "provider_events";

    /** Account link tables of other modules: deleted once used or expired. */
    private static final List<String> ACCOUNT_LINK_TABLES =
            List.of(PASSWORD_RESET_TOKENS, EMAIL_VERIFICATION_TOKENS, EMAIL_CHANGE_REQUESTS);

    private static final String TIMESTAMPTZ = "timestamptz";

    private final JobSupport jobs;
    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotencyService;
    private final RetentionProperties properties;
    private final Clock clock;
    private final TransactionTemplate perBatch;

    public RetentionSweeper(JobSupport jobs, JdbcTemplate jdbc, IdempotencyService idempotencyService,
                            RetentionProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.clock = clock;
        this.perBatch = new TransactionTemplate(transactionManager);
        this.perBatch.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.retention.interval-ms:3600000}",
            initialDelayString = "${carenexus.jobs.retention.interval-ms:3600000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // JobSupport already counted and logged the failed run; the next tick tries again.
            log.debug("Retention sweep ended early: {}", e.getClass().getName());
        }
    }

    /**
     * One pass over every data class.
     *
     * @return the number of rows deleted; 0 also when another instance holds the job lock
     */
    public int sweepOnce() {
        return sweepWithReport().values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * One pass over every data class.
     *
     * @return rows deleted per data class (table name), in sweep order; a skipped (missing) table is absent; empty
     *         when another instance holds the job lock
     */
    public Map<String, Integer> sweepWithReport() {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        jobs.runExclusive(JOB_NAME, () -> sweepAll(deleted));
        Map<String, Integer> result = Collections.unmodifiableMap(deleted);
        int total = result.values().stream().mapToInt(Integer::intValue).sum();
        if (total > 0) {
            log.info("Retention sweep deleted {} row(s): {}", total, result);
        }
        return result;
    }

    private void sweepAll(Map<String, Integer> deleted) {
        Map<String, Optional<String>> columnTypes = new HashMap<>();

        run(deleted, REFRESH_TOKENS, () -> purgeRefreshTokens(columnTypes));
        for (String table : ACCOUNT_LINK_TABLES) {
            run(deleted, table, () -> purgeAccountLinks(table, columnTypes));
        }
        run(deleted, MFA_CHALLENGES, () -> purgeWhere(MFA_CHALLENGES, "t", "t.expires_at < ?",
                cutoff(MFA_CHALLENGES, "expires_at", 0, columnTypes)));
        run(deleted, IDEMPOTENCY_KEYS, () -> tableExists(IDEMPOTENCY_KEYS)
                ? Optional.of(idempotencyService.purgeExpired())
                : Optional.empty());
        run(deleted, NOTIFICATIONS, () -> purgeWhere(NOTIFICATIONS, "t",
                "(t.is_read = TRUE AND t.created_at < ?) OR (t.expires_at IS NOT NULL AND t.expires_at < ?)",
                cutoff(NOTIFICATIONS, "created_at", properties.getReadNotificationsDays(), columnTypes),
                cutoff(NOTIFICATIONS, "expires_at", 0, columnTypes)));
        run(deleted, OUTBOUND_MESSAGES, () -> purgeWhere(OUTBOUND_MESSAGES, "t",
                "t.status IN ('SENT', 'SKIPPED') AND t.sent_at < ?",
                cutoff(OUTBOUND_MESSAGES, "sent_at", properties.getOutboundMessagesDays(), columnTypes)));
        run(deleted, CALL_JOBS, () -> purgeWhere(CALL_JOBS, "t",
                "t.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND t.completed_at < ?",
                cutoff(CALL_JOBS, "completed_at", properties.getCallRecordsDays(), columnTypes)));
        run(deleted, PROVIDER_EVENTS, () -> purgeWhere(PROVIDER_EVENTS, "t", "t.received_at < ?",
                cutoff(PROVIDER_EVENTS, "received_at", properties.getCallRecordsDays(), columnTypes)));
    }

    /** Runs one data class; a failure is logged and does not stop the others. */
    private void run(Map<String, Integer> deleted, String dataClass, DataClassPurge purge) {
        try {
            purge.purge().ifPresent(count -> deleted.put(dataClass, count));
        } catch (RuntimeException e) {
            log.warn("Retention of {} failed: {}", dataClass, e.getClass().getName());
        }
    }

    @FunctionalInterface
    private interface DataClassPurge {
        /** Rows deleted, or empty when the table is missing and was skipped. */
        Optional<Integer> purge();
    }

    // ==================== DATA CLASSES ====================

    /**
     * Expired, or revoked, more than tokens-days ago. The first row of a session that still has an active token is
     * kept so the session list can report when that session was signed in to.
     */
    private Optional<Integer> purgeRefreshTokens(Map<String, Optional<String>> columnTypes) {
        if (!tableExists(REFRESH_TOKENS)) {
            return Optional.empty();
        }
        int days = properties.getTokensDays();
        String predicate = "(t.expires_at < ? OR (t.is_revoked = TRUE AND t.revoked_at < ?)) "
                + "AND NOT (t.token_id = (SELECT MIN(f.token_id) FROM refresh_tokens f WHERE f.session_id = t.session_id) "
                + "AND EXISTS (SELECT 1 FROM refresh_tokens a WHERE a.session_id = t.session_id "
                + "AND a.is_revoked = FALSE AND a.expires_at > ?))";
        return Optional.of(drain(REFRESH_TOKENS, "t", predicate,
                cutoff(REFRESH_TOKENS, "expires_at", days, columnTypes),
                cutoff(REFRESH_TOKENS, "revoked_at", days, columnTypes),
                cutoff(REFRESH_TOKENS, "expires_at", 0, columnTypes)));
    }

    /** Used ({@code used_at} set) or expired; no extra window. */
    private Optional<Integer> purgeAccountLinks(String table, Map<String, Optional<String>> columnTypes) {
        if (!tableExists(table)) {
            return Optional.empty();
        }
        if (columnType(table, "expires_at", columnTypes).isEmpty()) {
            log.warn("Retention skipped {}: no expires_at column", table);
            return Optional.empty();
        }
        Object now = cutoff(table, "expires_at", 0, columnTypes);
        if (columnType(table, "used_at", columnTypes).isPresent()) {
            return Optional.of(drain(table, "t", "t.used_at IS NOT NULL OR t.expires_at < ?", now));
        }
        return Optional.of(drain(table, "t", "t.expires_at < ?", now));
    }

    /** Deletes the rows of an existing table that match {@code predicate}; empty when the table is missing. */
    private Optional<Integer> purgeWhere(String table, String alias, String predicate, Object... params) {
        if (!tableExists(table)) {
            return Optional.empty();
        }
        return Optional.of(drain(table, alias, predicate, params));
    }

    // ==================== PLUMBING ====================

    /**
     * Deletes matching rows in batches of {@value #BATCH_SIZE}, each batch in its own transaction, until fewer than
     * a full batch is found (or {@value #MAX_BATCHES_PER_CLASS} batches ran). Rows locked by another transaction
     * are skipped and picked up by a later run.
     *
     * {@code table}, {@code alias} and {@code predicate} are constants of this class, never client input.
     */
    private int drain(String table, String alias, String predicate, Object... params) {
        String sql = "DELETE FROM " + table + " WHERE ctid = ANY(ARRAY("
                + "SELECT " + alias + ".ctid FROM " + table + " " + alias
                + " WHERE " + predicate
                + " LIMIT " + BATCH_SIZE + " FOR UPDATE OF " + alias + " SKIP LOCKED))";
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_CLASS; batch++) {
            Integer removed = perBatch.execute(tx -> jdbc.update(sql, params));
            int count = removed == null ? 0 : removed;
            total += count;
            if (count < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * The point in time {@code days} whole days before now, typed for the column: an {@link OffsetDateTime} (UTC)
     * for a {@code TIMESTAMPTZ} column, a clinic wall-clock {@link LocalDateTime} for a {@code TIMESTAMP} column.
     */
    private Object cutoff(String table, String column, int days, Map<String, Optional<String>> columnTypes) {
        boolean withZone = columnType(table, column, columnTypes).map(TIMESTAMPTZ::equals).orElse(false);
        if (withZone) {
            return OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofDays(days)), ZoneOffset.UTC);
        }
        return LocalDateTime.now(clock).minusDays(days);
    }

    /** The type name ({@code pg_type.typname}) of a column, or empty when the table or column does not exist. */
    private Optional<String> columnType(String table, String column, Map<String, Optional<String>> cache) {
        return cache.computeIfAbsent(table + "." + column, key -> jdbc.queryForList(
                        "SELECT t.typname FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid "
                                + "WHERE a.attrelid = to_regclass(?) AND a.attname = ? AND NOT a.attisdropped",
                        String.class, table, column)
                .stream()
                .findFirst());
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }
}
