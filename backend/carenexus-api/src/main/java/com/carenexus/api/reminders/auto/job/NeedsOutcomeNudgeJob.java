package com.carenexus.api.reminders.auto.job;

import com.carenexus.api.common.job.JobSupport;
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
import com.carenexus.api.reminders.auto.service.ReminderText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@code needs-outcome-nudge} (about two minutes after startup, then every 24 hours): one notification to the
 * doctor of every appointment that is still {@code SCHEDULED} or {@code RESCHEDULED} {@value #NUDGE_AFTER_HOURS}
 * hours after its end ({@code scheduled_at + duration_minutes}).
 *
 * A run looks back {@value #LOOK_BACK_DAYS} days ({@code end + 24 h} in {@code (now - 7 days, now]}), in pages of
 * {@value #BATCH_SIZE} keyed on {@code appointment_id}, each page in its own transaction under the job's advisory
 * lock. There is no flag column: the per-doctor dedupe key {@code APPT_NEEDS_OUTCOME:<id>:<start>} makes the nudge
 * fire once per appointment and start time, so re-scanning the look-back every day is harmless. The 24 hours and 7
 * days are code constants, independent of the {@code appointments.needs-outcome-after-minutes} setting (which
 * drives the doctor's needs-outcome list). Category {@code APPOINTMENT_REMINDER}: the doctor's own
 * {@code appointmentReminders} toggle switches it off. The patient and caregivers are never notified.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NeedsOutcomeNudgeJob {

    public static final String JOB_NAME = "needs-outcome-nudge";
    static final int BATCH_SIZE = 200;
    static final int NUDGE_AFTER_HOURS = 24;
    static final int LOOK_BACK_DAYS = 7;
    static final String RESOURCE_TYPE = "APPOINTMENT";
    static final String NEEDS_OUTCOME_TAB = "needs-outcome";

    private final JobSupport jobs;
    private final ReminderJobsJdbc jdbc;
    private final ReminderPeople people;
    private final NotificationPublisher publisher;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.needs-outcome-nudge.interval-ms:86400000}",
            initialDelayString = "${carenexus.jobs.needs-outcome-nudge.initial-delay-ms:120000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            log.error("Needs-outcome nudge run failed: {}", e.getClass().getName());
        }
    }

    /** One run at the current clinic time. */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * One run at {@code clinicNow} (clinic wall-clock).
     *
     * @return notifications stored (0 on a repeat run, or when another instance holds the lock)
     */
    public int sweepOnce(LocalDateTime clinicNow) {
        // end + 24 h <= now  and  end + 24 h > now - 7 days
        LocalDateTime endNotAfter = clinicNow.minusHours(NUDGE_AFTER_HOURS);
        LocalDateTime endAfter = clinicNow.minusDays(LOOK_BACK_DAYS).minusHours(NUDGE_AFTER_HOURS);
        int stored = 0;
        int afterId = 0;
        while (true) {
            AtomicInteger pageStored = new AtomicInteger();
            AtomicInteger pageRows = new AtomicInteger();
            AtomicInteger lastId = new AtomicInteger(afterId);
            int from = afterId;
            boolean ran = jobs.runExclusive(JOB_NAME, () -> {
                List<ClaimedAppointment> page = jdbc.findWithoutOutcome(endAfter, endNotAfter, from, BATCH_SIZE);
                pageRows.set(page.size());
                if (page.isEmpty()) {
                    return;
                }
                List<String> everybody = new ArrayList<>();
                for (ClaimedAppointment appointment : page) {
                    everybody.add(appointment.patientSystemId());
                    everybody.add(appointment.doctorSystemId());
                }
                Directory directory = people.directory(everybody);
                for (ClaimedAppointment appointment : page) {
                    lastId.set(appointment.appointmentId());
                    if (appointment.doctorSystemId() == null || appointment.doctorSystemId().isBlank()) {
                        continue;
                    }
                    if (publisher.publish(command(appointment, directory)).isPresent()) {
                        pageStored.incrementAndGet();
                    }
                }
            });
            if (!ran) {
                break;
            }
            stored += pageStored.get();
            if (pageRows.get() < BATCH_SIZE || lastId.get() == from) {
                break;
            }
            afterId = lastId.get();
        }
        if (stored > 0) {
            log.info("Needs-outcome nudge run: {} notification(s) stored", stored);
        }
        return stored;
    }

    private NotificationCommand command(ClaimedAppointment appointment, Directory directory) {
        String doctor = appointment.doctorSystemId();
        return NotificationCommand.builder()
                .recipientSystemId(doctor)
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_REMINDER)
                .priority(NotificationPriority.NORMAL)
                .title(ReminderText.NEEDS_OUTCOME_TITLE)
                .message(ReminderText.needsOutcomeForDoctor(directory.fullName(appointment.patientSystemId()),
                        appointment.scheduledAt(), directory.clinic(), directory.zone(doctor)))
                .target(NotificationTarget.APPOINTMENTS)
                .param("tab", NEEDS_OUTCOME_TAB)
                .resourceType(RESOURCE_TYPE)
                .resourceId(appointment.appointmentId())
                .patientSystemId(appointment.patientSystemId())
                .dedupeKey(ReminderText.appointmentKey(ReminderText.NEEDS_OUTCOME_KEY, appointment.appointmentId(),
                        appointment.scheduledAt()))
                .build();
    }
}
