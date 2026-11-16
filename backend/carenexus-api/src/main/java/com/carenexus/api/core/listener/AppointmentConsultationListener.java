package com.carenexus.api.core.listener;

import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.core.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the appointment consistent with its consultation: when a consultation is completed, an appointment that is
 * still active and has started becomes COMPLETED through AppointmentService's normal outcome path (audit row,
 * AppointmentStatusChangedEvent).
 *
 * A plain {@code @EventListener} (CONTRACTS.v2 section 6): it runs synchronously inside the consultation's
 * transaction, so the appointment change and the events it publishes commit with the consultation, and a failure
 * rolls the completion back. Anything that does not qualify (no appointment, already COMPLETED, cancelled, not
 * started yet, a repeated event) is ignored without an error.
 */
@Component
@RequiredArgsConstructor
public class AppointmentConsultationListener {

    private final AppointmentService appointmentService;

    @EventListener
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        appointmentService.completeFromConsultation(event);
    }
}
