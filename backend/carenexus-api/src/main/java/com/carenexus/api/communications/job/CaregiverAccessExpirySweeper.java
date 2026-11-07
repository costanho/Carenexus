package com.carenexus.api.communications.job;

import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
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

import java.time.LocalDateTime;
import java.util.List;

/**
 * Nothing is published when a caregiver grant simply runs past expires_at. Request-time checks already deny an
 * expired grant, and this sweeper makes membership match: every sweep interval each newly lapsed grant
 *  - ends the caregiver's conversation memberships about that patient (ACCESS_EXPIRED) and marks their unread
 *    notifications for those conversations read, so a later extension starts a NEW history boundary;
 *  - publishes CaregiverAccessChangedEvent(revoked = true) so calls drop the caregiver's open participation;
 *  - stamps expiry_processed_at (a grant whose expiry is moved later is processed again when it lapses again).
 * Each grant is processed in its own transaction, locked and re-checked first.
 */
@Slf4j
@Component
public class CaregiverAccessExpirySweeper {

    static final int BATCH = 200;

    private final DependentAccessRepository dependentAccessRepository;
    private final ThreadService threadService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNew;

    public CaregiverAccessExpirySweeper(DependentAccessRepository dependentAccessRepository, ThreadService threadService,
                                        ApplicationEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.dependentAccessRepository = dependentAccessRepository;
        this.threadService = threadService;
        this.eventPublisher = eventPublisher;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${carenexus.messaging.access-expiry-sweep-interval-ms:60000}",
            initialDelayString = "${carenexus.messaging.access-expiry-sweep-interval-ms:60000}")
    public void sweep() {
        try {
            int processed = sweepOnce(LocalDateTime.now());
            if (processed > 0) {
                log.info("Processed {} expired caregiver grant(s)", processed);
            }
        } catch (RuntimeException e) {
            log.error("Caregiver access expiry sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** @return number of grants processed */
    public int sweepOnce(LocalDateTime now) {
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
