package com.carenexus.api.common.job;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JobSupport against PostgreSQL: the per-job advisory lock, the transaction around the body, the run counters and
 * the test-profile switch.
 */
public class JobSupportTest extends CommittedIntegrationTest {

    @Autowired
    private JobSupport jobs;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Clock clock;

    private double runs(String job, String outcome) {
        var counter = meterRegistry.find(JobSupport.RUNS_METRIC).tags("job", job, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void scheduledJobsAreOffInTheTestProfile() {
        assertFalse(jobs.enabled(), "carenexus.jobs.enabled must be false in application-test.yml");
    }

    @Test
    void applicationClock_isInTheClinicZone() {
        assertEquals(ZoneId.of("Africa/Harare"), clock.getZone());
    }

    @Test
    void runExclusive_runsTheBodyInATransaction_andCountsTheRun() {
        String job = "test-job-" + clientId();
        AtomicInteger ran = new AtomicInteger();

        boolean executed = jobs.runExclusive(job, () -> {
            ran.incrementAndGet();
            jdbc.update("UPDATE users SET first_name = 'Swept' WHERE system_id = ?", patient.getSystemId());
        });

        assertTrue(executed);
        assertEquals(1, ran.get());
        assertEquals("Swept", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                patient.getSystemId()), "the body's writes are committed");
        assertEquals(1.0, runs(job, "success"));
        assertEquals(0.0, runs(job, "skipped"));
        assertEquals(1, meterRegistry.find(JobSupport.DURATION_METRIC).tags("job", job).timer().count());
    }

    @Test
    void runExclusive_skipsWhileAnotherInstanceHoldsTheJobLock() throws Exception {
        String job = "test-job-" + clientId();
        AtomicInteger ran = new AtomicInteger();

        // "Another instance": a second connection holding the same transaction-scoped advisory lock.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
                lock.setString(1, job);
                lock.execute();
            }

            assertFalse(jobs.runExclusive(job, ran::incrementAndGet));
            assertEquals(0, ran.get(), "the body must not run while the lock is held elsewhere");
            assertEquals(1.0, runs(job, "skipped"));

            // A different job is not blocked by it.
            assertTrue(jobs.runExclusive(job + "-other", ran::incrementAndGet));
            assertEquals(1, ran.get());

            other.rollback();   // the other instance finished: its lock is gone
        }

        assertTrue(jobs.runExclusive(job, ran::incrementAndGet));
        assertEquals(2, ran.get());
        assertEquals(1.0, runs(job, "success"));
    }

    @Test
    void failingBody_rollsBack_isCountedAsFailure_andReleasesTheLock() {
        String job = "test-job-" + clientId();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> jobs.runExclusive(job, () -> {
            jdbc.update("UPDATE users SET first_name = 'Half' WHERE system_id = ?", patient.getSystemId());
            throw new IllegalStateException("batch failed");
        }));
        assertEquals("batch failed", failure.getMessage());
        assertEquals("PATIENT", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                patient.getSystemId()), "the failed run's writes are rolled back");
        assertEquals(1.0, runs(job, "failure"));

        assertTrue(jobs.runExclusive(job, () -> { }), "the lock was released with the failed transaction");
    }

    @Test
    void blankJobName_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> jobs.runExclusive(" ", () -> { }));
    }
}
