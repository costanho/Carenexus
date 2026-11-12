package com.carenexus.api.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

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
        return Clock.system(ZoneId.of(timeZone));
    }
}
