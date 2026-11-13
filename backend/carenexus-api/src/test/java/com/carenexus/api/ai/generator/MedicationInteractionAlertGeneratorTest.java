package com.carenexus.api.ai.generator;

import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc;
import com.carenexus.api.ai.generator.service.MedicationInteractionAlertGenerator;
import com.carenexus.api.ai.interaction.DisabledInteractionChecker;
import com.carenexus.api.ai.interaction.InteractionHit;
import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 5 of api/be-ai-generators.md 5.1, seam 5.4: a new prescription is screened against the same patient's other
 * prescriptions in force (ACTIVE and not expired, by generic name when known); each interacting one raises a
 * MEDICATION_INTERACTION alert for the prescriber only. A disabled or failing checker raises nothing.
 */
class MedicationInteractionAlertGeneratorTest extends CommittedIntegrationTest {

    private static final String ZONE = "Africa/Harare";

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private AiAlertService aiAlertService;

    @Autowired
    private AiGeneratorJdbc generatorJdbc;

    @Autowired
    private Clock clock;

    private AiGeneratorFixtures fixtures;
    private LocalDate today;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new AiGeneratorFixtures(jdbc);
        today = LocalDate.now(clock);
    }

    private int rx(String medicationName, String genericName, String status, LocalDate expiryDate) {
        return fixtures.prescription(patient.getSystemId(), otherDoctor.getSystemId(), medicationName, genericName,
                status, today.minusDays(20), expiryDate);
    }

    private PrescriptionIssuedEvent issued(int prescriptionId, String medicationName) {
        return new PrescriptionIssuedEvent(prescriptionId, null, patient.getSystemId(), doctor.getSystemId(),
                medicationName, "Once daily", today, today.plusDays(30), List.of());
    }

    private int newWarfarin() {
        return fixtures.prescription(patient.getSystemId(), doctor.getSystemId(), "Warfarin 5 mg", "warfarin",
                "ACTIVE", today, today.plusDays(30));
    }

    @Test
    void eachInteractingPrescriptionInForceRaisesOneAlertForThePrescriber() {
        rx("Aspirin 75 mg", null, "ACTIVE", today);                       // valid through today: screened
        rx("Brufen", "Ibuprofen", "ACTIVE", null);                       // matched on the generic name
        rx("Diclofenac", null, "ACTIVE", today.minusDays(1));            // expired yesterday
        rx("Fluconazole", null, "CANCELLED", null);                      // not in force
        rx("Paracetamol", "paracetamol", "ACTIVE", null);                // no rule
        fixtures.prescription(otherPatient.getSystemId(), otherDoctor.getSystemId(), "Aspirin", null, "ACTIVE",
                today.minusDays(2), null);                                // another patient
        int warfarin = newWarfarin();

        events.publishEvent(issued(warfarin, "Warfarin 5 mg"));

        List<Map<String, Object>> alerts = fixtures.alerts(patient.getSystemId(), "MEDICATION_INTERACTION");
        assertEquals(2, alerts.size(), alerts.toString());
        List<String> messages = new ArrayList<>();
        for (Map<String, Object> alert : alerts) {
            messages.add((String) alert.get("message"));
            assertEquals("HIGH", alert.get("priority"));
            assertEquals(Boolean.TRUE, alert.get("action_required"));
            assertEquals("Possible medication interaction", alert.get("title"));
            assertEquals("Review both prescriptions before the patient continues treatment.",
                    alert.get("suggested_action"));
            assertEquals("PRESCRIPTION", alert.get("source_type"));
            assertEquals(warfarin, alert.get("source_id"));
            assertEquals(doctor.getSystemId(), alert.get("doctor_system_id"), "the prescriber only");
        }
        assertEquals(List.of(
                "Warfarin 5 mg may interact with Aspirin 75 mg, which this patient is currently prescribed.",
                "Warfarin 5 mg may interact with Brufen, which this patient is currently prescribed."), messages);
        assertEquals("MEDICATION_INTERACTION:" + warfarin + ":aspirin 75 mg", alerts.get(0).get("dedupe_key"));
        assertEquals("MEDICATION_INTERACTION:" + warfarin + ":ibuprofen", alerts.get(1).get("dedupe_key"));

        assertEquals(2, fixtures.aiAlertNotifications(doctor.getSystemId()));
        assertEquals(0, fixtures.aiAlertNotifications(otherDoctor.getSystemId()),
                "the doctor of the active prescription is not notified");
        assertEquals(0, fixtures.aiAlertNotifications(patient.getSystemId()));

        // A redelivered event adds nothing.
        events.publishEvent(issued(warfarin, "Warfarin 5 mg"));
        assertEquals(2, fixtures.alertCount());
        assertEquals(2, fixtures.aiAlertNotifications(doctor.getSystemId()));
    }

    @Test
    void theNewPrescriptionIsNotScreenedAgainstItselfOrTheSameMedication() {
        rx("Coumadin", "Warfarin", "ACTIVE", null);
        int warfarin = newWarfarin();

        events.publishEvent(issued(warfarin, "Warfarin 5 mg"));

        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void noOtherPrescriptionOrAnUnknownPrescriptionRaisesNothing() {
        int warfarin = newWarfarin();
        events.publishEvent(issued(warfarin, "Warfarin 5 mg"));
        events.publishEvent(issued(987654, "Warfarin"));
        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void theDisabledCheckerRaisesNothing() {
        rx("Aspirin", null, "ACTIVE", null);
        int warfarin = newWarfarin();
        MedicationInteractionAlertGenerator disabled = new MedicationInteractionAlertGenerator(aiAlertService,
                generatorJdbc, new DisabledInteractionChecker(), clock, ZONE);

        assertTrue(disabled.generate(issued(warfarin, "Warfarin 5 mg")).isEmpty());
        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void aFailingCheckerIsSwallowedAndRaisesNothing() {
        rx("Aspirin", null, "ACTIVE", null);
        int warfarin = newWarfarin();
        MedicationInteractionChecker failing = new MedicationInteractionChecker() {
            @Override
            public String providerName() {
                return "failing";
            }

            @Override
            public List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames) {
                throw new IllegalStateException("interaction service unavailable");
            }
        };
        MedicationInteractionAlertGenerator generator = new MedicationInteractionAlertGenerator(aiAlertService,
                generatorJdbc, failing, clock, ZONE);

        assertTrue(generator.generate(issued(warfarin, "Warfarin 5 mg")).isEmpty());
        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void aHitForANameTheGeneratorDidNotAskAboutIsIgnored() {
        rx("Aspirin", null, "ACTIVE", null);
        int warfarin = newWarfarin();
        MedicationInteractionChecker inventive = new MedicationInteractionChecker() {
            @Override
            public String providerName() {
                return "inventive";
            }

            @Override
            public List<InteractionHit> check(String newMedicationName, List<String> activeMedicationNames) {
                return List.of(new InteractionHit(newMedicationName, "Something else"));
            }
        };
        MedicationInteractionAlertGenerator generator = new MedicationInteractionAlertGenerator(aiAlertService,
                generatorJdbc, inventive, clock, ZONE);

        assertTrue(generator.generate(issued(warfarin, "Warfarin 5 mg")).isEmpty());
        assertEquals(0, fixtures.alertCount());
    }
}
