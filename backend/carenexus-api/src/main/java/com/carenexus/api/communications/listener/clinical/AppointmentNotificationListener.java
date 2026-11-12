package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.common.event.AppointmentBookedEvent;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Appointment change notifications N1 to N4 (api/be-clinical-notifications.md): booked, rescheduled, cancelled and
 * marked NO_SHOW. Recipients are the appointment's doctor, the patient and every caregiver whose grant gives
 * {@link PatientPermission#APPOINTMENTS}, in that order; the actor is never notified (the publisher drops a command
 * whose recipient is the actor), so a patient who books notifies the doctor, and a caregiver who books notifies the
 * patient and the doctor. Type APPOINTMENT, category APPOINTMENT_CHANGE (switched off by
 * {@code appointmentChangeAlerts}), target APPOINTMENTS, resource APPOINTMENT / appointmentId.
 *
 * Plain {@link EventListener} methods: they run synchronously in the publisher's transaction, so the notifications
 * (and the outbound messages derived from them) commit or roll back with the appointment change. Dedupe keys make
 * a repeated event store nothing.
 */
@Component
@RequiredArgsConstructor
public class AppointmentNotificationListener {

    static final String RESOURCE_TYPE = "APPOINTMENT";
    static final String BOOKED_PREFIX = "APPT_BOOKED:";
    static final String RESCHEDULED_PREFIX = "APPT_RESCHEDULED:";
    static final String CANCELLED_PREFIX = "APPT_CANCELLED:";
    static final String NO_SHOW_PREFIX = "APPT_NO_SHOW:";
    static final String NO_SHOW = "NO_SHOW";

    /** Date-times inside dedupe keys: clinic wall-clock, minute precision. */
    static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ClinicalNotifier notifier;

    /** N1. */
    @EventListener
    public void onBooked(AppointmentBookedEvent event) {
        if (event.appointmentId() == null || event.patientSystemId() == null) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        notifier.publishEach(recipients(event.doctorSystemId(), event.patientSystemId()),
                template(event.appointmentId(), event.patientSystemId(), event.actorSystemId(),
                        ClinicalNotificationTexts.TITLE_APPOINTMENT_BOOKED, NotificationPriority.NORMAL,
                        BOOKED_PREFIX + event.appointmentId()),
                audience -> ClinicalNotificationTexts.appointmentBooked(audience, patient, doctor, event.scheduledAt()));
    }

    /** N2: only when the start time really changed. */
    @EventListener
    public void onRescheduled(AppointmentRescheduledEvent event) {
        if (event.appointmentId() == null || event.patientSystemId() == null || event.scheduledAt() == null
                || event.scheduledAt().equals(event.previousScheduledAt())) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        String dedupeKey = RESCHEDULED_PREFIX + event.appointmentId() + ":" + keyTime(event.previousScheduledAt())
                + ":" + keyTime(event.scheduledAt());
        notifier.publishEach(recipients(event.doctorSystemId(), event.patientSystemId()),
                template(event.appointmentId(), event.patientSystemId(), event.actorSystemId(),
                        ClinicalNotificationTexts.TITLE_APPOINTMENT_RESCHEDULED, NotificationPriority.NORMAL, dedupeKey),
                audience -> ClinicalNotificationTexts.appointmentRescheduled(audience, patient, doctor,
                        event.scheduledAt()));
    }

    /**
     * N3. A system cancellation (D5) is sent to everyone (the event's actor is ignored) at priority HIGH and shows
     * the server-written reason; a user cancellation never shows its reason (D4).
     */
    @EventListener
    public void onCancelled(AppointmentCancelledEvent event) {
        if (event.appointmentId() == null || event.patientSystemId() == null) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        String actor = event.bySystem() ? null : event.actorSystemId();
        NotificationPriority priority = event.bySystem() ? NotificationPriority.HIGH : NotificationPriority.NORMAL;
        String reason = event.bySystem() ? event.reason() : null;
        notifier.publishEach(recipients(event.doctorSystemId(), event.patientSystemId()),
                template(event.appointmentId(), event.patientSystemId(), actor,
                        ClinicalNotificationTexts.TITLE_APPOINTMENT_CANCELLED, priority,
                        CANCELLED_PREFIX + event.appointmentId()),
                audience -> ClinicalNotificationTexts.appointmentCancelled(audience, patient, doctor,
                        event.scheduledAt(), reason));
    }

    /** N4: NO_SHOW only; COMPLETED is covered by the consultation notification (D3). */
    @EventListener
    public void onStatusChanged(AppointmentStatusChangedEvent event) {
        if (event.appointmentId() == null || event.patientSystemId() == null
                || !NO_SHOW.equals(event.status()) || NO_SHOW.equals(event.previousStatus())) {
            return;
        }
        ClinicalNotifier.Names names = notifier.names(event.patientSystemId(), event.doctorSystemId());
        String patient = names.patient(event.patientSystemId());
        String doctor = names.doctor(event.doctorSystemId());
        notifier.publishEach(recipients(event.doctorSystemId(), event.patientSystemId()),
                template(event.appointmentId(), event.patientSystemId(), event.actorSystemId(),
                        ClinicalNotificationTexts.TITLE_APPOINTMENT_NO_SHOW, NotificationPriority.NORMAL,
                        NO_SHOW_PREFIX + event.appointmentId()),
                audience -> ClinicalNotificationTexts.appointmentNoShow(audience, patient, doctor));
    }

    private Map<String, Audience> recipients(String doctorSystemId, String patientSystemId) {
        return notifier.recipients(doctorSystemId, patientSystemId, true, PatientPermission.APPOINTMENTS);
    }

    /** Every field except recipient and message; the message is set per audience. */
    private static NotificationCommand template(Integer appointmentId, String patientSystemId, String actorSystemId,
                                                String title, NotificationPriority priority, String dedupeKey) {
        return NotificationCommand.builder()
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_CHANGE)
                .priority(priority)
                .title(title)
                .message(title)
                .target(NotificationTarget.APPOINTMENTS)
                .resourceType(RESOURCE_TYPE)
                .resourceId(appointmentId)
                .patientSystemId(patientSystemId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey)
                .build();
    }

    static String keyTime(LocalDateTime value) {
        return value == null ? "-" : KEY_TIME.format(value);
    }
}
