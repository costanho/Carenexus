package com.carenexus.api.delivery.fanout;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quiet-window arithmetic on its own: the worked examples of the contract (clinic zone Africa/Harare, UTC+2,
 * window 22:00 to 07:00), same-day windows, inclusive start and exclusive end, missing or equal bounds, the
 * recipient's time zone and its fallbacks, and a daylight-saving gap.
 */
class QuietHoursTest {

    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");
    private static final LocalTime TEN_PM = LocalTime.of(22, 0);
    private static final LocalTime SEVEN_AM = LocalTime.of(7, 0);

    private static QuietHours night(String timezone) {
        return new QuietHours(true, TEN_PM, SEVEN_AM, timezone);
    }

    /** The clinic wall-clock value of an instant, the form notBefore takes. */
    private static LocalDateTime clinic(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), HARARE);
    }

    // ==================== CONTRACT WORKED EXAMPLES ====================

    @Test
    void lateEveningInTheClinicZone_isHeldUntilSevenTheNextMorning() {
        assertEquals(clinic("2026-11-11T05:00:00Z"),
                night(null).deferUntil(Instant.parse("2026-11-10T20:30:00Z"), HARARE));
    }

    @Test
    void theSameInstantInNewYork_isAfternoon_andNotHeld() {
        assertNull(night("America/New_York").deferUntil(Instant.parse("2026-11-10T20:30:00Z"), HARARE));
    }

    @Test
    void earlyMorningBeforeTheEnd_isHeldUntilSevenTheSameDay() {
        assertEquals(clinic("2026-11-11T05:00:00Z"),
                night(null).deferUntil(Instant.parse("2026-11-11T04:00:00Z"), HARARE));
    }

    @Test
    void theEndIsExclusive() {
        assertNull(night(null).deferUntil(Instant.parse("2026-11-11T05:00:00Z"), HARARE));
    }

    @Test
    void theStartIsInclusive() {
        // 22:00 Harare
        assertEquals(clinic("2026-11-11T05:00:00Z"),
                night(null).deferUntil(Instant.parse("2026-11-10T20:00:00Z"), HARARE));
        // one second before: outside
        assertNull(night(null).deferUntil(Instant.parse("2026-11-10T19:59:59Z"), HARARE));
    }

    @Test
    void anInvalidZone_fallsBackToTheClinicZone() {
        assertEquals(clinic("2026-11-11T05:00:00Z"),
                night("Not/AZone").deferUntil(Instant.parse("2026-11-10T20:30:00Z"), HARARE));
        assertEquals(clinic("2026-11-11T05:00:00Z"),
                night("   ").deferUntil(Instant.parse("2026-11-10T20:30:00Z"), HARARE));
        assertEquals(HARARE, night("Not/AZone").zone(HARARE));
    }

    // ==================== SAME-DAY WINDOW ====================

    @Test
    void sameDayWindow_heldUntilItsEnd_andNotAtTheEnd() {
        QuietHours afternoon = new QuietHours(true, LocalTime.of(13, 0), LocalTime.of(15, 0), null);
        // 14:00 Harare -> 15:00 Harare the same day
        assertEquals(clinic("2026-11-10T13:00:00Z"), afternoon.deferUntil(Instant.parse("2026-11-10T12:00:00Z"), HARARE));
        // 15:00 Harare -> due
        assertNull(afternoon.deferUntil(Instant.parse("2026-11-10T13:00:00Z"), HARARE));
        // 12:59 Harare -> due
        assertNull(afternoon.deferUntil(Instant.parse("2026-11-10T10:59:00Z"), HARARE));
        // 23:00 Harare: a same-day window never covers the night
        assertNull(afternoon.deferUntil(Instant.parse("2026-11-10T21:00:00Z"), HARARE));
    }

    // ==================== NO WINDOW ====================

    @Test
    void disabledMissingOrEqualBounds_meanNoWindow() {
        Instant lateEvening = Instant.parse("2026-11-10T20:30:00Z");
        assertNull(new QuietHours(false, TEN_PM, SEVEN_AM, null).deferUntil(lateEvening, HARARE));
        assertNull(new QuietHours(true, null, SEVEN_AM, null).deferUntil(lateEvening, HARARE));
        assertNull(new QuietHours(true, TEN_PM, null, null).deferUntil(lateEvening, HARARE));
        assertNull(new QuietHours(true, TEN_PM, TEN_PM, null).deferUntil(lateEvening, HARARE));
        assertNull(QuietHours.NONE.deferUntil(lateEvening, HARARE));
        assertFalse(new QuietHours(true, TEN_PM, TEN_PM, null).hasWindow());
        assertTrue(night(null).hasWindow());
    }

    // ==================== THE RECIPIENT'S ZONE ====================

    @Test
    void aWindowInAnotherZone_isConvertedToClinicWallClock() {
        // 14:00Z is 16:00 in Harare (outside the window) but 23:00 in Tokyo (inside): held until 07:00 Tokyo,
        // which is 22:00Z, 00:00 the next day in Harare.
        Instant instant = Instant.parse("2030-11-10T14:00:00Z");
        assertNull(night(null).deferUntil(instant, HARARE));
        assertEquals(LocalDateTime.of(2030, 11, 11, 0, 0), night("Asia/Tokyo").deferUntil(instant, HARARE));
    }

    @Test
    void anEndInsideADaylightSavingGap_movesForward() {
        // 2030-03-10: New York clocks jump from 02:00 to 03:00. Window 23:00 to 02:30 local, now 01:00 EST (06:00Z).
        QuietHours window = new QuietHours(true, LocalTime.of(23, 0), LocalTime.of(2, 30), "America/New_York");
        LocalDateTime until = window.deferUntil(Instant.parse("2030-03-10T06:00:00Z"), HARARE);
        // 02:30 does not exist; ZonedDateTime moves it to 03:30 EDT = 07:30Z = 09:30 Harare.
        assertEquals(LocalDateTime.of(2030, 3, 10, 9, 30), until);
    }
}
