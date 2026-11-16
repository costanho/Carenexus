package com.carenexus.api.medication;

import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.medication.job.MedicationScheduleSweeper;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.PrescriptionSnapshot;
import com.carenexus.api.medication.service.DoseMaterializer;
import com.carenexus.api.medication.service.MedicationPlanService;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.carenexus.api.medication.MedicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The writers of a prescription's future doses never interleave: the sweeper's batch, a status change (with the
 * listener that removes future doses) and a schedule PUT serialise on the prescription row. Each test runs two
 * real transactions on two threads; without the row locks every one of them would leave doses behind that belong
 * to a stopped prescription or to replaced times.
 */
class MedicationScheduleConcurrencyTest extends CommittedIntegrationTest {

    private static final long WAIT_SECONDS = 15;
    /** How long a writer is given to (wrongly) get past a lock before the test calls it blocked. */
    private static final long BLOCKED_MILLIS = 1500;

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private MedicationScheduleJdbc scheduleJdbc;

    @Autowired
    private DoseMaterializer materializer;

    @Autowired
    private MedicationPlanService planService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private ExecutorService executor;

    @BeforeEach
    void startExecutor() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "worker threads stopped");
    }

    /** Pauses the sweeper inside its first batch transaction, after the first prescription's doses are written. */
    private static final class PausingMaterializer extends DoseMaterializer {
        private final CountDownLatch inBatch = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();

        PausingMaterializer(MedicationScheduleJdbc jdbc) {
            super(jdbc);
        }

        @Override
        public int materialise(PrescriptionSnapshot rx, List<LocalTime> times, LocalDateTime now) {
            int created = super.materialise(rx, times, now);
            if (paused.compareAndSet(false, true)) {
                inBatch.countDown();
                try {
                    if (!resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the test never resumed the sweeper");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return created;
        }
    }

    private MedicationScheduleSweeper sweeper(DoseMaterializer doses) {
        return new MedicationScheduleSweeper(jobSupport, scheduleJdbc, doses, event -> { }, clock, 120);
    }

    private int activeWithPlan(String timesJson) {
        int rx = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin", "Twice daily",
                "ACTIVE", LocalDate.now(clock).minusDays(9), null);
        storePlan(jdbc, rx, timesJson, doctor.getSystemId());
        return rx;
    }

    /** What be-prescriptions does on a cancel: the status UPDATE and the event, in one transaction. */
    private void cancel(int rx) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE prescriptions SET status = 'CANCELLED' WHERE prescription_id = ?", rx);
            publisher.publishEvent(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(),
                    doctor.getSystemId(), "ACTIVE", "CANCELLED"));
        });
    }

    private static void assertBlocked(Future<?> writer, String what) {
        assertThrows(TimeoutException.class, () -> writer.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS), what);
    }

    private int pendingFutureDoses(int rx) {
        LocalDateTime now = LocalDateTime.now(clock);
        return jdbc.queryForObject("SELECT COUNT(*) FROM medication_schedules WHERE prescription_id = ? "
                        + "AND NOT is_taken AND NOT missed AND (schedule_date > ? "
                        + "OR (schedule_date = ? AND scheduled_time > ?))", Integer.class, rx,
                java.sql.Date.valueOf(now.toLocalDate()), java.sql.Date.valueOf(now.toLocalDate()),
                java.sql.Time.valueOf(now.toLocalTime().withNano(0)));
    }

    @Test
    void cancelDuringASweeperBatchWaitsAndThenRemovesTheBatchsDoses() throws Exception {
        int rx = activeWithPlan("[\"08:00\", \"20:00\"]");
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        PausingMaterializer pausing = new PausingMaterializer(scheduleJdbc);

        Future<Integer> sweep = executor.submit(() -> sweeper(pausing).sweepOnce());
        assertTrue(pausing.inBatch.await(WAIT_SECONDS, TimeUnit.SECONDS), "the sweeper reached its batch");
        Future<?> cancel = executor.submit(() -> cancel(rx));
        assertBlocked(cancel, "the status change waits for the sweeper batch holding the prescription");

        pausing.resume.countDown();
        assertTrue(sweep.get(WAIT_SECONDS, TimeUnit.SECONDS) >= 2, "the batch created tomorrow's doses");
        cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertTrue(doseTimes(jdbc, rx, tomorrow).isEmpty(), "the cancel saw and removed the committed batch");
        assertEquals(0, pendingFutureDoses(rx));
    }

    @Test
    void sweeperSkipsAPrescriptionWhoseStatusChangeIsInFlight() throws Exception {
        int rx = activeWithPlan("[\"08:00\", \"20:00\"]");
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<?> cancel = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbc.update("UPDATE prescriptions SET status = 'CANCELLED' WHERE prescription_id = ?", rx);
            publisher.publishEvent(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(),
                    doctor.getSystemId(), "ACTIVE", "CANCELLED"));
            updated.countDown();
            try {
                assertTrue(commit.await(WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        assertTrue(updated.await(WAIT_SECONDS, TimeUnit.SECONDS), "the cancel is in flight");

        // The committed status is still ACTIVE, but the row is locked by the cancel: the sweeper must leave it alone
        // rather than create doses the cancel's listener can no longer see.
        Future<Integer> sweep = executor.submit(() -> sweeper(materializer).sweepOnce());
        assertEquals(0, sweep.get(WAIT_SECONDS, TimeUnit.SECONDS), "the sweeper skipped the locked prescription");

        commit.countDown();
        cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
        sweeper(materializer).sweepOnce();
        assertEquals(0, countDoses(jdbc, rx), "nothing was ever created for the cancelled prescription");
    }

    @Test
    void schedulePutDuringASweeperBatchWaitsAndLeavesOnlyTheNewTimes() throws Exception {
        int rx = activeWithPlan("[\"08:00\", \"20:00\"]");
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        PausingMaterializer pausing = new PausingMaterializer(scheduleJdbc);

        Future<Integer> sweep = executor.submit(() -> sweeper(pausing).sweepOnce());
        assertTrue(pausing.inBatch.await(WAIT_SECONDS, TimeUnit.SECONDS), "the sweeper reached its batch");
        Future<?> put = executor.submit(() -> {
            authenticateAs(doctor);
            try {
                planService.updatePlan(rx, List.of("09:00"));
            } finally {
                clearAuthentication();
            }
        });
        assertBlocked(put, "the PUT waits for the sweeper batch holding the prescription");

        pausing.resume.countDown();
        sweep.get(WAIT_SECONDS, TimeUnit.SECONDS);
        put.get(WAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("09:00"), doseTimes(jdbc, rx, tomorrow), "no dose of the replaced times survives");
        assertEquals("[\"09:00\"]", planJson(jdbc, rx));
    }

    @Test
    void schedulePutOnAPrescriptionCancelledMeanwhileIsRejected() throws Exception {
        int rx = activeWithPlan("[\"08:00\"]");
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        Future<?> cancel = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbc.update("UPDATE prescriptions SET status = 'CANCELLED' WHERE prescription_id = ?", rx);
            publisher.publishEvent(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(),
                    doctor.getSystemId(), "ACTIVE", "CANCELLED"));
            updated.countDown();
            try {
                assertTrue(commit.await(WAIT_SECONDS, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        assertTrue(updated.await(WAIT_SECONDS, TimeUnit.SECONDS), "the cancel is in flight");

        Future<?> put = executor.submit(() -> {
            authenticateAs(doctor);
            try {
                planService.updatePlan(rx, List.of("09:00"));
            } finally {
                clearAuthentication();
            }
        });
        assertBlocked(put, "the PUT waits for the in-flight status change");

        commit.countDown();
        cancel.get(WAIT_SECONDS, TimeUnit.SECONDS);
        Exception failure = assertThrows(Exception.class, () -> put.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(com.carenexus.api.common.exception.ConflictException.class, failure.getCause(),
                "the PUT decided on the committed CANCELLED status");
        assertEquals(0, countDoses(jdbc, rx));
        assertEquals("[\"08:00\"]", planJson(jdbc, rx));
    }
}
