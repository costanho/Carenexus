package com.carenexus.api.common.ratelimit;

import com.carenexus.api.common.config.EnvironmentInfo;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Store selection (no Spring context): the in-memory store starts in every environment, including production
 * without carenexus.allow-local-providers, and an unknown store name fails startup.
 */
public class RateLimitConfigTest {

    private static EnvironmentInfo info(String environment, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        return new EnvironmentInfo(spring, environment);
    }

    @Test
    void memoryStore_startsInEveryEnvironment_withoutAllowLocalProviders() {
        RateLimitConfig config = new RateLimitConfig();
        for (EnvironmentInfo environment : new EnvironmentInfo[]{
                info("production"), info("staging"), info("development"), info("development", "local"),
                info("development", "test")}) {
            RateLimiter limiter = config.rateLimiter(new RateLimitProperties(), Clock.systemUTC(), environment);
            assertInstanceOf(InMemoryRateLimiter.class, limiter, environment.environment());
            assertTrue(limiter.tryAcquire("default", "PAT-00001").allowed());
        }
    }

    @Test
    void storeName_isTrimmedAndCaseInsensitive() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setStore("  Memory ");
        assertInstanceOf(InMemoryRateLimiter.class,
                new RateLimitConfig().rateLimiter(properties, Clock.systemUTC(), info("production")));
    }

    @Test
    void unknownOrBlankStore_failsStartup() {
        for (String store : new String[]{"redis", "", null}) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setStore(store);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new RateLimitConfig().rateLimiter(properties, Clock.systemUTC(), info("development", "test")),
                    String.valueOf(store));
            assertTrue(ex.getMessage().contains("carenexus.rate-limit.store"));
        }
    }
}
