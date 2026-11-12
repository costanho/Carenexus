package com.carenexus.api.ai.interaction;

import java.util.List;

/**
 * Medication-interaction seam (CONTRACTS.v2 section 9, api/be-ai-generators.md 5.4). Screens a newly prescribed
 * medication against the medications a patient is already prescribed.
 * <p>
 * Selected by {@code carenexus.ai.interaction-provider}: {@code rules} ({@link StaticRuleInteractionChecker}, the
 * default local implementation) or {@code disabled} ({@link DisabledInteractionChecker}). A real interaction
 * service (formulary or drug-interaction database) is plugged in by adding an implementation and returning it from
 * {@code AiGeneratorConfig} for its own provider id.
 * <p>
 * The result is a screening aid for the prescribing doctor, not clinical advice. Implementations never log the
 * medication names they receive.
 */
public interface MedicationInteractionChecker {

    /** Provider id reported to the startup guard and in logs, for example {@code "rules"} or {@code "disabled"}. */
    String providerName();

    /**
     * Screens {@code newMedicationName} against each of {@code activeMedicationNames}.
     *
     * @param newMedicationName     name of the medication being prescribed (generic name when known)
     * @param activeMedicationNames names of the patient's other active prescriptions; null or blank entries are
     *                              ignored
     * @return one hit per interacting active medication, in the order of the input list; each hit's
     *         {@link InteractionHit#activeMedicationName()} is an element of {@code activeMedicationNames},
     *         unchanged. Never null; empty when nothing interacts
     */
    List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames);
}
