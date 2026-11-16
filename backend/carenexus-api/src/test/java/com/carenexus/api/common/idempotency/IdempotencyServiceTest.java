package com.carenexus.api.common.idempotency;

import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IdempotencyService against PostgreSQL, committed like production: replay of a completed key, 409 while the first
 * request is still running, release on failure, isolation per caller and scope, expiry and purge.
 */
public class IdempotencyServiceTest extends CommittedIntegrationTest {

    private static final String SCOPE = "POST /api/test-resources";

    /** A response DTO as controllers return them. */
    public record Created(Integer id, String label, LocalDateTime createdAt) {
    }

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void signIn() {
        runs.set(0);
        authenticateAs(doctor);
    }

    private Created create() {
        int n = runs.incrementAndGet();
        return new Created(n, "created-" + n, LocalDateTime.of(2026, 11, 10, 9, 0).plusMinutes(n));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys", Integer.class);
    }

    // ==================== BASICS ====================

    @Test
    void blankOrMissingKey_theActionJustRuns() {
        assertFalse(idempotencyService.execute(SCOPE, null, Created.class, this::create).replayed());
        assertFalse(idempotencyService.execute(SCOPE, "", Created.class, this::create).replayed());
        assertFalse(idempotencyService.execute(SCOPE, "   ", Created.class, this::create).replayed());
        assertEquals(3, runs.get());
        assertEquals(0, rows());
    }

    @Test
    void sameKeyTwice_runsOnce_andReplaysTheStoredBody() {
        String key = clientId();

        IdempotentResult<Created> first = idempotencyService.execute(SCOPE, key, Created.class, this::create);
        IdempotentResult<Created> second = idempotencyService.execute(SCOPE, key, Created.class, this::create);
        IdempotentResult<Created> third = idempotencyService.execute(SCOPE, key, Created.class, this::create);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertTrue(third.replayed());
        assertEquals(1, runs.get(), "the action ran once");
        assertEquals(first.body(), second.body(), "the replay returns the stored body, including dates");
        assertEquals(first.body(), third.body());
        assertEquals(1, rows());

        // stored for 24 hours, owned by the caller
        assertEquals(doctor.getSystemId(), jdbc.queryForObject("SELECT owner_system_id FROM idempotency_keys", String.class));
        LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM idempotency_keys", Timestamp.class).toLocalDateTime();
        LocalDateTime expires = jdbc.queryForObject("SELECT expires_at FROM idempotency_keys", Timestamp.class).toLocalDateTime();
        assertEquals(created.plusHours(24), expires);
    }

    @Test
    void keysAreScopedToTheCallerAndTheEndpoint() {
        String key = clientId();
        idempotencyService.execute(SCOPE, key, Created.class, this::create);

        assertFalse(idempotencyService.execute("POST /api/other-resources", key, Created.class, this::create).replayed(),
                "same key on another endpoint is a different request");

        authenticateAs(otherDoctor);
        assertFalse(idempotencyService.execute(SCOPE, key, Created.class, this::create).replayed(),
                "same key from another caller is a different request");

        assertEquals(3, runs.get());
        assertEquals(3, rows());
    }

    @Test
    void malformedKey_is400_andNothingRuns() {
        for (String key : new String[]{"short", "has space in it", "bad/chars/here", "x".repeat(65)}) {
            BadRequestException ex = assertThrows(BadRequestException.class,
                    () -> idempotencyService.execute(SCOPE, key, Created.class, this::create), key);
            assertTrue(ex.getMessage().contains("Idempotency-Key"));
        }
        assertEquals(0, runs.get());
        assertEquals(0, rows());
        // boundary lengths are accepted
        assertFalse(idempotencyService.execute(SCOPE, "abcd_-12", Created.class, this::create).replayed());
        assertFalse(idempotencyService.execute(SCOPE, "x".repeat(64), Created.class, this::create).replayed());
    }

    @Test
    void nullBody_isStoredAndReplayed() {
        String key = clientId();
        assertNull(idempotencyService.execute(SCOPE, key, Created.class, () -> { runs.incrementAndGet(); return null; }).body());
        IdempotentResult<Created> replay = idempotencyService.execute(SCOPE, key, Created.class, this::create);
        assertTrue(replay.replayed());
        assertNull(replay.body());
        assertEquals(1, runs.get());
    }

    // ==================== FAILURE AND CONCURRENCY ====================

    @Test
    void failedAction_releasesTheKey_soTheSameKeyCanBeRetried() {
        String key = clientId();

        ConflictException failure = assertThrows(ConflictException.class,
                () -> idempotencyService.execute(SCOPE, key, Created.class, () -> {
                    runs.incrementAndGet();
                    throw new ConflictException("This time slot is already booked");
                }));
        assertEquals("This time slot is already booked", failure.getMessage(), "the action's own error reaches the caller");
        assertEquals(0, rows(), "nothing is stored for a failed request");

        IdempotentResult<Created> retry = idempotencyService.execute(SCOPE, key, Created.class, this::create);
        assertFalse(retry.replayed());
        assertEquals(2, runs.get());
        assertTrue(idempotencyService.execute(SCOPE, key, Created.class, this::create).replayed());
    }

