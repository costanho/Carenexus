package com.carenexus.api.reminders;

import com.carenexus.api.reminders.model.Reminder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test data for the reminders module. */
public final class ReminderFixtures {

    /** The clinic zone of the test profile (carenexus.scheduling.time-zone default). */
    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private ReminderFixtures() {
    }

    /** A clock fixed at {@code at} clinic wall-clock time. */
    public static Clock clockAt(LocalDateTime at) {
        return Clock.fixed(at.atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
    }

    /** Today in the clinic zone (real clock). */
    public static LocalDate clinicToday() {
        return LocalDate.now(CLINIC_ZONE);
    }

    /** An active manual reminder addressed to its patient, due at {@code nextDueAt}. */
    public static Reminder manual(String patientSystemId, String createdBy, String type, String frequency,
                                  LocalDate startDate, LocalDate endDate, LocalTime time, LocalDateTime nextDueAt) {
        return Reminder.builder()
                .patientSystemId(patientSystemId)
                .recipientSystemId(patientSystemId)
                .createdBySystemId(createdBy)
                .type(type)
                .title("Morning blood pressure")
                .message("Measure your blood pressure before breakfast.")
                .reminderTime(time)
                .frequency(frequency)
                .startDate(startDate)
                .endDate(endDate)
                .isActive(nextDueAt != null)
                .nextDueAt(nextDueAt)
                .sourceType(Reminder.SOURCE_MANUAL)
                .createdAt(LocalDateTime.now(CLINIC_ZONE))
                .build();
    }

    /** An active automatic reminder from {@code sourceType}/{@code sourceId}, addressed to {@code recipient}. */
    public static Reminder automatic(String patientSystemId, String recipient, String type, String sourceType,
                                     Integer sourceId, LocalDate startDate, LocalDateTime nextDueAt) {
        return Reminder.builder()
                .patientSystemId(patientSystemId)
                .recipientSystemId(recipient)
                .type(type)
                .title("Automatic reminder")
                .message("Something is due.")
                .reminderTime(LocalTime.of(8, 0))
                .frequency(Reminder.FREQUENCY_ONCE)
                .startDate(startDate)
                .isActive(nextDueAt != null)
                .nextDueAt(nextDueAt)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .createdAt(LocalDateTime.now(CLINIC_ZONE))
                .build();
    }

    /** A valid create body; tweak entries with {@link Map#put}. */
    public static Map<String, Object> createBody(String patientSystemId) {
        LocalDate start = clinicToday().plusDays(1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("type", "VITAL_SIGN");
        body.put("title", "Morning blood pressure");
        body.put("message", "Measure your blood pressure before breakfast and write it down.");
        body.put("reminderTime", "07:30");
        body.put("frequency", "DAILY");
        body.put("startDate", start.toString());
        body.put("endDate", start.plusDays(30).toString());
        return body;
    }
}
