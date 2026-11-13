package com.carenexus.api.common.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A clock whose instants are truncated to the microsecond, the precision PostgreSQL stores.
 *
 * <p>{@code Clock.tick(clock, Duration.ofNanos(1000))} would do the same for {@link #instant()}, but on Java 17 its
 * {@code millis()} divides by the tick in whole milliseconds, which is zero for a sub-millisecond tick, and throws
 * {@link ArithmeticException}. Rate limiting and other code reading {@code millis()} would then fail.
 */
public final class MicrosecondClock extends Clock {

    private final Clock base;

    public MicrosecondClock(Clock base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(base.getZone()) ? this : new MicrosecondClock(base.withZone(zone));
    }

    @Override
    public Instant instant() {
        return base.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public long millis() {
        return base.millis();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MicrosecondClock that && base.equals(that.base);
    }

    @Override
    public int hashCode() {
        return base.hashCode() ^ 0x4d494352;
    }

    @Override
    public String toString() {
        return "MicrosecondClock[" + base + "]";
    }
}