    @Test
    void repeatWhileTheFirstRequestIsStillRunning_is409InProgress_thenReplaysOnceItCompleted() throws Exception {
        String key = clientId();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch letActionFinish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IdempotentResult<Created>> first = pool.submit(() -> {
                authenticateAs(doctor);
                try {
                    return idempotencyService.execute(SCOPE, key, Created.class, () -> {
                        actionStarted.countDown();
                        try {
                            assertTrue(letActionFinish.await(30, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return create();
                    });
                } finally {
                    clearAuthentication();
                }
            });
            assertTrue(actionStarted.await(30, TimeUnit.SECONDS), "the first request never started");

            ConflictException conflict = assertThrows(ConflictException.class,
                    () -> idempotencyService.execute(SCOPE, key, Created.class, this::create));
            assertEquals("IDEMPOTENCY_IN_PROGRESS", conflict.getCode());

            letActionFinish.countDown();
            IdempotentResult<Created> firstResult = first.get(30, TimeUnit.SECONDS);
            assertFalse(firstResult.replayed());

            IdempotentResult<Created> replay = idempotencyService.execute(SCOPE, key, Created.class, this::create);
            assertTrue(replay.replayed());
            assertEquals(firstResult.body(), replay.body());
            assertEquals(1, runs.get(), "the action ran exactly once");
        } finally {
            letActionFinish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void storedResponseCommitsWithTheCallersTransaction_andIsReleasedWhenItRollsBack() {
        String key = clientId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            assertFalse(idempotencyService.execute(SCOPE, key, Created.class, this::create).replayed());
            status.setRollbackOnly();   // the caller's change is rolled back after the idempotent call returned
        });
        assertEquals(0, rows(), "a rolled-back request must not leave a key behind");

        assertFalse(idempotencyService.execute(SCOPE, key, Created.class, this::create).replayed(),
                "the key is free again");
        assertEquals(2, runs.get());
    }

    // ==================== EXPIRY ====================

    @Test
    void expiredKey_isFreeAgain_andPurgeRemovesOnlyExpiredRows() {
        String expiredKey = clientId();
        String liveKey = clientId();
        idempotencyService.execute(SCOPE, expiredKey, Created.class, this::create);
        idempotencyService.execute(SCOPE, liveKey, Created.class, this::create);
        jdbc.update("UPDATE idempotency_keys SET created_at = ?, expires_at = ? WHERE idem_key = ?",
                Timestamp.valueOf(LocalDateTime.now().minusHours(25)), Timestamp.valueOf(LocalDateTime.now().minusHours(1)),
                expiredKey);

        IdempotentResult<Created> again = idempotencyService.execute(SCOPE, expiredKey, Created.class, this::create);
        assertFalse(again.replayed(), "after 24 hours the same key starts a new request");
        assertEquals(3, runs.get());
        assertEquals(2, rows(), "the expired row was re-used, not duplicated");
        assertTrue(idempotencyService.execute(SCOPE, expiredKey, Created.class, this::create).replayed());

        jdbc.update("UPDATE idempotency_keys SET expires_at = ? WHERE idem_key = ?",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), expiredKey);
        assertEquals(1, idempotencyService.purgeExpired());
        assertEquals(1, rows());
        assertEquals(liveKey, jdbc.queryForObject("SELECT idem_key FROM idempotency_keys", String.class));
    }

    @Test
    void abandonedInProgressClaim_isTakenOverAfterTheTimeout() {
        String key = clientId();
        // A request that claimed the key and died (process crash) 10 minutes ago: response_json stays NULL.
        jdbc.update("INSERT INTO idempotency_keys (owner_system_id, scope, idem_key, response_json, created_at, expires_at) "
                        + "VALUES (?, ?, ?, NULL, ?, ?)", doctor.getSystemId(), SCOPE, key,
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)), Timestamp.valueOf(LocalDateTime.now().plusHours(23)));

        IdempotentResult<Created> result = idempotencyService.execute(SCOPE, key, Created.class, this::create);
        assertFalse(result.replayed());
        assertEquals(1, runs.get());
        assertEquals(1, rows());

        // A claim that is only seconds old is still "in progress".
        String fresh = clientId();
        jdbc.update("INSERT INTO idempotency_keys (owner_system_id, scope, idem_key, response_json, created_at, expires_at) "
                        + "VALUES (?, ?, ?, NULL, ?, ?)", doctor.getSystemId(), SCOPE, fresh,
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(5)), Timestamp.valueOf(LocalDateTime.now().plusHours(24)));
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> idempotencyService.execute(SCOPE, fresh, Created.class, this::create));
        assertEquals("IDEMPOTENCY_IN_PROGRESS", conflict.getCode());
        assertEquals(1, runs.get());
    }

    @Test
    void keysDisappearWithTheirOwner() {
        idempotencyService.execute(SCOPE, clientId(), Created.class, this::create);
        assertEquals(1, rows());
        jdbc.update("DELETE FROM doctors WHERE system_id = ?", doctor.getSystemId());
        jdbc.update("DELETE FROM care_team WHERE provider_system_id = ?", doctor.getSystemId());
        jdbc.update("DELETE FROM users WHERE system_id = ?", doctor.getSystemId());
        assertEquals(0, rows());
    }
}
