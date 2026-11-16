package com.carenexus.api.scheduling.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure arithmetic of doctor availability, shared by the slots endpoint and the booking constraint so both see the
 * same working windows. No database, no clock: every input is passed in, which keeps the rules unit-testable.
 *
 * Times of day are minutes after midnight (0..1440, 1440 = end of the day); a window is {@code [start, end)}.
 * Two intervals overlap when {@code a.start < b.end && b.start < a.end}; intervals that only touch do not.
 */
public final class AvailabilityCalculator {

    public static final int MINUTES_PER_DAY = 24 * 60;

    /** Hard cap of the slots array: one day of 5-minute starts. */
    public static final int MAX_SLOTS = MINUTES_PER_DAY / 5;

    private AvailabilityCalculator() {
    }

    /** One stored template block, reduced to what the rules need. */
    public record Block(int dayOfWeek, int startMinute, int endMinute, boolean active) {
    }

    /** A working window of one day, {@code [startMinute, endMinute)}. */
    public record Window(int startMinute, int endMinute) {
    }

    /** A clinic wall-clock range {@code [start, end)}: a time-off range or an active appointment. */
    public record Interval(LocalDateTime start, LocalDateTime end) {

        public boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return start.isBefore(otherEnd) && otherStart.isBefore(end);
        }
    }

    /** One offered start time and whether a patient-side booking starting there would pass right now. */
    public record Candidate(LocalDateTime start, boolean available) {
    }

    /**
     * Working windows of {@code date} from a template: the active blocks of the date's ISO weekday, sorted by start,
     * touching (or overlapping) blocks merged, each clipped to the clinic window. No window at all when the clinic
     * is closed that day.
     */
    public static List<Window> templateWindows(List<Block> blocks, LocalDate date,
                                               int openMinute, int closeMinute, boolean clinicOpenThatDay) {
        if (!clinicOpenThatDay || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        int weekday = date.getDayOfWeek().getValue();
        List<Window> day = blocks.stream()
                .filter(b -> b.active() && b.dayOfWeek() == weekday && b.endMinute() > b.startMinute())
                .map(b -> new Window(b.startMinute(), b.endMinute()))
                .sorted(Comparator.comparingInt(Window::startMinute).thenComparingInt(Window::endMinute))
                .toList();
        return clip(merge(day), openMinute, closeMinute);
    }

    /** The clinic window {@code openMinute..closeMinute}, or nothing when the clinic is closed that day. */
    public static List<Window> clinicWindows(int openMinute, int closeMinute, boolean clinicOpenThatDay) {
        if (!clinicOpenThatDay || closeMinute <= openMinute) {
            return List.of();
        }
        return List.of(new Window(openMinute, Math.min(closeMinute, MINUTES_PER_DAY)));
    }

    /** Merges sorted windows that touch or overlap into one. */
    static List<Window> merge(List<Window> sorted) {
        List<Window> merged = new ArrayList<>();
        for (Window w : sorted) {
            if (!merged.isEmpty()) {
                Window last = merged.get(merged.size() - 1);
                if (w.startMinute() <= last.endMinute()) {
                    merged.set(merged.size() - 1, new Window(last.startMinute(), Math.max(last.endMinute(), w.endMinute())));
                    continue;
                }
            }
            merged.add(w);
        }
        return merged;
    }

    private static List<Window> clip(List<Window> windows, int openMinute, int closeMinute) {
        List<Window> clipped = new ArrayList<>();
        int close = Math.min(closeMinute, MINUTES_PER_DAY);
        for (Window w : windows) {
            int start = Math.max(w.startMinute(), openMinute);
            int end = Math.min(w.endMinute(), close);
            if (end > start) {
                clipped.add(new Window(start, end));
            }
        }
        return clipped;
    }

    /**
     * Candidate starts of {@code date}: for each window {@code start + k * slotMinutes} while the appointment of
     * {@code durationMinutes} still ends inside the window; ascending. Each is available when it lies strictly after
     * {@code now}, not after {@code horizon}, and overlaps neither a time-off range nor a busy interval.
     */
    public static List<Candidate> slots(LocalDate date, List<Window> windows, int slotMinutes, int durationMinutes,
                                        LocalDateTime now, LocalDateTime horizon,
                                        List<Interval> timeOff, List<Interval> busy) {
        if (slotMinutes <= 0 || durationMinutes <= 0) {
            return List.of();
        }
        LocalDateTime midnight = date.atStartOfDay();
        List<Candidate> result = new ArrayList<>();
        for (Window w : windows) {
            for (int minute = w.startMinute(); minute + durationMinutes <= w.endMinute(); minute += slotMinutes) {
                if (result.size() >= MAX_SLOTS) {
                    return result;
                }
                LocalDateTime start = midnight.plusMinutes(minute);
                LocalDateTime end = start.plusMinutes(durationMinutes);
                boolean available = start.isAfter(now)
                        && !start.isAfter(horizon)
                        && noneOverlaps(timeOff, start, end)
                        && noneOverlaps(busy, start, end);
                result.add(new Candidate(start, available));
            }
        }
        return result;
    }

    /**
     * True when an appointment {@code [start, start + durationMinutes)} lies completely inside one window of
     * {@code start}'s calendar day (and therefore ends on that same day). {@code start} is a whole minute (the booking
     * service truncates it before the constraints run).
     */
    public static boolean fitsInsideOneWindow(List<Window> windows, LocalDateTime start, int durationMinutes) {
        int startMinute = start.getHour() * 60 + start.getMinute();
        long endMinute = (long) startMinute + durationMinutes;
        for (Window w : windows) {
            if (w.startMinute() <= startMinute && endMinute <= w.endMinute()) {
                return true;
            }
        }
        return false;
    }

    private static boolean noneOverlaps(List<Interval> intervals, LocalDateTime start, LocalDateTime end) {
        if (intervals == null) {
            return true;
        }
        for (Interval interval : intervals) {
            if (interval.overlaps(start, end)) {
                return false;
            }
        }
        return true;
    }

    /** Minutes after midnight of a time of day. */
    public static int minuteOfDay(java.time.LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
