package com.carenexus.api.communications.job;

import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.service.ThreadService;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.DependentAccessRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nothing is published when a caregiver grant simply runs past expires_at. Request-time checks already deny an
 * expired grant, and this sweeper makes membership and the consent ledger match: every sweep interval each newly
 * lapsed grant
 *  - ends the caregiver's conversation memberships about that patient (ACCESS_EXPIRED) and marks their unread
 *    notifications for those conversations read, so a later extension starts a NEW history boundary;
 *  - publishes CaregiverAccessChangedEvent(revoked = true) so calls drop the caregiver's open participation;
 *  - publishes CaregiverGrantRecordedEvent(EXPIRED, actor null) once per lapse for the consent ledger;
 *  - stamps expiry_processed_at (a grant whose expiry is moved later is processed again when it lapses again).
 * Each grant is processed in its own transaction, locked and re-checked first, so two instances never process the
 * same lapse twice. Off when {@code carenexus.jobs.enabled=false} (tests call {@link #sweepOnce()} themselves).
 */
@Slf4j
@Component
public class CaregiverAccessExpirySweeper {

    static final int BATCH = 200;
    static final String JOB_NAME = "caregiver-access-expiry";

    private final DependentAccessRepository dependentAccessRepository;
    private final ThreadService threadService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNew;
    private final JobSupport jobSupport;
    private final Clock clock;

    public CaregiverAccessExpirySweeper(DependentAccessRepository dependentAccessRepository, ThreadService threadService,
                                        ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager,
                                        JobSupport jobSupport, Clock clock) {
        this.dependentAccessRepository = dependentAccessRepository;
        this.threadService = threadService;
        this.eventPublisher = eventPublisher;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jobSupport = jobSupport;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${carenexus.messaging.access-expiry-sweep-interval-ms:60000}",
            initialDelayString = "${carenexus.messaging.access-expiry-sweep-interval-ms:60000}")
    public void sweep() {
        if (!jobSupport.enabled()) {
            return;
        }
        try {
            int processed = sweepOnce();
            if (processed > 0) {
                log.info("Processed {} expired caregiver grant(s)", processed);
            }
        } catch (RuntimeException e) {
            log.error("Caregiver access expiry sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One sweep at the application clock's current clinic time. @return number of grants processed */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock));
    }

    /**
     * One sweep at {@code now} (clinic wall-clock), run as job {@value #JOB_NAME}: only one API instance sweeps at a
     * time (advisory lock) and every run is counted in the job metrics.
     *
     * @return number of grants processed (0 when another instance holds the job lock)
     */
    public int sweepOnce(LocalDateTime now) {
        AtomicInteger processed = new AtomicInteger();
        jobSupport.runExclusive(JOB_NAME, () -> processed.set(processDue(now)));
        return processed.get();
    }

    /** Each due grant in its own transaction; a failing grant is logged and retried on the next sweep. */
    private int processDue(LocalDateTime now) {
        List<Integer> due = dependentAccessRepository.findExpiredUnprocessedIds(now, PageRequest.of(0, BATCH));
        int processed = 0;
        for (Integer accessId : due) {
            try {
                Boolean done = requiresNew.execute(status -> processOne(accessId, now));
                if (Boolean.TRUE.equals(done)) {
                    processed++;
                }
            } catch (RuntimeException e) {
                log.warn("Expiring caregiver grant {} failed: {}", accessId, e.getClass().getSimpleName());
            }
        }
        return processed;
    }

    private boolean processOne(Integer accessId, LocalDateTime now) {
        DependentAccess access = dependentAccessRepository.findForUpdateById(accessId).orElse(null);
        if (access == null || !isDue(access, now)) {
            return false; // extended, revoked or processed by another instance meanwhile
        }
        int removed = threadService.removeCaregiverAfterAccessChange(access.getCaregiverSystemId(),
                access.getPatientSystemId(), ThreadMember.REASON_ACCESS_EXPIRED);
        access.setExpiryProcessedAt(now);
        dependentAccessRepository.save(access);
        eventPublisher.publishEvent(new CaregiverAccessChangedEvent(access.getAccessId(), access.getPatientSystemId(),
                access.getCaregiverSystemId(), true, false, false));
        eventPublisher.publishEvent(new CaregiverGrantRecordedEvent(access.getAccessId(), access.getPatientSystemId(),
                access.getCaregiverSystemId(), null, CaregiverGrantRecordedEvent.EXPIRED,
                access.getGuardianAttestedAt() != null));
        if (removed > 0) {
            log.info("Caregiver grant {} expired: ended {} conversation membership(s)", accessId, removed);
        }
        return true;
    }

    static boolean isDue(DependentAccess access, LocalDateTime now) {
        return Boolean.TRUE.equals(access.getIsActive())
                && access.getRevokedAt() == null
                && access.getExpiresAt() != null
                && !access.getExpiresAt().isAfter(now)
                && (access.getExpiryProcessedAt() == null || access.getExpiryProcessedAt().isBefore(access.getExpiresAt()));
    }
}
