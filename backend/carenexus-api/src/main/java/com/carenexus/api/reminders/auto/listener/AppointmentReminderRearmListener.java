package com.carenexus.api.reminders.auto.listener;

import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Re-arms both automatic reminders when an appointment moves to another start time: clears
 * {@code appointments.reminder_sent} and {@code appointments.reminder_1h_sent}, so the new time earns its own first
 * and second reminder (their dedupe keys include the start time).
 *
 * A plain {@code @EventListener}: it runs inside the reschedule transaction, and a failure rolls the reschedule
 * back. Pending entity changes are flushed first, so the service's own UPDATE of the appointment row cannot land
 * after the native one. An event with an unchanged start time is ignored; an unknown appointment id updates
 * nothing.
 */
@Component
@Slf4j
public class AppointmentReminderRearmListener {

    private final ReminderJobsJdbc jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    public AppointmentReminderRearmListener(ReminderJobsJdbc jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional
    public void onRescheduled(AppointmentRescheduledEvent event) {
        if (event == null || event.appointmentId() == null
                || Objects.equals(event.previousScheduledAt(), event.scheduledAt())) {
            return;
        }
        entityManager.flush();
        int updated = jdbc.rearm(event.appointmentId());
        if (updated > 0) {
            log.debug("Automatic reminders re-armed for appointment {}", event.appointmentId());
        }
    }
}
