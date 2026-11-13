package com.carenexus.api.common.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application clock must never produce sub-microsecond values: PostgreSQL keeps microseconds, so a timestamp
 * returned by the request that wrote it has to equal the one read back later. Linux JVMs read nanoseconds, macOS
 * JVMs microseconds, so this is asserted directly rather than left to the release gates.
 */
class ClockConfigTest {

    @Test
    void clockTicksInMicroseconds_inTheClinicZone() {
        Clock clock = new ClockConfig().clock("Africa/Harare");

        assertEquals(ZoneId.of("Africa/Harare"), clock.getZone());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(0, clock.instant().getNano() % 1_000, "nanoseconds below the microsecond must be zero");
        }
        // millis() must work too: rate limiting reads it (Clock.tick with a sub-millisecond tick throws here on Java 17)
        long before = System.currentTimeMillis();
        long millis = clock.millis();
        assertTrue(millis >= before && millis <= System.currentTimeMillis());
        assertEquals(clock, clock.withZone(ZoneId.of("Africa/Harare")));
    }
}
