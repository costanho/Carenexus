package com.carenexus.api.auth.config;

import com.carenexus.api.auth.provider.HumanVerification;
import com.carenexus.api.auth.provider.NoopHumanVerification;
import com.carenexus.api.common.config.EnvironmentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the {@link HumanVerification} implementation from {@code carenexus.auth.human-verification}
 * (default {@code none}). {@code none} is the only value shipped; a real provider is added here as another branch,
 * or as a bean, which takes precedence.
 *
 * {@code none} means the feature is switched off, it is not a stand-in that pretends to verify. It is therefore
 * allowed in every environment; outside developer profiles one WARN line records that registration and password
 * recovery are protected by rate limits only. An unknown value fails startup, so a typing mistake can never switch
 * verification off silently.
 */
@Configuration
@Slf4j
public class HumanVerificationConfig {

    @Bean
    @ConditionalOnMissingBean(HumanVerification.class)
    public HumanVerification humanVerification(
            @Value("${carenexus.auth.human-verification:none}") String configured,
            EnvironmentInfo environmentInfo) {
        String provider = configured == null || configured.isBlank()
                ? NoopHumanVerification.PROVIDER : configured.trim().toLowerCase(Locale.ROOT);
        if (!NoopHumanVerification.PROVIDER.equals(provider)) {
            throw new IllegalStateException("Unsupported carenexus.auth.human-verification '" + configured
                    + "'. Supported: " + NoopHumanVerification.PROVIDER);
        }
        if (!environmentInfo.isDevProfile()) {
            log.warn("Human verification is off (carenexus.auth.human-verification=none, environment: {}): "
                    + "registration and password recovery are protected by rate limits only.",
                    environmentInfo.environment());
        }
        return new NoopHumanVerification();
    }
}
