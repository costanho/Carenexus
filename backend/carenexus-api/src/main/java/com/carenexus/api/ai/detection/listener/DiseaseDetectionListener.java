package com.carenexus.api.ai.detection.listener;

import com.carenexus.api.ai.detection.service.DetectionPipeline;
import com.carenexus.api.ai.detection.service.RiskAssessmentException;
import com.carenexus.api.ai.detection.service.ScreeningDispatcher;
import com.carenexus.api.ai.detection.service.ScreeningTrigger;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts an automatic disease-risk screening after a symptom log (any severity) or an abnormal reading was saved
 * (api/be-ai-detection.md section 7). Runs after the origin committed; the screening uses its own short
 * transactions ({@link DetectionPipeline#screenAutomatically}). Screening switched off, a provider failure or
 * timeout, or any other error is logged (ids and exception class only) and never fails or undoes the origin write.
 * <p>
 * Off the request thread: the listener only hands the screening to {@link ScreeningDispatcher} (bounded pool and
 * queue), so the origin's HTTP response and its database connection never wait for the provider. On the worker
 * thread the input is read in one short transaction, the provider runs with no transaction and no pooled connection
 * held, and the detection and its alert are written in a second short transaction. The DISEASE_RISK detection and
 * alert therefore appear shortly after the response, not before it. A full queue skips the screening with a WARN
 * line. {@code fallbackExecution} lets an event published outside a transaction be screened too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiseaseDetectionListener {

    private final DetectionPipeline pipeline;
    private final ScreeningDispatcher dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSymptomLogged(SymptomLoggedEvent event) {
        if (event == null || event.symptomId() == null || event.patientSystemId() == null) {
            return;
        }
        screen(event.patientSystemId(), ScreeningTrigger.symptomLog(event.symptomId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHealthMetricRecorded(HealthMetricRecordedEvent event) {
        if (event == null || !event.abnormal() || event.metricId() == null || event.patientSystemId() == null) {
            return;
        }
        screen(event.patientSystemId(), ScreeningTrigger.healthMetric(event.metricId()));
    }

    private void screen(String patientSystemId, ScreeningTrigger trigger) {
        if (!dispatcher.dispatch(() -> runScreening(patientSystemId, trigger))) {
            log.warn("Automatic risk screening of {} after {} {} skipped: the screening queue is full",
                    patientSystemId, trigger.sourceType(), trigger.sourceId());
        }
    }

    private void runScreening(String patientSystemId, ScreeningTrigger trigger) {
        try {
            pipeline.screenAutomatically(patientSystemId, trigger).ifPresent(result ->
                    log.debug("Automatic risk screening of {} after {} {}: {}", patientSystemId, trigger.sourceType(),
                            trigger.sourceId(), result.outcome()));
        } catch (RiskAssessmentException e) {
            log.warn("Automatic risk screening of {} after {} {} not completed: {}", patientSystemId,
                    trigger.sourceType(), trigger.sourceId(), e.getReason());
        } catch (RuntimeException e) {
            log.warn("Automatic risk screening of {} after {} {} failed: {}", patientSystemId, trigger.sourceType(),
                    trigger.sourceId(), e.getClass().getName());
        }
    }
}
