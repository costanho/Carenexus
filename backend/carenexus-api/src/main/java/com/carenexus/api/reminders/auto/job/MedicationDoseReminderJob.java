package com.carenexus.api.reminders.auto.job;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc.ClaimedDose;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@code medication-reminders} (every 60 s by default): one reminder per due dose row of
 * {@code medication_schedules} to the patient and every caregiver holding {@link PatientPermission#MEDICATIONS}.
 *
 * A dose is due when it is neither taken nor missed, was not reminded yet ({@code reminder_sent_at} null) and its
 * time {@code schedule_date + scheduled_time} (clinic wall-clock) lies in {@code (now - 60 minutes, now]}. Each
 * sweep claims at most {@value #BATCH_SIZE} rows by stamping {@code reminder_sent_at}; the stamp is set whether or
 * not a notification was stored and never cleared. Because reminders follow the dose rows, they always match the
 * schedule be-medication materialises: an edited schedule deletes and re-inserts future rows, and the new rows are
 * reminded at their own times. A dose whose time passed more than 60 minutes ago (the API was down) is not
 * reminded late. No audit row is written.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MedicationDoseReminderJob {

    public static final String JOB_NAME = "medication-reminders";
    static final int BATCH_SIZE = 200;
    /** A dose is reminded only within this many minutes after its time. */
    static final int DUE_WINDOW_MINUTES = 60;
    /** R3 disappears from the list this long after the dose time. */
    static final int EXPIRES_AFTER_HOURS = 24;
    static final String RESOURCE_TYPE = "MEDICATION_SCHEDULE";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JobSupport jobs;
    private final ReminderJobsJdbc jdbc;
    private final ReminderPeople people;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.medication-reminders.interval-ms:60000}",
            initialDelayString = "${carenexus.jobs.medication-reminders.initial-delay-ms:60000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Medication reminder sweep failed: {}", e.getClass().getName());
        }
    }

    /** One sweep at the current clinic time. */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * One sweep at {@code clinicNow} (clinic wall-clock).
     *
     * @return dose rows claimed (0 when another instance holds the lock)
     */
    public int sweepOnce(LocalDateTime clinicNow) {
        LocalDateTime from = clinicNow.minusMinutes(DUE_WINDOW_MINUTES);
        AtomicInteger claimed = new AtomicInteger();
        boolean ran = jobs.runExclusive(JOB_NAME, () -> {
            List<ClaimedDose> doses = jdbc.claimDueDoses(from, clinicNow, BATCH_SIZE);
            remindAll(doses);
            claimed.set(doses.size());
        });
        if (!ran) {
            return 0;
        }
        if (claimed.get() > 0) {
            log.info("Medication reminder sweep: {} dose(s) claimed", claimed.get());
        }
        return claimed.get();
    }

    private void remindAll(List<ClaimedDose> doses) {
        if (doses.isEmpty()) {
            return;
        }
        Map<ClaimedDose, List<Recipient>> circles = new LinkedHashMap<>();
        List<String> everybody = new ArrayList<>();
        // One grant query for the whole batch; several doses of one patient share the patient's circle
        Map<String, List<Recipient>> circleByPatient = people.patientCircles(
                doses.stream().map(ClaimedDose::patientSystemId).toList(), PatientPermission.MEDICATIONS);
        for (ClaimedDose dose : doses) {
            List<Recipient> circle = circleByPatient.getOrDefault(dose.patientSystemId(), List.of());
            circles.put(dose, circle);
            everybody.add(dose.patientSystemId());
            circle.forEach(recipient -> everybody.add(recipient.systemId()));
        }
        Directory directory = people.directory(everybody);
        circles.forEach((dose, circle) -> {
            for (Recipient recipient : circle) {
                publisher.publish(command(dose, recipient, directory));
            }
        });
    }

    private NotificationCommand command(ClaimedDose dose, Recipient recipient, Directory directory) {
        ZoneId clinic = directory.clinic();
        ZoneId zone = directory.zone(recipient.systemId());
        LocalDateTime due = dose.dueAt();
        String message = recipient.patient()
                ? ReminderText.doseForPatient(dose.medicationName(), due, clinic, zone)
                : ReminderText.doseForCaregiver(directory.fullName(dose.patientSystemId()), dose.medicationName(),
                        due, clinic, zone);
        return NotificationCommand.builder()
                .recipientSystemId(recipient.systemId())
                .type(NotificationType.MEDICATION)
                .category(NotificationCategory.MEDICATION_REMINDER)
                .priority(NotificationPriority.NORMAL)
                .title(ReminderText.DOSE_REMINDER_TITLE)
                .message(message)
                .target(NotificationTarget.MEDICATION_SCHEDULE)
                .param("date", dose.scheduleDate().format(DATE))
                // No resourceId: the patient prescriptions section reads "id" as a prescription id
                .resourceType(RESOURCE_TYPE)
                .patientSystemId(dose.patientSystemId())
                .dedupeKey(ReminderText.doseKey(dose.scheduleId()))
                .expiresAt(due.plusHours(EXPIRES_AFTER_HOURS))
                .build();
    }
}
