package com.carenexus.api.core.job;

import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.repository.PrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Expires prescriptions (job {@value #JOB_NAME}, every {@code carenexus.jobs.prescription-expiry.interval-ms},
 * default one hour).
 *
 * A prescription is valid through its {@code expiry_date}; on the following clinic day an ACTIVE or ON_HOLD row
 * becomes EXPIRED and {@link PrescriptionStatusChangedEvent} is published with the previous stored status and the
 * prescriber as {@code doctorSystemId}. Each batch (at most {@value #BATCH_SIZE} rows, locked with
 * {@code FOR UPDATE SKIP LOCKED}) runs in its own transaction inside {@link JobSupport#runExclusive}, so a second
 * API instance skips the run and a re-run finds nothing left to do. No audit row is written (no acting user).
 *
 * Scheduled runs are off when {@code carenexus.jobs.enabled=false}; tests call {@link #sweepOnce()} on an instance
 * built with a fixed {@link Clock}.
 */
@Slf4j
@Component
public class PrescriptionExpirySweeper {

    public static final String JOB_NAME = "prescription-expiry";
    public static final int BATCH_SIZE = 200;
    /** Upper bound of batches per run, so one run cannot loop forever; the next run continues. */
    static final int MAX_BATCHES_PER_RUN = 50;

    private final JobSupport jobs;
    private final PrescriptionRepository prescriptions;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PrescriptionExpirySweeper(JobSupport jobs, PrescriptionRepository prescriptions,
                                     ApplicationEventPublisher events, Clock clock) {
        this.jobs = jobs;
        this.prescriptions = prescriptions;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.prescription-expiry.interval-ms:3600000}",
            initialDelayString = "${carenexus.jobs.prescription-expiry.interval-ms:3600000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            int expired = sweepOnce();
            if (expired > 0) {
                log.info("Prescription expiry job expired {} prescription(s)", expired);
            }
        } catch (RuntimeException e) {
            // JobSupport already counted and logged the failed run; the next tick tries again.
            log.debug("Prescription expiry run ended early: {}", e.getClass().getName());
        }
    }

    /**
     * One run: expires every ACTIVE or ON_HOLD prescription whose expiry date is before clinic today, in batches.
     *
     * @return how many prescriptions were expired; 0 when nothing was due or another instance holds the job
     */
    public int sweepOnce() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            AtomicInteger done = new AtomicInteger();
            boolean ran = jobs.runExclusive(JOB_NAME, () -> done.set(expireBatch(today)));
            total += done.get();
            if (!ran || done.get() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /** Runs inside the job's transaction. */
    private int expireBatch(LocalDate today) {
        List<Prescription> due = prescriptions.lockExpiredBatch(today, BATCH_SIZE);
        for (Prescription prescription : due) {
            String previous = prescription.getStatus();
            prescription.setStatus(Prescription.EXPIRED);
            prescriptions.save(prescription);
            events.publishEvent(new PrescriptionStatusChangedEvent(prescription.getPrescriptionId(),
                    prescription.getPatientSystemId(), prescription.getDoctorSystemId(), previous, Prescription.EXPIRED));
        }
        prescriptions.flush();
        return due.size();
    }
}
