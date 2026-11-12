package com.carenexus.api.reminders.service;

import com.carenexus.api.reminders.model.Reminder;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Fixed English texts of the reminders module: source labels, the generic texts of automatic reminders and the time
 * line the dispatcher appends to every notification.
 */
public final class ReminderText {

    /** Fixed time of automatic reminders, clinic wall-clock. */
    public static final java.time.LocalTime AUTOMATIC_TIME = java.time.LocalTime.of(8, 0);

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter DAY_SHORT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    public static final String FOLLOW_UP_TITLE = "Follow-up visit due";
    public static final String RESULT_REVIEW_TITLE = "Result waiting for review";
    public static final String LAB_REVIEW_MESSAGE = "A lab result is waiting for your review.";
    public static final String IMAGING_REVIEW_MESSAGE = "An imaging result is waiting for your review.";
    public static final String CARE_PLAN_REVIEW_TITLE = "Care plan review due";

    private static final Map<String, String> SOURCE_LABELS = Map.of(
            Reminder.SOURCE_MANUAL, "Manual",
            Reminder.SOURCE_CONSULTATION, "Follow-up",
            Reminder.SOURCE_CARE_PLAN, "Care plan",
            Reminder.SOURCE_LAB_RESULT, "Lab result review",
            Reminder.SOURCE_IMAGING_RESULT, "Imaging result review");

    private ReminderText() {
    }

    public static String sourceLabel(String sourceType) {
        return SOURCE_LABELS.getOrDefault(sourceType, "Manual");
    }

    public static String followUpMessage(LocalDate followUpDate) {
        return "Your doctor asked to see you again on " + DAY.format(followUpDate)
                + ". Book a follow-up appointment if you have not already.";
    }

    public static String carePlanReviewMessageForPatient(LocalDate reviewDate) {
        return "Your care plan is due for review on " + DAY.format(reviewDate) + ".";
    }

    public static String carePlanReviewMessageForDoctor(LocalDate reviewDate) {
        return "A care plan you wrote is due for review on " + DAY.format(reviewDate) + ".";
    }

    /**
     * {@code Due Tue 1 Dec 2026 at 08:00 clinic time.}, plus the recipient's local time when it differs:
     * {@code (06:00 your time)}, or with the local day when the local date differs: {@code (Mon 30 Nov 22:00 your time)}.
     *
     * @param due           clinic wall-clock due time
     * @param clinicZone    the clinic zone
     * @param recipientZone the recipient's {@code user_settings.timezone} (null, blank or invalid = clinic zone)
     */
    public static String timeLine(LocalDateTime due, ZoneId clinicZone, String recipientZone) {
        StringBuilder line = new StringBuilder("Due ")
                .append(DAY.format(due))
                .append(" at ")
                .append(TIME.format(due))
                .append(" clinic time");
        ZoneId zone = zoneOrNull(recipientZone);
        if (zone != null) {
            LocalDateTime local = due.atZone(clinicZone).withZoneSameInstant(zone).toLocalDateTime();
            if (!local.equals(due)) {
                line.append(" (");
                if (!local.toLocalDate().equals(due.toLocalDate())) {
                    line.append(DAY_SHORT.format(local)).append(' ');
                }
                line.append(TIME.format(local)).append(" your time)");
            }
        }
        return line.append('.').toString();
    }

    /** A valid zone id, or null for null, blank or an id Java does not know. */
    static ZoneId zoneOrNull(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (DateTimeException invalid) {
            return null;
        }
    }
}
