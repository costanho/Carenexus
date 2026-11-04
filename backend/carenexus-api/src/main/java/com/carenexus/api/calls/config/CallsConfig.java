package com.carenexus.api.calls.config;

import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.DisabledCallProvider;
import com.carenexus.api.calls.provider.LiveKitCallProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Video call wiring: properties, scheduling (job worker, expiry sweeper) and the provider adapter.
 * Without LiveKit configuration the app still starts with {@link DisabledCallProvider}: rooms cannot be
 * prepared, tokens are refused with "temporarily unavailable" and the webhook returns 404.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties({LiveKitProperties.class, CallsProperties.class})
public class CallsConfig {

    @Bean
    public CallProvider callProvider(LiveKitProperties properties) {
        if (properties.isConfigured()) {
            log.info("Video calls: LiveKit provider configured");
            return new LiveKitCallProvider(properties);
        }
        log.info("Video calls: LiveKit is not configured; calls are unavailable");
        return new DisabledCallProvider();
    }
}
