package com.carenexus.api.lifecycle.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeactivationEffectsTest {

    @Test
    void summary_listsAllEightKeysInContractOrder() {
        DeactivationEffects effects = new DeactivationEffects("ADMIN", "DOCTOR");
        effects.appointmentCancelled();
        effects.appointmentCancelled();
        effects.pendingReferrals(1);

        assertFalse(effects.anyFailed());
        assertEquals("cause=ADMIN; role=DOCTOR; appointmentsCancelled=2; grantsRevoked=0; remindersDeactivated=0; "
                + "accessRequestsCancelled=0; pendingReferrals=1; failed=none", effects.summary());
    }

    @Test
    void failures_areListedInStepOrder_withTheirUnitCounts() {
        DeactivationEffects effects = new DeactivationEffects("CLOSURE", "PATIENT");
        effects.failed(DeactivationStep.REMINDERS);
        effects.failed(DeactivationStep.APPOINTMENTS);
        effects.failed(DeactivationStep.APPOINTMENTS);
        effects.grantRevoked();
        effects.remindersDeactivated(3);
        effects.accessRequestsCancelled(2);
        effects.accessRequestsCancelled(-1);

        assertTrue(effects.anyFailed());
        assertEquals(2, effects.failures(DeactivationStep.APPOINTMENTS));
        assertEquals(0, effects.failures(DeactivationStep.GRANTS));
        assertEquals("cause=CLOSURE; role=PATIENT; appointmentsCancelled=0; grantsRevoked=1; remindersDeactivated=3; "
                + "accessRequestsCancelled=2; pendingReferrals=0; failed=APPOINTMENTS:2,REMINDERS:1", effects.summary());
    }
}
