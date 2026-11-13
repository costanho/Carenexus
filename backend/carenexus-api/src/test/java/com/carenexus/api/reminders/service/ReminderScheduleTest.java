package com.carenexus.api.reminders.service;

import com.carenexus.api.reminders.model.Reminder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Occurrence rules (contract 3.5) and the dispatcher's time line (contract 5.2). Pure functions, no Spring. */
class ReminderScheduleTest {

    private static final LocalTime SEVEN_THIRTY = LocalTime.of(7, 30);
    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

    private static ReminderSchedule schedule(String frequency, LocalDate start, LocalDate end) {
        return new ReminderSchedule(frequency, start, end, SEVEN_THIRTY);
    }

    @Test
    void once_hasOneOccurrence_andIgnoresTheEndDate() {
        ReminderSchedule once = schedule(Reminder.FREQUENCY_ONCE, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 1));
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 11, 7, 30)),
                once.firstAtOrAfter(LocalDateTime.of(2026, 11, 10, 12, 0)));
        // At exactly the due time it is still upcoming; one second later it is gone
        assertTrue(once.firstAtOrAfter(LocalDateTime.of(2026, 11, 11, 7, 30)).isPresent());
        assertFalse(once.hasOccurrenceAtOrAfter(LocalDateTime.of(2026, 11, 11, 7, 30, 1)));
        assertEquals(Optional.empty(), once.nextAfter(LocalDateTime.of(2026, 11, 11, 7, 30),
                LocalDateTime.of(2026, 11, 11, 7, 31)));
    }

    @Test
    void daily_advancesOneDay_stopsAfterTheEndDate_andDoesNotReplayMissedDays() {
        ReminderSchedule daily = schedule(Reminder.FREQUENCY_DAILY, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 1, 7, 30)),
                daily.firstAtOrAfter(LocalDateTime.of(2026, 10, 1, 0, 0)));
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 11, 7, 30)),
                daily.firstAtOrAfter(LocalDateTime.of(2026, 11, 10, 7, 31)));
        // sent at 10 Nov 07:30 and processed at once: the next one is the following day
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 11, 7, 30)),
                daily.nextAfter(LocalDateTime.of(2026, 11, 10, 7, 30), LocalDateTime.of(2026, 11, 10, 7, 30)));
        // the job was down for three days: the missed occurrences are skipped
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 14, 7, 30)),
                daily.nextAfter(LocalDateTime.of(2026, 11, 10, 7, 30), LocalDateTime.of(2026, 11, 13, 9, 0)));
        assertEquals(Optional.empty(),
                daily.nextAfter(LocalDateTime.of(2026, 11, 20, 7, 30), LocalDateTime.of(2026, 11, 20, 7, 30)));
    }

    @Test
    void weekly_isCountedFromTheStartDate() {
        ReminderSchedule weekly = schedule(Reminder.FREQUENCY_WEEKLY, LocalDate.of(2026, 11, 2), null);
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 16, 7, 30)),
                weekly.firstAtOrAfter(LocalDateTime.of(2026, 11, 9, 7, 31)));
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 9, 7, 30)),
                weekly.firstAtOrAfter(LocalDateTime.of(2026, 11, 3, 0, 0)));
    }

    @Test
    void monthly_onThe31st_usesTheMonthsLastDay_andReturnsToThe31st() {
        ReminderSchedule monthly = schedule(Reminder.FREQUENCY_MONTHLY, LocalDate.of(2027, 1, 31), null);
        assertEquals(Optional.of(LocalDateTime.of(2027, 2, 28, 7, 30)),
                monthly.firstAtOrAfter(LocalDateTime.of(2027, 2, 1, 0, 0)));
        assertEquals(Optional.of(LocalDateTime.of(2027, 3, 31, 7, 30)),
                monthly.nextAfter(LocalDateTime.of(2027, 2, 28, 7, 30), LocalDateTime.of(2027, 2, 28, 7, 30)));
    }

    @Test
    void customFrequency_hasNoOccurrence() {
        assertFalse(new ReminderSchedule("CUSTOM", LocalDate.of(2026, 11, 1), null, SEVEN_THIRTY)
                .hasOccurrenceAtOrAfter(LocalDateTime.of(2026, 10, 1, 0, 0)));
    }

    @Test
    void timeLine_clinicTimeOnly_whenNoOrTheSameOrAnInvalidZone() {
        LocalDateTime due = LocalDateTime.of(2026, 12, 1, 8, 0);
        String clinicOnly = "Due Tue 1 Dec 2026 at 08:00 clinic time.";
        assertEquals(clinicOnly, ReminderText.timeLine(due, HARARE, null));
        assertEquals(clinicOnly, ReminderText.timeLine(due, HARARE, " "));
        assertEquals(clinicOnly, ReminderText.timeLine(due, HARARE, "Not/AZone"));
        assertEquals(clinicOnly, ReminderText.timeLine(due, HARARE, "Africa/Harare"));
        // Another zone with the same offset gives the same local time: no second time
        assertEquals(clinicOnly, ReminderText.timeLine(due, HARARE, "Africa/Johannesburg"));
    }

    @Test
    void timeLine_addsTheRecipientsTime_andTheDayWhenItDiffers() {
        assertEquals("Due Tue 1 Dec 2026 at 08:00 clinic time (06:00 your time).",
                ReminderText.timeLine(LocalDateTime.of(2026, 12, 1, 8, 0), HARARE, "Europe/London"));
        assertEquals("Due Tue 1 Dec 2026 at 01:00 clinic time (Mon 30 Nov 23:00 your time).",
                ReminderText.timeLine(LocalDateTime.of(2026, 12, 1, 1, 0), HARARE, "Europe/London"));
        assertEquals("Due Tue 1 Dec 2026 at 08:00 clinic time (10:00 your time).",
                ReminderText.timeLine(LocalDateTime.of(2026, 12, 1, 8, 0), HARARE, "Asia/Dubai"));
    }
}
