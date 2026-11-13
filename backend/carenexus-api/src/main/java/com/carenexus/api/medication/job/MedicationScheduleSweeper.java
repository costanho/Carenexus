package com.carenexus.api.medication.job;

import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.DueDose;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.PlannedPrescription;
import com.carenexus.api.medication.service.DoseMaterializer;
import com.carenexus.api.medication.service.ScheduleTimes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@code medication-schedule} (every 15 minutes by default, one instance at a time):
 * 1. for every ACTIVE prescription with a non-empty plan, creates the missing doses of today (times still ahead)
 *    and tomorrow, so the next day always exists before it starts;
 * 2. marks PENDING doses MISSED once their time is more than {@code carenexus.medication.missed-grace-minutes}
 *    (default 120) in the past, and publishes {@link MedicationDoseMissedEvent} with the consecutive-miss count.
 *    Only doses of the markable window (the clinic's today and yesterday) raise an event; PENDING doses older than
 *    that (legacy rows, a backlog after downtime) are marked MISSED silently in one statement, since nobody can act
 *    on them any more and alerting about them would only send stale notifications.
 *
 * Work is done in batches of at most {@value #BATCH_SIZE} rows, each batch in its own transaction under the job's
 * advisory lock ({@link JobSupport#runExclusive}). The materialise pass pages through every ACTIVE planned
 * prescription in id order until a short page, share-locking each page's prescription and plan rows so a status
 * change or schedule PUT on the same prescription cannot interleave with it. Missed doses are claimed with
 * {@code FOR UPDATE SKIP LOCKED} so a patient marking a dose at the same moment is never overwritten. Every step is
 * idempotent.
 */
@Component
@Slf4j
public class MedicationScheduleSweeper {

    public static final String JOB_NAME = "medication-schedule";
    static final int BATCH_SIZE = 200;
    /**
     * Upper bound of missed-dose batches per run. Each batch removes its rows from the PENDING set, so the next run
     * simply continues with the remaining overdue doses.
     */
    static final int MAX_MISSED_BATCHES = 50;
    /** Doses of today and of this many days before it can still be marked, and raise a missed-dose event. */
    static final int MARKABLE_DAYS_BACK = 1;
    static final int MISS_RUN_LIMIT = 50;

    private final JobSupport jobs;
    private final MedicationScheduleJdbc jdbc;
    private final DoseMaterializer materializer;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final int graceMinutes;

    @Autowired
    public MedicationScheduleSweeper(JobSupport jobs, MedicationScheduleJdbc jdbc, DoseMaterializer materializer,
                                     ApplicationEventPublisher eventPublisher, Clock clock,
                                     @Value("${carenexus.medication.missed-grace-minutes:120}") int graceMinutes) {
        if (graceMinutes < 0) {
            throw new IllegalArgumentException("carenexus.medication.missed-grace-minutes must not be negative");
        }
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.materializer = materializer;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.graceMinutes = graceMinutes;
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.medication-schedule.interval-ms:900000}",
            initialDelayString = "${carenexus.jobs.medication-schedule.initial-delay-ms:60000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Medication schedule sweep failed: {}", e.getClass().getName());
        }
    }

    /**
     * One full run: materialise, then mark overdue doses missed.
     *
     * @return the number of doses created plus the number marked missed
     */
    public int sweepOnce() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int created = materialiseAll(now);
        int missed = markOverdueMissed(now);
        if (created > 0 || missed > 0) {
            log.info("Medication schedule sweep: {} dose(s) created, {} marked missed", created, missed);
        }
        return created + missed;
    }

    private int materialiseAll(LocalDateTime now) {
        int total = 0;
        int afterId = 0;
        // Keyset paging on prescription_id always advances and ends on the first short page, so no batch cap is
        // needed: every planned ACTIVE prescription is visited on every run.
        while (true) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger lastId = new AtomicInteger(afterId);
            AtomicInteger rows = new AtomicInteger();
            int from = afterId;
            boolean ran = jobs.runExclusive(JOB_NAME, () -> {
                List<PlannedPrescription> page = jdbc.findActiveWithPlan(from, now.toLocalDate(), BATCH_SIZE);
                rows.set(page.size());
                for (PlannedPrescription planned : page) {
                    created.addAndGet(materializer.materialise(planned.prescription(),
                            ScheduleTimes.fromJson(planned.timesJson()), now));
                    lastId.set(planned.prescription().prescriptionId());
                }
            });
            if (!ran) {
                break;
            }
            total += created.get();
            afterId = lastId.get();
            if (rows.get() < BATCH_SIZE || afterId == from) {
                break;
            }
        }
        return total;
    }

    private int markOverdueMissed(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(graceMinutes);
        LocalDate windowStart = now.toLocalDate().minusDays(MARKABLE_DAYS_BACK);
        AtomicInteger stale = new AtomicInteger();
        if (!jobs.runExclusive(JOB_NAME, () -> stale.set(jdbc.markStalePendingMissed(windowStart)))) {
            return 0;
        }
        if (stale.get() > 0) {
            log.info("Medication schedule sweep: {} dose(s) older than the markable window marked missed without "
                    + "notification", stale.get());
        }
        int total = stale.get();
        for (int batch = 0; batch < MAX_MISSED_BATCHES; batch++) {
            AtomicInteger marked = new AtomicInteger();
            AtomicInteger rows = new AtomicInteger();
            boolean ran = jobs.runExclusive(JOB_NAME, () -> {
                List<DueDose> due = jdbc.claimPendingBefore(windowStart, cutoff, BATCH_SIZE);
                rows.set(due.size());
                for (DueDose dose : due) {
                    if (jdbc.markMissedBySystem(dose.scheduleId()) == 0) {
                        continue;
                    }
                    marked.incrementAndGet();
                    int run = jdbc.missedRunBefore(dose.prescriptionId(), dose.scheduleDate(), dose.scheduledTime(),
                            MISS_RUN_LIMIT - 1);
                    eventPublisher.publishEvent(new MedicationDoseMissedEvent(dose.scheduleId(),
                            dose.prescriptionId(), dose.patientSystemId(), dose.scheduleDate(), dose.scheduledTime(),
                            run + 1));
                }
            });
            if (!ran) {
                break;
            }
            total += marked.get();
            if (rows.get() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
