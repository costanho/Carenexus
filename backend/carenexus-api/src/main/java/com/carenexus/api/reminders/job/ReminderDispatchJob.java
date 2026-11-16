package com.carenexus.api.reminders.job;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderJdbc;
import com.carenexus.api.reminders.repository.ReminderRepository;
import com.carenexus.api.reminders.service.ReminderSchedule;
import com.carenexus.api.reminders.service.ReminderText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@value #JOB_NAME} (every 60 s by default, {@code carenexus.jobs.reminders.interval-ms}; one instance at a
 * time through {@link JobSupport#runExclusive}): every active reminder whose {@code next_due_at} has come produces
 * one notification to its recipient, then {@code last_sent_at} is stamped and {@code next_due_at} advances to the
 * next occurrence at or after now, or the reminder ends ({@code is_active = false}, {@code next_due_at = null})
 * when no occurrence is left (ONCE, or the next one falls after the end date). Occurrences missed while the job was
 * down are not replayed.
 *
 * The row is advanced whether or not the publisher stored a notification (preference switched off, duplicate), and
 * the dedupe key {@code REMINDER:<id>:<due yyyy-MM-ddTHH:mm>} makes sure one due time never notifies twice, even
 * after a crash between the publish and the commit of a batch.
 *
 * Rows are claimed in batches of at most {@value #BATCH_SIZE} with {@code FOR UPDATE SKIP LOCKED}, each batch in its
 * own transaction; a PATCH on the same row waits for the batch or is skipped by it.
 */
@Component
@Slf4j
public class ReminderDispatchJob {

    public static final String JOB_NAME = "reminders";
    public static final int BATCH_SIZE = 200;
    /** Upper bound of batches per run; the next run continues with what is left. */
    static final int MAX_BATCHES = 50;

    static final String DEDUPE_PREFIX = "REMINDER:";
    private static final DateTimeFormatter DUE_KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final String PARAM_TAB = "tab";
    private static final String TAB_REMINDERS = "reminders";

    private final JobSupport jobs;
    private final ReminderRepository reminderRepository;
    private final ReminderJdbc reminderJdbc;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    @Autowired
    public ReminderDispatchJob(JobSupport jobs, ReminderRepository reminderRepository, ReminderJdbc reminderJdbc,
                               NotificationPublisher notificationPublisher, Clock clock) {
        this.jobs = jobs;
        this.reminderRepository = reminderRepository;
        this.reminderJdbc = reminderJdbc;
        this.notificationPublisher = notificationPublisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.reminders.interval-ms:60000}",
            initialDelayString = "${carenexus.jobs.reminders.initial-delay-ms:30000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Reminder dispatch failed: {}", e.getClass().getName());
        }
    }

    /**
     * One run: dispatches every reminder due at the current clinic time.
     *
     * @return the number of reminders processed (advanced or ended), whether or not a notification was stored
     */
    public int sweepOnce() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            AtomicInteger processed = new AtomicInteger();
            AtomicInteger claimed = new AtomicInteger();
            boolean ran = jobs.runExclusive(JOB_NAME, () -> {
                List<Reminder> due = reminderRepository.claimDue(now, BATCH_SIZE);
                claimed.set(due.size());
                processed.set(dispatch(due, now));
            });
            if (!ran) {
                break;
            }
            total += processed.get();
            if (claimed.get() < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Reminder dispatch: {} reminder(s) processed", total);
        }
        return total;
    }

    private int dispatch(List<Reminder> due, LocalDateTime now) {
        if (due.isEmpty()) {
            return 0;
        }
        Set<String> recipients = new LinkedHashSet<>();
        due.forEach(reminder -> recipients.add(reminder.getRecipientSystemId()));
        Map<String, String> zones = reminderJdbc.timezones(recipients);
        ZoneId clinicZone = clock.getZone();
        for (Reminder reminder : due) {
            LocalDateTime dueAt = reminder.getNextDueAt();
            try {
                notificationPublisher.publish(command(reminder, dueAt, clinicZone,
                        zones.get(reminder.getRecipientSystemId())));
            } catch (IllegalArgumentException invalid) {
                // Raised by the publisher's own checks before anything is written: the row is still advanced so a
                // malformed legacy row cannot block the queue on every run.
                log.error("Reminder {} could not be published: {}", reminder.getReminderId(), invalid.getMessage());
            }
            advance(reminder, dueAt, now);
        }
        reminderRepository.saveAll(due);
        return due.size();
    }

    /** Stamps the run and moves to the next occurrence after the one just sent, or ends the reminder. */
    static void advance(Reminder reminder, LocalDateTime dueAt, LocalDateTime now) {
        reminder.setLastSentAt(now);
        Optional<LocalDateTime> next = ReminderSchedule.of(reminder).nextAfter(dueAt, now);
        if (next.isPresent()) {
            reminder.setNextDueAt(next.get());
        } else {
            reminder.setIsActive(false);
            reminder.setNextDueAt(null);
        }
    }

    static NotificationCommand command(Reminder reminder, LocalDateTime dueAt, ZoneId clinicZone,
                                       String recipientZone) {
        NotificationCommand.Builder builder = NotificationCommand.builder()
                .recipientSystemId(reminder.getRecipientSystemId())
                .type(notificationType(reminder.getType()))
                .category(category(reminder.getType()))
                .title(reminder.getTitle())
                .message(reminder.getMessage() + " " + ReminderText.timeLine(dueAt, clinicZone, recipientZone))
                .patientSystemId(reminder.getPatientSystemId())
                .dedupeKey(dedupeKey(reminder.getReminderId(), dueAt));
        switch (reminder.getSourceType()) {
            case Reminder.SOURCE_CONSULTATION -> builder.target(NotificationTarget.CONSULTATIONS)
                    .resourceType("CONSULTATION").resourceId(reminder.getSourceId());
            case Reminder.SOURCE_CARE_PLAN -> builder.target(NotificationTarget.CARE_PLANS)
                    .resourceType("CARE_PLAN").resourceId(reminder.getSourceId());
            case Reminder.SOURCE_LAB_RESULT -> builder.target(NotificationTarget.LAB_RESULTS)
                    .resourceType("LAB_RESULT").resourceId(reminder.getSourceId());
            case Reminder.SOURCE_IMAGING_RESULT -> builder.target(NotificationTarget.IMAGING_RESULTS)
                    .resourceType("IMAGING_RESULT").resourceId(reminder.getSourceId());
            // MANUAL: the patient's reminders tab; deliberately no id, so it is never read as a prescription id
            default -> builder.target(NotificationTarget.PRESCRIPTIONS)
                    .params(Map.of(PARAM_TAB, TAB_REMINDERS))
                    .resourceType("REMINDER");
        }
        return builder.build();
    }

    /** {@code REMINDER:<reminderId>:<due yyyy-MM-ddTHH:mm>}: one notification per reminder and due time. */
    public static String dedupeKey(Integer reminderId, LocalDateTime dueAt) {
        return DEDUPE_PREFIX + reminderId + ":" + DUE_KEY.format(dueAt);
    }

    static NotificationType notificationType(String reminderType) {
        return switch (reminderType) {
            case Reminder.TYPE_MEDICATION -> NotificationType.MEDICATION;
            case Reminder.TYPE_APPOINTMENT -> NotificationType.APPOINTMENT;
            case Reminder.TYPE_LAB_FOLLOWUP -> NotificationType.LAB_RESULT;
            default -> NotificationType.SYSTEM;
        };
    }

    static NotificationCategory category(String reminderType) {
        return switch (reminderType) {
            case Reminder.TYPE_MEDICATION -> NotificationCategory.MEDICATION_REMINDER;
            case Reminder.TYPE_APPOINTMENT -> NotificationCategory.APPOINTMENT_REMINDER;
            case Reminder.TYPE_LAB_FOLLOWUP -> NotificationCategory.LAB_RESULT;
            case Reminder.TYPE_CARE_PLAN -> NotificationCategory.CARE_PLAN;
            default -> NotificationCategory.SYSTEM;
        };
    }
}
