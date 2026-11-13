package com.carenexus.api.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Billing foundation wiring: binds {@link BillingProperties} ({@code carenexus.billing.*}). The payment gateway
 * and the claims provider are wired by the configurations of the slices that own them.
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfig {
}
