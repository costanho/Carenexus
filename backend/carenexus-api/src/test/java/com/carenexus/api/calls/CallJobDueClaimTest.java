package com.carenexus.api.calls;

import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.repository.CallJobRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Due-job claim of the call job worker: {@code next_attempt_at} is written with the JVM clock, so the claim compares
 * it with the worker's own "now" (passed in), never with the database's {@code now()}. A database clock that runs
 * ahead or behind the application therefore cannot run a job early or hold it back.
 */
class CallJobDueClaimTest extends CommittedIntegrationTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private CallJobRepository callJobRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private List<Long> claimAt(Instant now) {
        return new TransactionTemplate(transactionManager).execute(tx ->
                callJobRepository.lockDueJobs(now, 10).stream().map(CallJob::getCallJobId).toList());
    }

    @Test
    void aJobIsClaimedExactlyWhenTheWorkersClockReachesItsAttemptTime() throws Exception {
        MvcResult booked = doPost("/api/appointments", doctor, Map.of(
                "patientSystemId", patient.getSystemId(),
                "doctorSystemId", doctor.getSystemId(),
                "scheduledAt", slotInMinutes(30).format(ISO),
                "durationMinutes", 30,
                "type", "VIDEO",
                "reasonForVisit", "Follow-up"));
        assertEquals(201, booked.getResponse().getStatus(), booked.getResponse().getContentAsString());

        // Move the job's attempt time well away from the database clock, as a clock difference would
        Instant due = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("UPDATE call_jobs SET next_attempt_at = ?", Timestamp.from(due));
        Long jobId = jdbc.queryForObject("SELECT call_job_id FROM call_jobs", Long.class);

        assertTrue(claimAt(due.minusSeconds(1)).isEmpty(), "not due yet for the worker");
        assertEquals(List.of(jobId), claimAt(due), "due at exactly its attempt time");
        assertEquals(List.of(jobId), claimAt(due.plusSeconds(60)));

        // The worker's runOnce uses the JVM clock: the job two hours ahead is left alone
        assertEquals(0, callJobService.runOnce());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM call_jobs WHERE call_job_id = ?",
                String.class, jobId));
    }
}
