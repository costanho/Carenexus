package com.carenexus.api.account.retention;

import com.carenexus.api.account.retention.config.RetentionProperties;
import com.carenexus.api.account.retention.job.RetentionSweeper;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retention sweeper, driven with a fixed clock: one test per data class (rows just past the window go, rows
 * just inside it stay), the tables it must never touch, batching, locking, and the account-link tables shipped by V26 to V28.
 *
 * Runs without a test transaction: the sweeper deletes in its own transactions, so fixtures must be committed.
 */
class RetentionSweeperTest extends CommittedIntegrationTest {

    /** 2026-11-10 10:00 in the clinic zone. */
    private static final Instant FIXED = Instant.parse("2026-11-10T08:00:00Z");
    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Clock clock;
    private LocalDateTime now;
    private RetentionSweeper sweeper;

    @BeforeEach
    void setUpSweeper() {
        clock = Clock.fixed(FIXED, CLINIC);
        now = LocalDateTime.now(clock);
        sweeper = sweeperWith(new RetentionProperties());
    }

    private RetentionSweeper sweeperWith(RetentionProperties properties) {
        IdempotencyService idempotency = new IdempotencyService(jdbc, objectMapper, authorizationService, clock,
                transactionManager);
        return new RetentionSweeper(jobSupport, jdbc, idempotency, properties, clock, transactionManager);
    }

    // ==================== refresh_tokens ====================

