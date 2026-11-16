package com.carenexus.api.calls.listener;

import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.service.CallSessionService;
import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes people from calls when their access ends:
 * - caregiver access revoked or JOIN_CALLS no longer allowed: their open rows in this patient's calls (ACCESS_REVOKED);
 * - logout: all open rows of the user (LOGGED_OUT).
 * Rows become REMOVED with the revoked_at cutoff; admitted ones get removal_pending and a REMOVE_PARTICIPANT job.
 *
 * Runs after the publishing transaction committed, in a NEW transaction. Failures are logged (class name only) and
 * never propagate to the publisher; the work runs in a TransactionTemplate so a rollback cannot escape as an
 * UnexpectedRollbackException.
 */
@Slf4j
@Component
public class CallAccessListener {

    private final CallSessionService callSessionService;
    private final TransactionTemplate requiresNew;

    public CallAccessListener(CallSessionService callSessionService, PlatformTransactionManager transactionManager) {
        this.callSessionService = callSessionService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCaregiverAccessChanged(CaregiverAccessChangedEvent event) {
        if (!event.revoked() && event.joinCallsAllowed()) {
            return;
        }
        try {
            Integer removed = requiresNew.execute(status -> callSessionService.removeUserFromOpenCalls(
                    event.caregiverSystemId(), event.patientSystemId(), CallParticipant.REASON_ACCESS_REVOKED));
            if (removed != null && removed > 0) {
                log.info("Removed caregiver from {} call(s) after access change (access {})", removed, event.accessId());
            }
        } catch (RuntimeException e) {
            log.error("Call cleanup after caregiver access change failed: {}", e.getClass().getSimpleName());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserLoggedOut(UserLoggedOutEvent event) {
        try {
            requiresNew.execute(status -> callSessionService.removeUserFromOpenCalls(event.systemId(), null,
                    CallParticipant.REASON_LOGGED_OUT));
        } catch (RuntimeException e) {
            log.error("Call cleanup after logout failed: {}", e.getClass().getSimpleName());
        }
    }
}
