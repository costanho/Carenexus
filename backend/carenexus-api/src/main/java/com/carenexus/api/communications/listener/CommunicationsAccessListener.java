package com.carenexus.api.communications.listener;

import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverMessagingRegainedEvent;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.service.ThreadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * When a caregiver's grant is revoked or no longer allows messaging, the caregiver is removed (ACCESS_REVOKED)
 * from every conversation about that patient. Request-time checks already block access immediately; this keeps
 * membership consistent. Membership is not restored automatically when access returns.
 *
 * The AFTER_COMMIT clean-up only logs when it fails, so a membership can survive a revocation or lapse. When access
 * RETURNS, {@link #onMessagingRegained} ends any such membership synchronously, inside the grant change's own
 * transaction: if that fails the grant change fails too, so a caregiver never regains an old history boundary.
 * Grant expiry itself is handled by CaregiverAccessExpirySweeper (ACCESS_EXPIRED).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommunicationsAccessListener {

    private final ThreadService threadService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCaregiverAccessChanged(CaregiverAccessChangedEvent event) {
        if (event == null || (!event.revoked() && event.messagingAllowed())) {
            return;
        }
        try {
            int removed = threadService.removeCaregiverAfterAccessChange(event.caregiverSystemId(), event.patientSystemId());
            if (removed > 0) {
                log.info("Caregiver access {} changed: removed {} conversation membership(s)", event.accessId(), removed);
            }
        } catch (RuntimeException failure) {
            log.error("Failed to update conversation memberships after access change {}: {}",
                    event.accessId(), failure.getClass().getName());
            markRollbackOnly();
        }
    }

    private static void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // invoked without a transaction (unit tests)
        }
    }

    @EventListener
    public void onMessagingRegained(CaregiverMessagingRegainedEvent event) {
        if (event == null) {
            return;
        }
        String reason = event.wasExpired() ? ThreadMember.REASON_ACCESS_EXPIRED : ThreadMember.REASON_ACCESS_REVOKED;
        int removed = threadService.removeCaregiverAfterAccessChange(event.caregiverSystemId(), event.patientSystemId(), reason);
        if (removed > 0) {
            log.info("Caregiver access {} returned: ended {} stale conversation membership(s)", event.accessId(), removed);
        }
    }
}
