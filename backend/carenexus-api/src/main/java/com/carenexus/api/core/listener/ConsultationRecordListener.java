package com.carenexus.api.core.listener;

import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.core.service.MedicalRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Creates the automatic CONSULT medical record when a consultation is completed.
 *
 * A plain {@code @EventListener}: it runs synchronously inside the transaction of the request that completes the
 * consultation, so the record exists when that request returns and is rolled back with it. The insert is guarded
 * by the V40 partial unique index ({@code ON CONFLICT DO NOTHING}) and a NOT EXISTS check, so a repeated event or a
 * CONSULT record the doctor already filed by hand adds nothing and never fails the completion.
 */
@Component
@RequiredArgsConstructor
public class ConsultationRecordListener {

    private final MedicalRecordService medicalRecordService;

    @EventListener
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        medicalRecordService.recordCompletedConsultation(event);
    }
}
