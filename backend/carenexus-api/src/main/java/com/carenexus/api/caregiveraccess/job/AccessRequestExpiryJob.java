package com.carenexus.api.caregiveraccess.job;

import com.carenexus.api.caregiveraccess.model.CaregiverAccessRequest;
import com.carenexus.api.caregiveraccess.repository.CaregiverAccessRequestRepository;
import com.carenexus.api.common.job.JobSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@value #JOB_NAME} (daily by default, {@code carenexus.jobs.access-requests.interval-ms}): PENDING access
 * requests created {@value CaregiverAccessRequest#PENDING_DAYS} days ago or earlier become EXPIRED, with
 * responded_at = now. Nobody is notified. Batches of {@value #BATCH} rows, each claimed with
 * {@code FOR UPDATE SKIP LOCKED}; re-running finds nothing more to do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessRequestExpiryJob {

    static final String JOB_NAME = "access-requests";
    static final int BATCH = 200;

    private final CaregiverAccessRequestRepository requestRepository;
    private final JobSupport jobSupport;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.access-requests.interval-ms:86400000}")
    public void tick() {
        if (!jobSupport.enabled()) {
            return;
        }
        try {
            int expired = sweepOnce();
            if (expired > 0) {
                log.info("Expired {} pending caregiver access request(s)", expired);
            }
        } catch (RuntimeException e) {
            log.error("Access request expiry failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One run at the application clock's clinic time. @return number of requests expired */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock));
    }

    /**
     * One run at {@code now} (clinic wall-clock), exclusive across API instances.
     *
     * @return number of requests expired (0 when another instance holds the job lock)
     */
    public int sweepOnce(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(CaregiverAccessRequest.PENDING_DAYS);
        AtomicInteger expired = new AtomicInteger();
        jobSupport.runExclusive(JOB_NAME, () -> {
            int batch;
            do {
                batch = requestRepository.expirePendingBatch(cutoff, now, BATCH);
                expired.addAndGet(batch);
            } while (batch == BATCH);
        });
        return expired.get();
    }
}
