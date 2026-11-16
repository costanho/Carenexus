package com.carenexus.api.scheduling;

import com.carenexus.api.scheduling.service.AvailabilityCalculator;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Block;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Candidate;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Interval;
import com.carenexus.api.scheduling.service.AvailabilityCalculator.Window;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The slot arithmetic with fixed inputs (no database, no clock). */
class AvailabilityCalculatorTest {

    /** A Thursday. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    private static final int OPEN = 8 * 60;
    private static final int CLOSE = 18 * 60;
    private static final LocalDateTime LONG_AGO = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime FAR_AHEAD = LocalDateTime.of(2027, 12, 31, 0, 0);

    private static int m(String hhmm) {
        return AvailabilityCalculator.minuteOfDay(LocalTime.parse(hhmm));
    }

    private static LocalDateTime at(String hhmm) {
        return DAY.atTime(LocalTime.parse(hhmm));
    }

    private static List<String> times(List<Candidate> candidates) {
        return candidates.stream().map(c -> c.start().toLocalTime().toString()).toList();
    }

    private static List<String> available(List<Candidate> candidates) {
        return candidates.stream().filter(Candidate::available).map(c -> c.start().toLocalTime().toString()).toList();
    }

    @Test
    void templateWindows_useOnlyActiveBlocksOfTheWeekday_mergeTouchingBlocks_andClipToClinicHours() {
        List<Block> blocks = List.of(
                new Block(4, m("12:00"), m("13:00"), true),
                new Block(4, m("09:00"), m("12:00"), true),   // touches the 12:00 block: one window 09:00-13:00
                new Block(4, m("14:00"), m("19:00"), true),   // clipped to the 18:00 close
                new Block(4, m("06:00"), m("07:30"), true),   // entirely before opening: dropped
                new Block(4, m("20:00"), m("21:00"), false),  // inactive
                new Block(5, m("08:00"), m("18:00"), true));  // another weekday

        List<Window> windows = AvailabilityCalculator.templateWindows(blocks, DAY, OPEN, CLOSE, true);

        assertEquals(List.of(new Window(m("09:00"), m("13:00")), new Window(m("14:00"), m("18:00"))), windows);
    }

    @Test
    void templateWindows_areEmptyWhenTheClinicIsClosedThatDay_orNoActiveBlockExists() {
        List<Block> blocks = List.of(new Block(4, m("09:00"), m("12:00"), true));
        assertTrue(AvailabilityCalculator.templateWindows(blocks, DAY, OPEN, CLOSE, false).isEmpty());
        assertTrue(AvailabilityCalculator.templateWindows(
                List.of(new Block(4, m("09:00"), m("12:00"), false)), DAY, OPEN, CLOSE, true).isEmpty());
    }

    @Test
    void clinicWindows_spanOpeningToClosing_andCloseHour24MeansEndOfDay() {
        assertEquals(List.of(new Window(OPEN, CLOSE)), AvailabilityCalculator.clinicWindows(OPEN, CLOSE, true));
        assertEquals(List.of(new Window(0, 1440)), AvailabilityCalculator.clinicWindows(0, 1440, true));
        assertTrue(AvailabilityCalculator.clinicWindows(OPEN, CLOSE, false).isEmpty());
    }

    @Test
    void slots_stepBySlotLength_andOnlyKeepStartsWhoseAppointmentEndsInsideTheWindow() {
        List<Window> windows = List.of(new Window(m("09:00"), m("10:30")), new Window(m("14:00"), m("15:00")));

        List<Candidate> thirty = AvailabilityCalculator.slots(DAY, windows, 30, 30, LONG_AGO, FAR_AHEAD, List.of(), List.of());
        assertEquals(List.of("09:00", "09:30", "10:00", "14:00", "14:30"), times(thirty));

        List<Candidate> hour = AvailabilityCalculator.slots(DAY, windows, 30, 60, LONG_AGO, FAR_AHEAD, List.of(), List.of());
        assertEquals(List.of("09:00", "09:30", "14:00"), times(hour));

        List<Candidate> tooLong = AvailabilityCalculator.slots(DAY, windows, 30, 120, LONG_AGO, FAR_AHEAD, List.of(), List.of());
        assertTrue(tooLong.isEmpty(), "no window is long enough for 120 minutes");
    }

    @Test
    void slots_overlappingAppointmentsAndTimeOff_makeStartsUnavailable_touchingOnesDoNot() {
        List<Window> windows = List.of(new Window(m("09:00"), m("13:00")));
        List<Interval> busy = List.of(
                new Interval(at("08:45"), at("09:15")),   // starts before the window, still blocks 09:00
                new Interval(at("10:00"), at("10:30")));
        List<Interval> timeOff = List.of(new Interval(at("12:30"), DAY.plusDays(1).atStartOfDay()));

        List<Candidate> thirty = AvailabilityCalculator.slots(DAY, windows, 30, 30, LONG_AGO, FAR_AHEAD, timeOff, busy);
        assertEquals(List.of("09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30"), times(thirty));
        assertEquals(List.of("09:30", "10:30", "11:00", "11:30", "12:00"), available(thirty),
                "09:30 touches the 09:15 end and 10:30 touches the 10:30 end: both are free");

        List<Candidate> hour = AvailabilityCalculator.slots(DAY, windows, 30, 60, LONG_AGO, FAR_AHEAD, timeOff, busy);
        assertEquals(List.of("10:30", "11:00", "11:30"), available(hour));
    }

    @Test
    void slots_inThePastOrAtNow_orBeyondTheHorizon_areUnavailable() {
        List<Window> windows = List.of(new Window(m("09:00"), m("12:00")));

        List<Candidate> partialDay = AvailabilityCalculator.slots(DAY, windows, 30, 30, at("10:00"), FAR_AHEAD,
                List.of(), List.of());
        assertEquals(6, partialDay.size(), "past starts are returned, not omitted");
        assertEquals(List.of("10:30", "11:00", "11:30"), available(partialDay), "a start equal to now is not available");

        List<Candidate> horizon = AvailabilityCalculator.slots(DAY, windows, 30, 30, LONG_AGO, at("10:30"),
                List.of(), List.of());
        assertEquals(List.of("09:00", "09:30", "10:00", "10:30"), available(horizon), "a start equal to the horizon is fine");
    }

    @Test
    void slots_areCappedAtOneDayOfFiveMinuteStarts() {
        List<Candidate> all = AvailabilityCalculator.slots(DAY, List.of(new Window(0, 1440)), 5, 5, LONG_AGO, FAR_AHEAD,
                List.of(), List.of());
        assertEquals(AvailabilityCalculator.MAX_SLOTS, all.size());
        assertEquals("23:55", all.get(all.size() - 1).start().toLocalTime().toString());
    }

    @Test
    void fitsInsideOneWindow_requiresTheWholeAppointmentInOneWindowOfItsDay() {
        List<Window> windows = List.of(new Window(m("08:00"), m("13:00")), new Window(m("14:00"), m("18:00")));

        assertTrue(AvailabilityCalculator.fitsInsideOneWindow(windows, at("08:00"), 30));
        assertTrue(AvailabilityCalculator.fitsInsideOneWindow(windows, at("12:30"), 30), "ending exactly at 13:00 fits");
        assertTrue(AvailabilityCalculator.fitsInsideOneWindow(windows, at("09:15"), 45), "off-grid starts are fine");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(windows, at("12:45"), 30), "runs into the break");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(windows, at("13:30"), 30), "inside the break");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(windows, at("07:45"), 30), "starts before the window");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(windows, at("12:00"), 150), "spans two windows");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(List.of(new Window(m("23:00"), 1440)), at("23:30"), 60),
                "an appointment cannot run past midnight");
        assertFalse(AvailabilityCalculator.fitsInsideOneWindow(List.of(), at("09:00"), 30));
    }
}
