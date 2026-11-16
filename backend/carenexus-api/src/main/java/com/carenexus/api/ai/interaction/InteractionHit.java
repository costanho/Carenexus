package com.carenexus.api.ai.interaction;

/**
 * One possible interaction found by a {@link MedicationInteractionChecker}.
 *
 * @param newMedicationName    the medication being prescribed, as passed to the checker
 * @param activeMedicationName the interacting active medication: an element of the checker's input list, unchanged
 */
public record InteractionHit(String newMedicationName, String activeMedicationName) {

    /** Medication names are clinical detail: keep them out of logs. */
    @Override
    public String toString() {
        return "InteractionHit[names hidden]";
    }
}
