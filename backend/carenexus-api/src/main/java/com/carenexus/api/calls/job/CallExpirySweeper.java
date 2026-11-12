package com.carenexus.api.calls.job;

import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.service.CallSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Every sweeper interval: sessions whose join window closed end as EXPIRED, and ADMITTED caregivers of LIVE
 * sessions are re-validated against their current JOIN_CALLS permission (covers grant expiry).
 * Each session is processed in its own transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallExpirySweeper {

    private final CallSessionRepository callSessionRepository;
    private final CallSessionService callSessionService;

    @Scheduled(fixedDelayString = "${carenexus.calls.sweeper-interval-ms:60000}",
            initialDelayString = "${carenexus.calls.sweeper-interval-ms:60000}")
    public void sweep() {
        try {
            sweepOnce(Instant.now());
        } catch (RuntimeException e) {
            log.error("Call expiry sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    public void sweepOnce(Instant now) {
        List<Long> closed = callSessionRepository.findIdsClosedBefore(CallSession.SCHEDULED_STATUSES, now);
        for (Long id : closed) {
            try {
                callSessionService.expireIfClosed(id, now);
            } catch (RuntimeException e) {
                log.warn("Expiring call session {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
        for (Long id : callSessionRepository.findIdsByStatus(CallSession.LIVE)) {
            try {
                int removed = callSessionService.revalidateCaregivers(id);
                if (removed > 0) {
                    log.info("Removed {} caregiver(s) without call permission from call session {}", removed, id);
                }
            } catch (RuntimeException e) {
                log.warn("Re-validating call session {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
    }
}
