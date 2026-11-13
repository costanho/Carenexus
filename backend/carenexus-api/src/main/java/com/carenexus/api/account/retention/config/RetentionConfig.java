package com.carenexus.api.account.retention.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link RetentionProperties}. */
@Configuration
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionConfig {
}
