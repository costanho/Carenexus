package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc.PrescriptionRow;
import com.carenexus.api.ai.interaction.InteractionHit;
import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Row 5 of api/be-ai-generators.md 5.1, seam 5.4: a newly issued prescription is screened against the patient's
 * other prescriptions in force; each interacting one raises a MEDICATION_INTERACTION alert (priority HIGH, action
 * required) addressed to the prescribing doctor only.
 * <ul>
 *   <li>names screened: {@code generic_name} when not blank, otherwise {@code medication_name};</li>
 *   <li>prescriptions in force: same patient, stored status ACTIVE, expiry date null or not before the clinic's
 *       today, other than the new one;</li>
 *   <li>the message names exactly the two medications ({@code medication_name} as stored) and nothing else;</li>
 *   <li>a checker that throws is logged (exception class and prescription id only) and raises nothing.</li>
 * </ul>
 * Dedupe key {@code MEDICATION_INTERACTION:<prescriptionId>:<lower-cased active name, cut to 100>}: one alert per
 * interacting pair.
 */
@Component
@Slf4j
public class MedicationInteractionAlertGenerator {

    static final int MAX_NAME_IN_KEY = 100;

    private final AiAlertService aiAlertService;
    private final AiGeneratorJdbc generatorJdbc;
    private final MedicationInteractionChecker checker;
    private final Clock clock;
    private final ZoneId clinicZone;

    @Autowired
    public MedicationInteractionAlertGenerator(AiAlertService aiAlertService, AiGeneratorJdbc generatorJdbc,
                                               MedicationInteractionChecker checker, Clock clock,
                                               @Value("${carenexus.scheduling.time-zone:Africa/Harare}")
                                               String timeZone) {
        this.aiAlertService = aiAlertService;
        this.generatorJdbc = generatorJdbc;
        this.checker = checker;
        this.clock = clock;
        this.clinicZone = ZoneId.of(timeZone);
    }

    /** @return the ids of the alerts raised (empty when nothing interacts or every alert exists) */
    public List<Integer> generate(PrescriptionIssuedEvent event) {
        if (event == null || event.prescriptionId() == null) {
            return List.of();
        }
        PrescriptionRow issued = generatorJdbc.findPrescription(event.prescriptionId()).orElse(null);
        if (issued == null) {
            log.warn("Interaction screening skipped: prescription {} not found", event.prescriptionId());
            return List.of();
        }
        LocalDate clinicToday = LocalDate.ofInstant(clock.instant(), clinicZone);
        List<PrescriptionRow> active = generatorJdbc.findOtherActivePrescriptions(issued.patientSystemId(),
                issued.prescriptionId(), clinicToday);
        if (active.isEmpty()) {
            return List.of();
        }

        // One entry per distinct screening name (case-insensitive); the first prescription with it is named.
        Map<String, PrescriptionRow> byKey = new LinkedHashMap<>();
        Map<String, String> keyByName = new LinkedHashMap<>();
        for (PrescriptionRow row : active) {
            String name = row.screeningName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String key = nameKey(name);
            if (!byKey.containsKey(key)) {
                byKey.put(key, row);
                keyByName.put(name, key);
            }
        }
        if (keyByName.isEmpty()) {
            return List.of();
        }

        List<InteractionHit> hits;
        try {
            hits = checker.check(issued.screeningName(), new ArrayList<>(keyByName.keySet()));
        } catch (RuntimeException e) {
            log.warn("Medication interaction check failed for prescription {}: {}", issued.prescriptionId(),
                    e.getClass().getName());
            return List.of();
        }
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        String doctor = event.doctorSystemId() != null ? event.doctorSystemId() : issued.doctorSystemId();
        List<Integer> raised = new ArrayList<>();
        for (InteractionHit hit : hits) {
            String key = hit == null ? null : keyByName.get(hit.activeMedicationName());
            PrescriptionRow interacting = key == null ? null : byKey.get(key);
            if (interacting == null) {
                continue;
            }
            aiAlertService.raise(new AiAlertCommand(
                    issued.patientSystemId(),
                    doctor,
                    AiAlertType.MEDICATION_INTERACTION.name(),
                    AiAlertPriority.HIGH.name(),
                    AlertTexts.INTERACTION_TITLE,
                    AlertTexts.interactionMessage(issued.medicationName(), interacting.medicationName()),
                    true,
                    AlertTexts.INTERACTION_ACTION,
                    AiAlertSourceType.PRESCRIPTION.name(),
                    issued.prescriptionId(),
                    null,
                    dedupeKey(issued.prescriptionId(), hit.activeMedicationName()))).ifPresent(raised::add);
        }
        return raised;
    }

    public static String dedupeKey(Integer prescriptionId, String activeMedicationName) {
        return AiAlertType.MEDICATION_INTERACTION.name() + ":" + prescriptionId + ":" + nameKey(activeMedicationName);
    }

    /** Trimmed, lower case, cut to {@value #MAX_NAME_IN_KEY} characters (never splitting a surrogate pair). */
    static String nameKey(String name) {
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (key.codePointCount(0, key.length()) > MAX_NAME_IN_KEY) {
            key = key.substring(0, key.offsetByCodePoints(0, MAX_NAME_IN_KEY));
        }
        return key;
    }
}
