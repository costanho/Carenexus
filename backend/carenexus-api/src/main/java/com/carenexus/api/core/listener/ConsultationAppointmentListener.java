package com.carenexus.api.core.listener;

import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.core.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps consultations consistent with their appointment: when an appointment is cancelled (by a user or by the
 * system) or marked NO_SHOW, every DRAFT or IN_PROGRESS consultation of that appointment becomes CANCELLED.
 * COMPLETED consultations are never touched (the appointment service refuses to cancel or mark NO_SHOW an
 * appointment that has one).
 *
 * Plain {@code @EventListener}s: they run synchronously inside the appointment change's transaction, so the
 * consultation and the appointment commit (or roll back) together. Repeated events are no-ops.
 */
@Component
@RequiredArgsConstructor
public class ConsultationAppointmentListener {

    static final String NO_SHOW = "NO_SHOW";

    private final ConsultationService consultationService;

    @EventListener
    public void onAppointmentCancelled(AppointmentCancelledEvent event) {
        if (event == null || event.appointmentId() == null) {
            return;
        }
        consultationService.cancelOpenConsultationsForAppointment(event.appointmentId());
    }

    @EventListener
    public void onAppointmentStatusChanged(AppointmentStatusChangedEvent event) {
        if (event == null || event.appointmentId() == null || !NO_SHOW.equalsIgnoreCase(event.status())) {
            return;
        }
        consultationService.cancelOpenConsultationsForAppointment(event.appointmentId());
    }
}
