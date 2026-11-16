package com.carenexus.api.ai.generator.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Row 1 of api/be-ai-generators.md 5.1: a lab or imaging result that has just become critical raises one
 * CRITICAL_RESULT alert (priority CRITICAL, action required) for every doctor related to the patient. This alert,
 * through its AI_ALERT notification, is the only doctor-facing notice of a critical result.
 * <p>
 * Dedupe key {@code CRITICAL_RESULT:<LAB|IMAGING>:<resultId>}: one alert per result for ever, so a result that
 * stops being critical and becomes critical again, a repeated update or a re-delivered event raises nothing new.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriticalResultAlertGenerator {

    private final AiAlertService aiAlertService;

    /** @return the new alert id; empty when the event is not a transition to critical or the alert exists */
    public Optional<Integer> generate(ClinicalResultRecordedEvent event) {
        if (event == null || !event.newlyCritical() || event.resultId() == null || event.patientSystemId() == null) {
            return Optional.empty();
        }
        boolean lab = ClinicalResultRecordedEvent.LAB.equals(event.kind());
        boolean imaging = ClinicalResultRecordedEvent.IMAGING.equals(event.kind());
        if (!lab && !imaging) {
            log.warn("Critical result event of unknown kind ignored (result {})", event.resultId());
            return Optional.empty();
        }
        return aiAlertService.raise(new AiAlertCommand(
                event.patientSystemId(),
                null,
                AiAlertType.CRITICAL_RESULT.name(),
                AiAlertPriority.CRITICAL.name(),
                lab ? AlertTexts.CRITICAL_LAB_TITLE : AlertTexts.CRITICAL_IMAGING_TITLE,
                lab ? AlertTexts.CRITICAL_LAB_MESSAGE : AlertTexts.CRITICAL_IMAGING_MESSAGE,
                true,
                AlertTexts.CRITICAL_RESULT_ACTION,
                (lab ? AiAlertSourceType.LAB_RESULT : AiAlertSourceType.IMAGING_RESULT).name(),
                event.resultId(),
                null,
                dedupeKey(event.kind(), event.resultId())));
    }

    public static String dedupeKey(String kind, Integer resultId) {
        return AiAlertType.CRITICAL_RESULT.name() + ":" + kind + ":" + resultId;
    }
}