    @Test
    void refreshTokens_expiredOrRevokedMoreThan30DaysAgo_areDeleted() {
        int expiredLongAgo = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(38), now.minusDays(31), null);
        int expiredRecently = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(36), now.minusDays(29), null);
        int revokedLongAgo = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(32), now.minusDays(25),
                now.minusDays(31));
        int revokedRecently = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(30), now.plusDays(1),
                now.minusDays(29));
        int active = token(doctor.getSystemId(), UUID.randomUUID(), now.minusDays(2), now.plusDays(5), null);

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(2, report.get(RetentionSweeper.REFRESH_TOKENS));
        assertEquals(List.of(expiredRecently, revokedRecently, active), tokenIds());
        assertFalse(tokenIds().contains(expiredLongAgo));
        assertFalse(tokenIds().contains(revokedLongAgo));
    }

    @Test
    void refreshTokens_firstRowOfAStillActiveSessionIsKept_soTheSignInTimeSurvives() {
        UUID longLived = UUID.randomUUID();
        int signIn = token(patient.getSystemId(), longLived, now.minusDays(45), now.minusDays(38), now.minusDays(40));
        int rotated = token(patient.getSystemId(), longLived, now.minusDays(40), now.minusDays(33), now.minusDays(35));
        int current = token(patient.getSystemId(), longLived, now.minusDays(1), now.plusDays(6), null);

        UUID ended = UUID.randomUUID();
        int endedSignIn = token(patient.getSystemId(), ended, now.minusDays(45), now.minusDays(38), now.minusDays(40));
        int endedLast = token(patient.getSystemId(), ended, now.minusDays(40), now.minusDays(33), now.minusDays(35));

        sweeper.sweepOnce();

        List<Integer> left = tokenIds();
        assertTrue(left.contains(signIn), "first row of an active session is kept");
        assertTrue(left.contains(current));
        assertFalse(left.contains(rotated));
        assertFalse(left.contains(endedSignIn), "an ended session is not protected");
        assertFalse(left.contains(endedLast));
    }

    @Test
    void refreshTokens_windowComesFromTheProperty() {
        RetentionProperties properties = new RetentionProperties();
        properties.setTokensDays(10);
        int expired12DaysAgo = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(19), now.minusDays(12), null);
        int expired8DaysAgo = token(patient.getSystemId(), UUID.randomUUID(), now.minusDays(15), now.minusDays(8), null);

        sweeperWith(properties).sweepOnce();

        assertEquals(List.of(expired8DaysAgo), tokenIds());
        assertFalse(tokenIds().contains(expired12DaysAgo));
    }

    // ==================== account link tables of other modules ====================

    @Test
    void accountLinkTables_shippedByV26ToV28_areSweptAndReported() {
        List<String> tables = List.of(RetentionSweeper.PASSWORD_RESET_TOKENS, RetentionSweeper.EMAIL_VERIFICATION_TOKENS,
                RetentionSweeper.EMAIL_CHANGE_REQUESTS, RetentionSweeper.MFA_CHALLENGES);
        int provider = providerEvent("evt-old", OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(31)), ZoneOffset.UTC));

        Map<String, Integer> report = sweeper.sweepWithReport();

        for (String table : tables) {
            // V26 to V28 ship these tables, so the sweeper must reach and report every one of them. Their deletion
            // rules (used or expired, MFA challenges by expiry only) are asserted against the merged schema by
            // releasegates.crossflow2.AccountTokensFlowGateTest.
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table),
                    table + " exists");
            assertTrue(report.containsKey(table), table + " is in the sweep report");
        }
        // The other data classes still ran
        assertEquals(1, report.get(RetentionSweeper.PROVIDER_EVENTS));
        assertFalse(providerEventIds().contains(provider));
    }

    // ==================== idempotency_keys ====================

    @Test
    void idempotencyKeys_pastExpiry_areDeleted() {
        jdbc.update("INSERT INTO idempotency_keys (owner_system_id, scope, idem_key, response_json, created_at, expires_at) "
                + "VALUES (?, 'POST /api/test', 'expired-key-1', '{}', ?, ?)", patient.getSystemId(),
                now.minusHours(25), now.minusHours(1));
        jdbc.update("INSERT INTO idempotency_keys (owner_system_id, scope, idem_key, response_json, created_at, expires_at) "
                + "VALUES (?, 'POST /api/test', 'live-key-01', '{}', ?, ?)", patient.getSystemId(),
                now.minusHours(1), now.plusHours(23));

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(1, report.get(RetentionSweeper.IDEMPOTENCY_KEYS));
        assertEquals(List.of("live-key-01"),
                jdbc.queryForList("SELECT idem_key FROM idempotency_keys", String.class));
    }

    // ==================== notifications ====================

    @Test
    void notifications_readOlderThan180Days_orPastExpiry_areDeleted() {
        int readOld = notification(true, now.minusDays(181), null);
        int readRecent = notification(true, now.minusDays(179), null);
        int unreadOld = notification(false, now.minusDays(400), null);
        int unreadExpired = notification(false, now.minusDays(1), now.minusMinutes(1));
        int readExpired = notification(true, now.minusDays(2), now.minusMinutes(1));
        int unreadNotYetExpired = notification(false, now.minusDays(1), now.plusDays(1));

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(3, report.get(RetentionSweeper.NOTIFICATIONS));
        List<Integer> left = jdbc.queryForList("SELECT notification_id FROM notifications ORDER BY notification_id",
                Integer.class);
        assertEquals(List.of(readRecent, unreadOld, unreadNotYetExpired), left);
        assertFalse(left.contains(readOld));
        assertFalse(left.contains(unreadExpired));
        assertFalse(left.contains(readExpired));
    }

    // ==================== outbound_messages ====================

    @Test
    void outboundMessages_sentOrSkippedMoreThan30DaysAgo_areDeleted() {
        OffsetDateTime old = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(31)), ZoneOffset.UTC);
        OffsetDateTime recent = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(29)), ZoneOffset.UTC);
        int sentOld = outbound("SENT", old, old);
        int skippedOld = outbound("SKIPPED", old, old);
        int sentRecent = outbound("SENT", recent, recent);
        int failedOld = outbound("FAILED", null, old);
        int pendingOld = outbound("PENDING", null, old);

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(2, report.get(RetentionSweeper.OUTBOUND_MESSAGES));
        List<Integer> left = jdbc.queryForList("SELECT message_id FROM outbound_messages ORDER BY message_id",
                Integer.class);
        assertEquals(List.of(sentRecent, failedOld, pendingOld), left);
        assertFalse(left.contains(sentOld));
        assertFalse(left.contains(skippedOld));
    }

    // ==================== call_jobs and provider_events ====================

    @Test
    void callJobs_finishedMoreThan30DaysAgo_areDeleted_openJobsAndSessionsStay() {
        long callSession = callSession();
        OffsetDateTime old = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(31)), ZoneOffset.UTC);
        OffsetDateTime recent = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(29)), ZoneOffset.UTC);
        long succeededOld = callJob(callSession, "DELETE_ROOM", "SUCCEEDED", old);
        long failedOld = callJob(callSession, "DELETE_ROOM", "FAILED", old);
        long cancelledOld = callJob(callSession, "DELETE_ROOM", "CANCELLED", old);
        long succeededRecent = callJob(callSession, "DELETE_ROOM", "SUCCEEDED", recent);
        long pending = callJob(callSession, "CREATE_ROOM", "PENDING", null);
        long running = callJob(callSession, "DELETE_ROOM", "RUNNING", null);

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(3, report.get(RetentionSweeper.CALL_JOBS));
        List<Long> left = jdbc.queryForList("SELECT call_job_id FROM call_jobs ORDER BY call_job_id", Long.class);
        assertEquals(List.of(succeededRecent, pending, running), left);
        assertFalse(left.contains(succeededOld));
        assertFalse(left.contains(failedOld));
        assertFalse(left.contains(cancelledOld));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM call_sessions", Integer.class));
    }

    @Test
    void providerEvents_receivedMoreThan30DaysAgo_areDeleted() {
        int old = providerEvent("evt-1", OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(31)), ZoneOffset.UTC));
        int recent = providerEvent("evt-2", OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(29)), ZoneOffset.UTC));

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(1, report.get(RetentionSweeper.PROVIDER_EVENTS));
        assertEquals(List.of(recent), providerEventIds());
        assertFalse(providerEventIds().contains(old));
    }

    @Test
    void outboundAndCallWindows_comeFromTheirProperties() {
        RetentionProperties properties = new RetentionProperties();
        properties.setOutboundMessagesDays(5);
        properties.setCallRecordsDays(5);
        properties.setReadNotificationsDays(10);
        OffsetDateTime sixDaysAgo = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(6)), ZoneOffset.UTC);
        OffsetDateTime fourDaysAgo = OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(4)), ZoneOffset.UTC);
        outbound("SENT", sixDaysAgo, sixDaysAgo);
        int keptMessage = outbound("SENT", fourDaysAgo, fourDaysAgo);
        providerEvent("evt-a", sixDaysAgo);
        int keptEvent = providerEvent("evt-b", fourDaysAgo);
        notification(true, now.minusDays(11), null);
        int keptNotification = notification(true, now.minusDays(9), null);

        sweeperWith(properties).sweepOnce();

        assertEquals(List.of(keptMessage), jdbc.queryForList("SELECT message_id FROM outbound_messages", Integer.class));
        assertEquals(List.of(keptEvent), providerEventIds());
        assertEquals(List.of(keptNotification),
                jdbc.queryForList("SELECT notification_id FROM notifications", Integer.class));
    }

    // ==================== what is never touched, batching, locking ====================

    @Test
    void auditLogAndClinicalRows_areNeverTouched() {
        jdbc.update("INSERT INTO audit_log (system_id, action, resource_type, status, timestamp) "
                + "VALUES (?, 'READ', 'APPOINTMENT', 'SUCCESS', ?)", patient.getSystemId(), now.minusYears(10));
        jdbc.update("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, duration_minutes, "
                        + "type, status) VALUES (?, ?, ?, 30, 'IN_PERSON', 'COMPLETED')",
                patient.getSystemId(), doctor.getSystemId(), now.minusYears(5));
        int auditRows = count("audit_log");
        int appointments = count("appointments");
        int users = count("users");

        sweeper.sweepOnce();

        assertEquals(auditRows, count("audit_log"));
        assertEquals(appointments, count("appointments"));
        assertEquals(users, count("users"));
    }

    @Test
    void largeBacklogs_areDrainedInBatchesWithinOneRun_andARerunDeletesNothing() {
        jdbc.update("INSERT INTO provider_events (provider, event_id, event_type, received_at) "
                + "SELECT 'LIVEKIT', 'bulk-' || g, 'room_finished', ? FROM generate_series(1, 450) g",
                OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(40)), ZoneOffset.UTC));
        jdbc.update("INSERT INTO notifications (system_id, type, title, message, is_read, created_at) "
                + "SELECT ?, 'SYSTEM', 'Old', 'Old notice', TRUE, ? FROM generate_series(1, 230) g",
                patient.getSystemId(), now.minusDays(200));

        Map<String, Integer> report = sweeper.sweepWithReport();

        assertEquals(450, report.get(RetentionSweeper.PROVIDER_EVENTS));
        assertEquals(230, report.get(RetentionSweeper.NOTIFICATIONS));
        assertEquals(0, count("provider_events"));
        assertEquals(0, count("notifications"));
        assertEquals(680, report.values().stream().mapToInt(Integer::intValue).sum());

        assertEquals(0, sweeper.sweepOnce());
    }

    @Test
    void anotherInstanceHoldingTheJobLock_skipsTheRun() {
        int old = providerEvent("evt-locked", OffsetDateTime.ofInstant(FIXED.minus(Duration.ofDays(40)), ZoneOffset.UTC));

        Integer deleted = new TransactionTemplate(transactionManager).execute(tx -> {
            jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, RetentionSweeper.JOB_NAME);
            return sweeper.sweepOnce();
        });

        assertEquals(0, deleted);
        assertEquals(List.of(old), providerEventIds());
        // Lock released with that transaction: the next run deletes it
        assertEquals(1, sweeper.sweepOnce());
    }

    // ==================== FIXTURES ====================

    private int token(String systemId, UUID sessionId, LocalDateTime createdAt, LocalDateTime expiresAt,
                      LocalDateTime revokedAt) {
        return jdbc.queryForObject("INSERT INTO refresh_tokens (system_id, token_hash, session_id, expires_at, "
                        + "is_revoked, revoked_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING token_id",
                Integer.class, systemId, "hash-" + UUID.randomUUID(), sessionId, expiresAt, revokedAt != null,
                revokedAt, createdAt);
    }

    private List<Integer> tokenIds() {
        return jdbc.queryForList("SELECT token_id FROM refresh_tokens ORDER BY token_id", Integer.class);
    }

    private int notification(boolean read, LocalDateTime createdAt, LocalDateTime expiresAt) {
        return jdbc.queryForObject("INSERT INTO notifications (system_id, type, title, message, is_read, read_at, "
                        + "created_at, expires_at) VALUES (?, 'SYSTEM', 'Notice', 'A notice', ?, ?, ?, ?) "
                        + "RETURNING notification_id",
                Integer.class, patient.getSystemId(), read, read ? createdAt.plusHours(1) : null, createdAt, expiresAt);
    }

    private int outbound(String status, OffsetDateTime sentAt, OffsetDateTime createdAt) {
        return jdbc.queryForObject("INSERT INTO outbound_messages (channel, recipient_system_id, recipient_address, "
                        + "body, status, sent_at, created_at, next_attempt_at) "
                        + "VALUES ('EMAIL', ?, 'thomas@example.com', 'Body', ?, ?, ?, ?) RETURNING message_id",
                Integer.class, patient.getSystemId(), status, sentAt, createdAt, createdAt);
    }

    private long callSession() {
        Integer appointmentId = jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, "
                        + "scheduled_at, duration_minutes, type, status) VALUES (?, ?, ?, 30, 'VIDEO', 'COMPLETED') "
                        + "RETURNING appointment_id",
                Integer.class, patient.getSystemId(), doctor.getSystemId(), now.minusDays(40));
        return jdbc.queryForObject("INSERT INTO call_sessions (appointment_id, status, room_name) "
                + "VALUES (?, 'ENDED', ?) RETURNING call_session_id", Long.class, appointmentId, "room-" + UUID.randomUUID()
                .toString().substring(0, 20));
    }

    private long callJob(long callSessionId, String jobType, String status, OffsetDateTime completedAt) {
        String room = jdbc.queryForObject("SELECT room_name FROM call_sessions WHERE call_session_id = ?", String.class,
                callSessionId);
        return jdbc.queryForObject("INSERT INTO call_jobs (job_type, status, call_session_id, room_name, completed_at) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING call_job_id", Long.class, jobType, status, callSessionId, room,
                completedAt);
    }

    private int providerEvent(String eventId, OffsetDateTime receivedAt) {
        return jdbc.queryForObject("INSERT INTO provider_events (provider, event_id, event_type, received_at) "
                        + "VALUES ('LIVEKIT', ?, 'room_finished', ?) RETURNING provider_event_id::int",
                Integer.class, eventId, receivedAt);
    }

    private List<Integer> providerEventIds() {
        return jdbc.queryForList("SELECT provider_event_id::int FROM provider_events ORDER BY provider_event_id",
                Integer.class);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
