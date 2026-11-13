package com.carenexus.api.common.ratelimit;

import com.carenexus.api.common.config.EnvironmentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Locale;

/**
 * Selects the rate-limit store from {@code carenexus.rate-limit.store}. {@code memory} is the only store shipped;
 * a shared store is added by providing another {@link RateLimiter} here (or as a bean, which takes precedence).
 *
 * The in-memory store is a complete implementation, not a stand-in for an external provider: it enforces every
 * limit, only per API instance. It is therefore NOT subject to the fail-in-production rule of
 * {@link com.carenexus.api.common.config.LocalProviderGuard} (that would force every production deployment to set
 * {@code carenexus.allow-local-providers=true}, which also lets the local email, SMS, storage, payment and AI
 * implementations start). Outside developer profiles it logs one WARN line instead.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@Slf4j
public class RateLimitConfig {

    static final String STORE_MEMORY = "memory";

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter rateLimiter(RateLimitProperties properties, Clock clock, EnvironmentInfo environmentInfo) {
        String store = properties.getStore() == null ? "" : properties.getStore().trim().toLowerCase(Locale.ROOT);
        if (!STORE_MEMORY.equals(store)) {
            throw new IllegalStateException("Unsupported carenexus.rate-limit.store '" + properties.getStore()
                    + "'. Supported: " + STORE_MEMORY);
        }
        if (!environmentInfo.isDevProfile()) {
            log.warn("Rate limits use the in-memory store (environment: {}): rate limits are counted per API instance. "
                    + "With several instances behind a load balancer each one counts separately.",
                    environmentInfo.environment());
        }
        return new InMemoryRateLimiter(properties, clock);
    }
}
