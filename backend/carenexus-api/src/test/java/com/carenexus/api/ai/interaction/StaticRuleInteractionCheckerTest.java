package com.carenexus.api.ai.interaction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The static interaction rule table (api/be-ai-generators.md 5.4): every pinned pair in both directions, whole-word
 * and case-insensitive matching, the same medication on both sides, unchanged list elements in the hits, and the
 * disabled checker.
 */
class StaticRuleInteractionCheckerTest {

    private static final List<List<String>> PINNED = List.of(
            List.of("warfarin", "aspirin"),
            List.of("warfarin", "ibuprofen"),
            List.of("warfarin", "diclofenac"),
            List.of("warfarin", "fluconazole"),
            List.of("simvastatin", "clarithromycin"),
            List.of("simvastatin", "erythromycin"),
            List.of("sildenafil", "nitroglycerin"),
            List.of("lisinopril", "spironolactone"),
            List.of("enalapril", "spironolactone"),
            List.of("methotrexate", "trimethoprim"),
            List.of("digoxin", "amiodarone"),
            List.of("tramadol", "sertraline"),
            List.of("clopidogrel", "omeprazole"));

    private final StaticRuleInteractionChecker checker = new StaticRuleInteractionChecker();

    @Test
    void everyPinnedPairInteractsInBothDirections() {
        assertEquals("rules", checker.providerName());
        for (List<String> pair : PINNED) {
            assertEquals(List.of(new InteractionHit(pair.get(0), pair.get(1))),
                    checker.check(pair.get(0), List.of(pair.get(1))), "rule " + pair);
            assertEquals(List.of(new InteractionHit(pair.get(1), pair.get(0))),
                    checker.check(pair.get(1), List.of(pair.get(0))), "rule " + pair + " reversed");
        }
    }

    @Test
    void namesAreMatchedCaseInsensitivelyAsWholeWords() {
        assertEquals(1, checker.check("  Warfarin 5 mg ", List.of("ASPIRIN")).size());
        assertEquals(1, checker.check("warfarin sodium", List.of("Aspirin (low dose) 75mg")).size());
        assertEquals(1, checker.check("Co-Aspirin", List.of("warfarin")).size(), "a hyphen separates words");
        assertTrue(checker.check("warfarinx", List.of("aspirin")).isEmpty(), "part of a longer word");
        assertTrue(checker.check("warfarin", List.of("aspirinate")).isEmpty(), "part of a longer word");
        assertTrue(checker.check("warfarin", List.of("paracetamol")).isEmpty(), "no rule");
        assertTrue(checker.check("aspirin", List.of("ibuprofen")).isEmpty(), "both in rules but not as a pair");
    }

    @Test
    void theSameMedicationOnBothSidesIsNotAnInteraction() {
        assertTrue(checker.check("Warfarin", List.of(" warfarin ")).isEmpty());
        assertTrue(checker.check("aspirin", List.of("Aspirin")).isEmpty());
    }

    @Test
    void hitsKeepTheInputElementsUnchangedAndInOrder() {
        String aspirin = "  Aspirin 75 mg ";
        String ibuprofen = "Ibuprofen";
        List<String> active = new ArrayList<>(Arrays.asList("Paracetamol", aspirin, null, " ", ibuprofen, "warfarin"));

        List<InteractionHit> hits = checker.check("Warfarin", active);

        assertEquals(2, hits.size());
        assertSame(aspirin, hits.get(0).activeMedicationName());
        assertSame(ibuprofen, hits.get(1).activeMedicationName());
        assertEquals("Warfarin", hits.get(0).newMedicationName());
    }

    @Test
    void emptyOrMissingInputGivesNoHitsAndNeverNull() {
        assertNotNull(checker.check(null, List.of("aspirin")));
        assertTrue(checker.check(null, List.of("aspirin")).isEmpty());
        assertTrue(checker.check("  ", List.of("aspirin")).isEmpty());
        assertTrue(checker.check("warfarin", null).isEmpty());
        assertTrue(checker.check("warfarin", List.of()).isEmpty());
    }

    @Test
    void hitsNeverPrintMedicationNames() {
        assertEquals("InteractionHit[names hidden]", new InteractionHit("warfarin", "aspirin").toString());
    }

    @Test
    void theDisabledCheckerFindsNothing() {
        DisabledInteractionChecker disabled = new DisabledInteractionChecker();
        assertEquals("disabled", disabled.providerName());
        assertTrue(disabled.check("warfarin", List.of("aspirin")).isEmpty());
    }
}
