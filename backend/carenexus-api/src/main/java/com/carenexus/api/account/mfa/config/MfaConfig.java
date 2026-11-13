package com.carenexus.api.account.mfa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link MfaProperties}. TOTP needs no external provider, so there is no provider seam to select. */
@Configuration
@EnableConfigurationProperties(MfaProperties.class)
public class MfaConfig {
}
