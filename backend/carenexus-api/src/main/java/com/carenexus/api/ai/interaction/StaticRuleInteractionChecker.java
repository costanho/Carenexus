package com.carenexus.api.ai.interaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code carenexus.ai.interaction-provider=rules} (the default): the local implementation of the interaction seam, a
 * small static table of well-known interacting pairs used as screening reference (api/be-ai-generators.md 5.4). It is
 * not a drug-interaction database and not clinical advice; production needs a real provider or
 * {@code carenexus.allow-local-providers=true}.
 * <p>
 * Matching: names are trimmed and lower-cased; a rule name matches a prescription name when it equals it or occurs
 * in it as a whole word ({@code "Warfarin 5 mg"} matches {@code warfarin}, {@code "warfarinx"} does not). Rules are
 * unordered pairs. The same medication on both sides is never an interaction.
 */
public class StaticRuleInteractionChecker implements MedicationInteractionChecker {

    public static final String PROVIDER = "rules";

    /** The pinned minimum rule table (unordered pairs of generic names, lower case). */
    static final List<List<String>> RULES = List.of(
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

    private final List<Rule> rules;

    public StaticRuleInteractionChecker() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();
        List<Rule> compiled = new ArrayList<>();
        for (List<String> pair : RULES) {
            Pattern first = patterns.computeIfAbsent(pair.get(0), StaticRuleInteractionChecker::wholeWord);
            Pattern second = patterns.computeIfAbsent(pair.get(1), StaticRuleInteractionChecker::wholeWord);
            compiled.add(new Rule(first, second));
        }
        this.rules = List.copyOf(compiled);
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames) {
        String newName = normalise(newMedicationName);
        if (newName.isEmpty() || activeMedicationNames == null || activeMedicationNames.isEmpty()) {
            return List.of();
        }
        List<InteractionHit> hits = new ArrayList<>();
        for (String active : activeMedicationNames) {
            String activeName = normalise(active);
            if (activeName.isEmpty() || activeName.equals(newName)) {
                continue;
            }
            if (interacts(newName, activeName)) {
                hits.add(new InteractionHit(newMedicationName, active));
            }
        }
        return List.copyOf(hits);
    }

    private boolean interacts(String newName, String activeName) {
        for (Rule rule : rules) {
            if ((matches(rule.first(), newName) && matches(rule.second(), activeName))
                    || (matches(rule.second(), newName) && matches(rule.first(), activeName))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Pattern ruleName, String prescriptionName) {
        return ruleName.matcher(prescriptionName).find();
    }

    /** {@code name} as a whole word: not preceded or followed by a letter or digit. */
    private static Pattern wholeWord(String name) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])");
    }

    /** Trimmed, lower case, inner whitespace collapsed; empty for null. */
    static String normalise(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record Rule(Pattern first, Pattern second) {
    }
}
