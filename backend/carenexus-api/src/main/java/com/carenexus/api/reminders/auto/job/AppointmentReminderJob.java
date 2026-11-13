package com.carenexus.api.reminders.auto.job;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc.ClaimedAppointment;
import com.carenexus.api.reminders.auto.service.ReminderPeople;
import com.carenexus.api.reminders.auto.service.ReminderPeople.Directory;
import com.carenexus.api.reminders.auto.service.ReminderPeople.Recipient;
import com.carenexus.api.reminders.auto.service.ReminderText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@code appointment-reminders} (every 60 s by default): the two automatic appointment reminders to the patient
 * and every caregiver holding {@link PatientPermission#APPOINTMENTS}.
 *
 * Lead times are read on every sweep through {@link SystemSettingsService} only:
 * L = {@code reminders.appointment-lead-hours} (hours), S = {@code reminders.second-lead-minutes} (minutes).
 * One sweep, under the job's advisory lock and in one transaction:
 * <ol>
 *   <li>second reminder: active appointments with {@code now < start <= now + S} and {@code reminder_1h_sent}
 *       false are claimed (both flags set) and get R2 (category {@code APPOINTMENT_REMINDER_1H});</li>
 *   <li>first reminder: active appointments with {@code now + S < start <= now + L} and {@code reminder_sent} false
 *       are claimed and get R1 (category {@code APPOINTMENT_REMINDER}). With S &gt;= L this window is empty.</li>
 * </ol>
 * Each step claims at most {@value #BATCH_SIZE} rows (earliest start first); the rest follow on the next sweep.
 * Flags are set whether or not a notification was stored (a recipient may have switched the category off), and a
 * repeated claim stores nothing thanks to the per-recipient dedupe key, which includes the start time. The doctor
 * receives no automatic reminder. No audit row is written: no user acts and no data is returned.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentReminderJob {

    public static final String JOB_NAME = "appointment-reminders";
    static final int BATCH_SIZE = 200;
    static final String RESOURCE_TYPE = "APPOINTMENT";

    private final JobSupport jobs;
    private final ReminderJobsJdbc jdbc;
    private final ReminderPeople people;
    private final NotificationPublisher publisher;
    private final SystemSettingsService settings;
    private final Clock clock;

    /** Which of the two reminders a claim is for. */
    enum Kind {
        FIRST(NotificationCategory.APPOINTMENT_REMINDER, ReminderText.FIRST_REMINDER_TITLE,
                ReminderText.FIRST_REMINDER_KEY),
        SECOND(NotificationCategory.APPOINTMENT_REMINDER_1H, ReminderText.SECOND_REMINDER_TITLE,
                ReminderText.SECOND_REMINDER_KEY);

        final NotificationCategory category;
        final String title;
        final String keyPrefix;

        Kind(NotificationCategory category, String title, String keyPrefix) {
            this.category = category;
            this.title = title;
            this.keyPrefix = keyPrefix;
        }
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.appointment-reminders.interval-ms:60000}",
            initialDelayString = "${carenexus.jobs.appointment-reminders.initial-delay-ms:60000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Appointment reminder sweep failed: {}", e.getClass().getName());
        }
    }

    /** One sweep at the current clinic time. */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * One sweep at {@code clinicNow} (clinic wall-clock).
     *
     * @return claims won: second reminders plus first reminders (0 when another instance holds the lock)
     */
    public int sweepOnce(LocalDateTime clinicNow) {
        int leadHours = settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS);
        int secondLeadMinutes = settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES);
        LocalDateTime secondWindowEnd = clinicNow.plusMinutes(secondLeadMinutes);
        LocalDateTime firstWindowEnd = clinicNow.plusHours(leadHours);

        AtomicInteger seconds = new AtomicInteger();
        AtomicInteger firsts = new AtomicInteger();
        boolean ran = jobs.runExclusive(JOB_NAME, () -> {
            List<ClaimedAppointment> second = jdbc.claimSecondReminders(clinicNow, secondWindowEnd, BATCH_SIZE);
            remindAll(second, Kind.SECOND);
            seconds.set(second.size());

            List<ClaimedAppointment> first = jdbc.claimFirstReminders(secondWindowEnd, firstWindowEnd, BATCH_SIZE);
            remindAll(first, Kind.FIRST);
            firsts.set(first.size());
        });
        if (!ran) {
            return 0;
        }
        if (seconds.get() > 0 || firsts.get() > 0) {
            log.info("Appointment reminder sweep: {} first and {} second reminder(s) claimed",
                    firsts.get(), seconds.get());
        }
        return seconds.get() + firsts.get();
    }

    private void remindAll(List<ClaimedAppointment> claimed, Kind kind) {
        if (claimed.isEmpty()) {
            return;
        }
        Map<ClaimedAppointment, List<Recipient>> circles = new LinkedHashMap<>();
        List<String> everybody = new ArrayList<>();
        // One grant query for the whole batch (not one per appointment)
        Map<String, List<Recipient>> circleByPatient = people.patientCircles(
                claimed.stream().map(ClaimedAppointment::patientSystemId).toList(), PatientPermission.APPOINTMENTS);
        for (ClaimedAppointment appointment : claimed) {
            List<Recipient> circle = circleByPatient.getOrDefault(appointment.patientSystemId(), List.of());
            circles.put(appointment, circle);
            everybody.add(appointment.patientSystemId());
            everybody.add(appointment.doctorSystemId());
            circle.forEach(recipient -> everybody.add(recipient.systemId()));
        }
        Directory directory = people.directory(everybody);
        circles.forEach((appointment, circle) -> {
            for (Recipient recipient : circle) {
                publisher.publish(command(appointment, recipient, kind, directory));
            }
        });
    }

    private NotificationCommand command(ClaimedAppointment appointment, Recipient recipient, Kind kind,
                                        Directory directory) {
        ZoneId clinic = directory.clinic();
        ZoneId zone = directory.zone(recipient.systemId());
        String doctorName = directory.fullName(appointment.doctorSystemId());
        String message = recipient.patient()
                ? ReminderText.appointmentForPatient(doctorName, appointment.scheduledAt(), clinic, zone)
                : ReminderText.appointmentForCaregiver(directory.fullName(appointment.patientSystemId()), doctorName,
                        appointment.scheduledAt(), clinic, zone);
        return NotificationCommand.builder()
                .recipientSystemId(recipient.systemId())
                .type(NotificationType.APPOINTMENT)
                .category(kind.category)
                .priority(NotificationPriority.NORMAL)
                .title(kind.title)
                .message(message)
                .target(NotificationTarget.APPOINTMENTS)
                .resourceType(RESOURCE_TYPE)
                .resourceId(appointment.appointmentId())
                .patientSystemId(appointment.patientSystemId())
                .dedupeKey(ReminderText.appointmentKey(kind.keyPrefix, appointment.appointmentId(),
                        appointment.scheduledAt()))
                .expiresAt(appointment.endsAt())
                .build();
    }
}
