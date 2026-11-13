package com.carenexus.api.ai.interaction;

import java.util.List;

/**
 * {@code carenexus.ai.interaction-provider=disabled}: no interaction screening. Every check finds nothing, so no
 * MEDICATION_INTERACTION alert is ever raised.
 */
public class DisabledInteractionChecker implements MedicationInteractionChecker {

    public static final String PROVIDER = "disabled";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames) {
        return List.of();
    }
}
