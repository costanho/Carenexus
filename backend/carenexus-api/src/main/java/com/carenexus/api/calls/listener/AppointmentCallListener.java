package com.carenexus.api.calls.listener;

import com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent;
import com.carenexus.api.calls.service.CallSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Syncs the call session inside the appointment transaction (BEFORE_COMMIT), so session and job rows commit
 * atomically with the appointment change. No provider call runs here (provider work is a job).
 */
@Component
@RequiredArgsConstructor
public class AppointmentCallListener {

    private final CallSessionService callSessionService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void onAppointmentChange(AppointmentCallRelevantChangeEvent event) {
        callSessionService.syncWithAppointment(event.appointmentId(), event.kind());
    }
}
