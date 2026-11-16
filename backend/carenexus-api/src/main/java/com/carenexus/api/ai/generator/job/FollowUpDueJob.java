package com.carenexus.api.ai.generator.job;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc.FollowUpDue;
import com.carenexus.api.ai.generator.service.AlertTexts;
import com.carenexus.api.common.job.JobSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@code follow-up-due} (row 6 of api/be-ai-generators.md 5.1, rule 5.5; daily by default, one instance at a
 * time): a completed consultation whose recommended follow-up date has passed, with no later appointment booked for
 * the same patient and doctor, raises one FOLLOW_UP_DUE alert (priority LOW) for the consultation's doctor. The
 * patient and permitted caregivers also see it, so its text names only the follow-up date.
 * <p>
 * Only follow-up dates from {@code carenexus.ai.follow-up-lookback-days} (default 30) days before the clinic's
 * today up to yesterday are considered, which bounds the first run on old data. Dedupe key
 * {@code FOLLOW_UP_DUE:<consultationId>}: once per consultation for ever; booking the follow-up later does not
 * withdraw the alert.
 * <p>
 * Candidates are read in batches of at most {@value #BATCH_SIZE} (keyset on the consultation id) under the job's
 * advisory lock ({@link JobSupport#runExclusive}); each alert is raised in its own transaction, so one failing row
 * is logged and skipped without losing the others. Re-running is harmless.
 */
@Component
@Slf4j
public class FollowUpDueJob {

    public static final String JOB_NAME = "follow-up-due";
    static final int BATCH_SIZE = 200;
    /** Upper bound of batches per run; the next run continues where this one stopped. */
    static final int MAX_BATCHES = 50;

    private final JobSupport jobs;
    private final AiGeneratorJdbc generatorJdbc;
    private final AiAlertService aiAlertService;
    private final TransactionTemplate perAlert;
    private final Clock clock;
    private final ZoneId clinicZone;
    private final AiGeneratorProperties properties;

    @Autowired
    public FollowUpDueJob(JobSupport jobs, AiGeneratorJdbc generatorJdbc, AiAlertService aiAlertService,
                          PlatformTransactionManager transactionManager, Clock clock,
                          AiGeneratorProperties properties,
                          @Value("${carenexus.scheduling.time-zone:Africa/Harare}") String timeZone) {
        this.jobs = jobs;
        this.generatorJdbc = generatorJdbc;
        this.aiAlertService = aiAlertService;
        this.perAlert = new TransactionTemplate(transactionManager);
        this.perAlert.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.properties = properties;
        this.clinicZone = ZoneId.of(timeZone);
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.follow-up-due.interval-ms:86400000}",
            initialDelayString = "${carenexus.jobs.follow-up-due.initial-delay-ms:300000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Follow-up-due sweep failed: {}", e.getClass().getName());
        }
    }

    /**
     * One full run.
     *
     * @return the number of FOLLOW_UP_DUE alerts raised
     */
    public int sweepOnce() {
        LocalDate clinicToday = LocalDate.ofInstant(clock.instant(), clinicZone);
        LocalDate earliest = clinicToday.minusDays(Math.max(1, properties.getFollowUpLookbackDays()));
        int total = 0;
        int afterId = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            AtomicInteger raised = new AtomicInteger();
            AtomicInteger rows = new AtomicInteger();
            AtomicInteger lastId = new AtomicInteger(afterId);
            int from = afterId;
            boolean ran = jobs.runExclusive(JOB_NAME, () -> {
                List<FollowUpDue> due = generatorJdbc.findFollowUpsDue(clinicToday, earliest, from, BATCH_SIZE);
                rows.set(due.size());
                for (FollowUpDue followUp : due) {
                    lastId.set(followUp.consultationId());
                    if (raise(followUp)) {
                        raised.incrementAndGet();
                    }
                }
            });
            if (!ran) {
                break;
            }
            total += raised.get();
            afterId = lastId.get();
            if (rows.get() < BATCH_SIZE || afterId == from) {
                break;
            }
        }
        if (total > 0) {
            log.info("Follow-up-due sweep: {} alert(s) raised", total);
        }
        return total;
    }

    private boolean raise(FollowUpDue followUp) {
        try {
            Boolean inserted = perAlert.execute(status -> aiAlertService.raise(new AiAlertCommand(
                    followUp.patientSystemId(),
                    followUp.doctorSystemId(),
                    AiAlertType.FOLLOW_UP_DUE.name(),
                    AiAlertPriority.LOW.name(),
                    AlertTexts.FOLLOW_UP_TITLE,
                    AlertTexts.followUpMessage(followUp.followUpDate()),
                    false,
                    AlertTexts.FOLLOW_UP_ACTION,
                    AiAlertSourceType.CONSULTATION.name(),
                    followUp.consultationId(),
                    null,
                    dedupeKey(followUp.consultationId()))).isPresent());
            return Boolean.TRUE.equals(inserted);
        } catch (RuntimeException e) {
            log.error("Follow-up-due alert failed for consultation {}: {}", followUp.consultationId(),
                    e.getClass().getName());
            return false;
        }
    }

    public static String dedupeKey(Integer consultationId) {
        return AiAlertType.FOLLOW_UP_DUE.name() + ":" + consultationId;
    }
}
