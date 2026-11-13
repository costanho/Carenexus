package com.carenexus.api.lifecycle.listener;

import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.event.UserReactivatedEvent;
import com.carenexus.api.lifecycle.service.AccountDeactivationEffectsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Downstream handling of deactivated and closed accounts.
 * <p>
 * {@link UserDeactivatedEvent} (an administrator's deactivation, a self-closure, or the approval of a deletion request
 * for an account that was still active) starts one clean-up run of {@link AccountDeactivationEffectsService}: a
 * doctor's future appointments are cancelled, a caregiver's grants revoked, a closed patient's appointments, grants,
 * reminders and pending access requests ended, followed by one {@code ACCOUNT_DEACTIVATION_EFFECTS} audit row.
 * <p>
 * The run happens after the deactivating transaction committed, synchronously in the same thread (so it has finished
 * before the HTTP response of deactivate, close or approve is sent), and every unit of it commits in its own
 * transaction. It can never change that response or roll the deactivation back: every {@link Throwable} is caught
 * and logged here.
 * <p>
 * {@link UserReactivatedEvent} restores nothing. Cancelled appointments stay cancelled (a new appointment must be
 * booked), revoked caregiver grants stay revoked (the patient grants access again), switched-off reminders stay off
 * and cancelled access requests stay cancelled. The reactivation is only logged; the administrator is told so by the
 * activate response ("Account reactivated. Cancelled appointments and revoked caregiver access were not restored.").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountLifecycleListener {

    private final AccountDeactivationEffectsService effectsService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserDeactivated(UserDeactivatedEvent event) {
        if (event == null) {
            return;
        }
        try {
            effectsService.run(event);
        } catch (Throwable failure) {
            log.error("Account deactivation effects for {} failed: {}",
                    event.systemId(), failure.getClass().getName());
        }
    }

    /** Restores nothing; see the class documentation. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserReactivated(UserReactivatedEvent event) {
        if (event == null) {
            return;
        }
        log.info("Account {} reactivated: earlier deactivation effects are not restored", event.systemId());
    }
}
