package com.carenexus.api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The application clock, in the clinic zone ({@code carenexus.scheduling.time-zone}). Jobs, token expiry, rate
 * limits and idempotency inject {@link Clock} instead of calling {@code now()} so tests can fix the time by
 * providing their own {@code Clock} bean.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock(@Value("${carenexus.scheduling.time-zone:Africa/Harare}") String timeZone) {
        // PostgreSQL stores timestamps to the microsecond. Linux JVMs read the system clock to the nanosecond, so a
        // value returned in the response that wrote it would differ from the same value read back later. Ticking
        // in microseconds keeps the two identical on every platform.
        return Clock.tick(Clock.system(ZoneId.of(timeZone)), Duration.of(1, ChronoUnit.MICROS));
    }
}
