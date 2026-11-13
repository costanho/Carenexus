package com.carenexus.api.medication.listener;

import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.medication.service.MedicationPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the dose schedule in step with prescriptions. Plain {@code @EventListener}s: they run synchronously inside
 * the publisher's transaction, so the plan and the doses commit together with the prescription, and a failure here
 * rolls the prescription change back (CONTRACTS.v2 section 6).
 */
@Component
@RequiredArgsConstructor
public class PrescriptionScheduleListener {

    private final MedicationPlanService planService;

    /** Stores the plan and creates today's remaining and tomorrow's doses. */
    @EventListener
    public void onPrescriptionIssued(PrescriptionIssuedEvent event) {
        planService.onPrescriptionIssued(event);
    }

    /** Leaving ACTIVE removes future untaken doses; returning to ACTIVE recreates them. */
    @EventListener
    public void onPrescriptionStatusChanged(PrescriptionStatusChangedEvent event) {
        planService.onPrescriptionStatusChanged(event);
    }
}
