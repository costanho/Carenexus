package com.carenexus.api.reminders.listener;

import com.carenexus.api.common.event.CarePlanChangedEvent;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderJdbc;
import com.carenexus.api.reminders.repository.ReminderJdbc.AutomaticReminder;
import com.carenexus.api.reminders.service.ReminderText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Automatic reminders created, moved and removed by clinical events. Plain {@link EventListener}s: they run inside
 * the publisher's transaction, so a reminder exists exactly when the change that asked for it commits. Every write
 * is an upsert on the V54 partial unique index ({@code ON CONFLICT}), so a repeated event changes nothing and never
 * aborts the publisher's transaction. A listener never throws for missing data: an event without the ids it needs
 * is skipped.
 *
 * Automatic reminders fire at {@link ReminderText#AUTOMATIC_TIME} (08:00) clinic time:
 * <ul>
 *   <li>{@link ConsultationCompletedEvent} with a follow-up date: one CHECK_IN reminder for the patient, ONCE, on
 *       the first day from {@value #FOLLOW_UP_LEAD_DAYS} days before the follow-up date up to that date whose 08:00
 *       is still ahead (none when the follow-up is today and 08:00 has passed);</li>
 *   <li>{@link ClinicalResultRecordedEvent} with status REQUIRES_REVIEW: one LAB_FOLLOWUP reminder for the ordering
 *       doctor, DAILY for {@value #REVIEW_DAYS} days from the first 08:00 still ahead (an existing one is kept); any
 *       other status deletes the result's review reminder;</li>
 *   <li>{@link CarePlanChangedEvent} with a review date on an ACTIVE or ON_HOLD plan: one CARE_PLAN reminder for the
 *       patient and one for the authoring doctor, ONCE on the review date; a moved date moves and re-arms both. No
 *       review date, or a COMPLETED or DISCONTINUED plan, deletes both.</li>
 * </ul>
 * Automatic text is generic: no result value, diagnosis, plan title or name.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReminderEventListener {

    /** The follow-up reminder may fire up to this many days before the follow-up date. */
    static final int FOLLOW_UP_LEAD_DAYS = 3;
    /** A result waiting for review is reminded daily for at most this many days. */
    static final int REVIEW_DAYS = 7;

    static final String REQUIRES_REVIEW = "REQUIRES_REVIEW";
    private static final Set<String> CARE_PLAN_OPEN = Set.of("ACTIVE", "ON_HOLD");

    private final ReminderJdbc reminderJdbc;
    private final Clock clock;

    @EventListener
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        if (event.consultationId() == null || isBlank(event.patientSystemId()) || event.followUpDate() == null) {
            return;
        }
        LocalDateTime now = now();
        LocalDate followUp = event.followUpDate();
        LocalDate day = firstDayStillAhead(now);
        LocalDate earliest = followUp.minusDays(FOLLOW_UP_LEAD_DAYS);
        if (day.isBefore(earliest)) {
            day = earliest;
        }
        if (day.isAfter(followUp)) {
            // The follow-up is today after 08:00, or already past: nothing left to remind about
            return;
        }
        String patient = event.patientSystemId().trim();
        reminderJdbc.upsertMovingStartDate(new AutomaticReminder(patient, patient, Reminder.TYPE_CHECK_IN,
                ReminderText.FOLLOW_UP_TITLE, ReminderText.followUpMessage(followUp), ReminderText.AUTOMATIC_TIME,
                Reminder.FREQUENCY_ONCE, day, null, day.atTime(ReminderText.AUTOMATIC_TIME),
                Reminder.SOURCE_CONSULTATION, event.consultationId()), now);
    }

    @EventListener
    public void onClinicalResultRecorded(ClinicalResultRecordedEvent event) {
        String sourceType = resultSource(event.kind());
        if (sourceType == null || event.resultId() == null) {
            return;
        }
        String status = event.status() == null ? "" : event.status().trim().toUpperCase(Locale.ROOT);
        if (!REQUIRES_REVIEW.equals(status)) {
            reminderJdbc.deleteBySource(sourceType, event.resultId());
            return;
        }
        if (isBlank(event.doctorSystemId()) || isBlank(event.patientSystemId())) {
            return;
        }
        LocalDateTime now = now();
        LocalDate start = firstDayStillAhead(now);
        String message = Reminder.SOURCE_LAB_RESULT.equals(sourceType)
                ? ReminderText.LAB_REVIEW_MESSAGE : ReminderText.IMAGING_REVIEW_MESSAGE;
        reminderJdbc.insertIfAbsent(new AutomaticReminder(event.patientSystemId().trim(),
                event.doctorSystemId().trim(), Reminder.TYPE_LAB_FOLLOWUP, ReminderText.RESULT_REVIEW_TITLE, message,
                ReminderText.AUTOMATIC_TIME, Reminder.FREQUENCY_DAILY, start, start.plusDays(REVIEW_DAYS - 1),
                start.atTime(ReminderText.AUTOMATIC_TIME), sourceType, event.resultId()), now);
    }

    @EventListener
    public void onCarePlanChanged(CarePlanChangedEvent event) {
        if (event.planId() == null) {
            return;
        }
        String status = event.status() == null ? "" : event.status().trim().toUpperCase(Locale.ROOT);
        LocalDate reviewDate = event.reviewDate();
        if (reviewDate == null || !CARE_PLAN_OPEN.contains(status)) {
            // No review date, or a terminal (or unknown) status: the reminders have no purpose any more
            reminderJdbc.deleteBySource(Reminder.SOURCE_CARE_PLAN, event.planId());
            return;
        }
        if (isBlank(event.patientSystemId())) {
            return;
        }
        LocalDateTime now = now();
        String patient = event.patientSystemId().trim();
        String doctor = isBlank(event.doctorSystemId()) ? null : event.doctorSystemId().trim();
        LocalDateTime dueAt = reviewDate.atTime(ReminderText.AUTOMATIC_TIME);
        // A review moment already past is stored inactive (nothing to send); a later move re-arms it
        LocalDateTime nextDueAt = dueAt.isAfter(now) ? dueAt : null;

        List<String> recipients = new ArrayList<>();
        recipients.add(patient);
        reminderJdbc.upsertMovingStartDate(carePlanReminder(event.planId(), patient, patient, reviewDate,
                ReminderText.carePlanReviewMessageForPatient(reviewDate), nextDueAt), now);
        if (doctor != null && !doctor.equals(patient)) {
            recipients.add(doctor);
            reminderJdbc.upsertMovingStartDate(carePlanReminder(event.planId(), patient, doctor, reviewDate,
                    ReminderText.carePlanReviewMessageForDoctor(reviewDate), nextDueAt), now);
        }
        // A plan has one author; rows of any other recipient are stale
        reminderJdbc.deleteBySourceExceptRecipients(Reminder.SOURCE_CARE_PLAN, event.planId(), recipients);
    }

    private static AutomaticReminder carePlanReminder(Integer planId, String patient, String recipient,
                                                      LocalDate reviewDate, String message, LocalDateTime nextDueAt) {
        return new AutomaticReminder(patient, recipient, Reminder.TYPE_CARE_PLAN, ReminderText.CARE_PLAN_REVIEW_TITLE,
                message, ReminderText.AUTOMATIC_TIME, Reminder.FREQUENCY_ONCE, reviewDate, null, nextDueAt,
                Reminder.SOURCE_CARE_PLAN, planId);
    }

    /** Today when today's 08:00 is still ahead, otherwise tomorrow. */
    private static LocalDate firstDayStillAhead(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        return today.atTime(ReminderText.AUTOMATIC_TIME).isAfter(now) ? today : today.plusDays(1);
    }

    private static String resultSource(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind.trim().toUpperCase(Locale.ROOT)) {
            case ClinicalResultRecordedEvent.LAB -> Reminder.SOURCE_LAB_RESULT;
            case ClinicalResultRecordedEvent.IMAGING -> Reminder.SOURCE_IMAGING_RESULT;
            default -> null;
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
