package com.carenexus.api.reminders.service;

import com.carenexus.api.reminders.model.Reminder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Occurrences of a reminder (clinic wall-clock; pure functions, no clock of its own).
 *
 * Occurrence k (k = 0, 1, 2, ...) is at {@code time} on: {@code start} (ONCE, k = 0 only); {@code start + k days}
 * (DAILY); {@code start + 7k days} (WEEKLY); {@code start.plusMonths(k)} (MONTHLY, always counted from the start, so
 * the 31st becomes the month's last day and returns to the 31st). An occurrence whose date is after {@code end} does
 * not exist ({@code end} is ignored for ONCE). Any other frequency (the legacy CUSTOM) has no occurrence.
 */
public record ReminderSchedule(String frequency, LocalDate startDate, LocalDate endDate, LocalTime time) {

    public static ReminderSchedule of(Reminder reminder) {
        return new ReminderSchedule(reminder.getFrequency(), reminder.getStartDate(), reminder.getEndDate(),
                reminder.getReminderTime());
    }

    /** The first occurrence at or after {@code from}, or empty when none is left. */
    public Optional<LocalDateTime> firstAtOrAfter(LocalDateTime from) {
        if (frequency == null || startDate == null || time == null || from == null) {
            return Optional.empty();
        }
        if (Reminder.FREQUENCY_ONCE.equals(frequency)) {
            LocalDateTime only = startDate.atTime(time);
            return only.isBefore(from) ? Optional.empty() : Optional.of(only);
        }
        long k = estimateIndex(from.toLocalDate());
        if (k < 0) {
            return Optional.empty();
        }
        // The estimate is never past the answer; at most a few steps forward reach it.
        for (int guard = 0; guard < 400; guard++, k++) {
            LocalDate date = occurrenceDate(k);
            if (endDate != null && date.isAfter(endDate)) {
                return Optional.empty();
            }
            LocalDateTime candidate = date.atTime(time);
            if (!candidate.isBefore(from)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The occurrence after the one due at {@code sentDue} that is also at or after {@code now} (occurrences missed
     * while nothing dispatched are not replayed), or empty when the reminder has ended.
     */
    public Optional<LocalDateTime> nextAfter(LocalDateTime sentDue, LocalDateTime now) {
        LocalDateTime after = sentDue.plusNanos(1);
        return firstAtOrAfter(after.isAfter(now) ? after : now);
    }

    /** True when an occurrence exists at or after {@code now} (a paused reminder is PAUSED, otherwise ENDED). */
    public boolean hasOccurrenceAtOrAfter(LocalDateTime now) {
        return firstAtOrAfter(now).isPresent();
    }

    private LocalDate occurrenceDate(long k) {
        return switch (frequency) {
            case Reminder.FREQUENCY_DAILY -> startDate.plusDays(k);
            case Reminder.FREQUENCY_WEEKLY -> startDate.plusDays(7 * k);
            case Reminder.FREQUENCY_MONTHLY -> startDate.plusMonths(k);
            default -> throw new IllegalStateException("No occurrences for frequency " + frequency);
        };
    }

    /**
     * An index whose occurrence date is on or before {@code day} (or 0 when {@code day} is before the start), so the
     * forward scan starts close to the answer. -1 when the frequency has no occurrences.
     */
    private long estimateIndex(LocalDate day) {
        long k;
        switch (frequency) {
            case Reminder.FREQUENCY_DAILY -> k = ChronoUnit.DAYS.between(startDate, day);
            case Reminder.FREQUENCY_WEEKLY -> k = Math.floorDiv(ChronoUnit.DAYS.between(startDate, day), 7);
            // MONTHS.between counts whole months; one less keeps the estimate on or before the day
            case Reminder.FREQUENCY_MONTHLY -> k = ChronoUnit.MONTHS.between(startDate, day) - 1;
            default -> {
                return -1;
            }
        }
        return Math.max(0, k);
    }
}
