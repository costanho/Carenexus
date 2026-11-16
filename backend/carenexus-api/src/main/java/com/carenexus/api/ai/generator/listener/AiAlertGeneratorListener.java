package com.carenexus.api.ai.generator.listener;

import com.carenexus.api.ai.generator.service.AnomalyGenerator;
import com.carenexus.api.ai.generator.service.CriticalResultAlertGenerator;
import com.carenexus.api.ai.generator.service.MedicationInteractionAlertGenerator;
import com.carenexus.api.ai.generator.service.MissedMedicationAlertGenerator;
import com.carenexus.api.ai.generator.service.VitalSignAlertGenerator;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the event-driven alert generators (api/be-ai-generators.md 5.1 rows 1 to 5; row 6 is the follow-up-due job).
 * <p>
 * Timing and failure rules (contract 5.7, CONTRACTS.v2 section 6):
 * <ul>
 *   <li>each method runs after the originating transaction committed ({@code AFTER_COMMIT}; with
 *       {@code fallbackExecution} also for an event published outside a transaction), synchronously on the
 *       publishing thread, so the alert and its AI_ALERT notifications exist before the originating HTTP response
 *       is sent. A rolled-back origin raises nothing;</li>
 *   <li>the work runs in a NEW transaction through a {@link TransactionTemplate}, so a failure rolls back only the
 *       generator's own writes and can never surface as an {@code UnexpectedRollbackException};</li>
 *   <li>any failure is logged with the source id and the exception class only and swallowed: no originating
 *       endpoint changes its status, body or errors because of a generator or provider;</li>
 *   <li>every generator is idempotent through its dedupe key, so a re-delivered event raises nothing new.</li>
 * </ul>
 */
@Component
@Slf4j
public class AiAlertGeneratorListener {

    private final CriticalResultAlertGenerator criticalResults;
    private final VitalSignAlertGenerator vitalSigns;
    private final AnomalyGenerator anomalies;
    private final MissedMedicationAlertGenerator missedMedication;
    private final MedicationInteractionAlertGenerator interactions;
    private final TransactionTemplate requiresNew;

    public AiAlertGeneratorListener(CriticalResultAlertGenerator criticalResults, VitalSignAlertGenerator vitalSigns,
                                    AnomalyGenerator anomalies, MissedMedicationAlertGenerator missedMedication,
                                    MedicationInteractionAlertGenerator interactions,
                                    PlatformTransactionManager transactionManager) {
        this.criticalResults = criticalResults;
        this.vitalSigns = vitalSigns;
        this.anomalies = anomalies;
        this.missedMedication = missedMedication;
        this.interactions = interactions;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Row 1: CRITICAL_RESULT when a lab or imaging result has just become critical. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onClinicalResultRecorded(ClinicalResultRecordedEvent event) {
        if (!event.newlyCritical()) {
            return;
        }
        run("critical-result", event.resultId(), () -> criticalResults.generate(event));
    }

    /** Row 2 (abnormal reading): CRITICAL_VITALS. Row 3 (normal reading): ANOMALY_DETECTED when the rule holds. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHealthMetricRecorded(HealthMetricRecordedEvent event) {
        if (event.abnormal()) {
            run("critical-vitals", event.metricId(), () -> vitalSigns.generate(event));
        } else {
            run("anomaly", event.metricId(), () -> anomalies.generate(event));
        }
    }

    /** Row 4: MISSED_MEDICATION once the consecutive misses reach the threshold. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMedicationDoseMissed(MedicationDoseMissedEvent event) {
        run("missed-medication", event.prescriptionId(), () -> missedMedication.generate(event));
    }

    /** Row 5: MEDICATION_INTERACTION for the prescriber when the new prescription interacts with an active one. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPrescriptionIssued(PrescriptionIssuedEvent event) {
        run("medication-interaction", event.prescriptionId(), () -> interactions.generate(event));
    }

    private void run(String generator, Integer sourceId, Runnable work) {
        try {
            requiresNew.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.error("AI alert generator {} failed for source {}: {}", generator, sourceId, e.getClass().getName());
        }
    }
}
