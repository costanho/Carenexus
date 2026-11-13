package com.carenexus.api.billing.config;

import com.carenexus.api.billing.provider.LocalPaymentGateway;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the payment gateway by {@code carenexus.billing.provider}. Today only {@code local}
 * ({@link LocalPaymentGateway}) exists; a real adapter (M-PESA Daraja, Stripe, ...) is added as another branch with
 * its own provider id and properties. An unknown provider id fails startup instead of silently taking payments
 * through the local gateway.
 */
@Slf4j
@Configuration
public class BillingGatewayConfig {

    @Bean
    public PaymentGateway paymentGateway(BillingProperties properties, ObjectMapper objectMapper) {
        String provider = properties.getProvider();
        if (BillingProperties.PROVIDER_LOCAL.equals(provider)) {
            LocalPaymentGateway gateway = new LocalPaymentGateway(properties, objectMapper);
            log.info("Payments: local gateway selected (no money moves; online payments settle through the signed "
                    + "local callback{})", gateway.isConfigured() ? "" : ", unavailable until a webhook secret is set");
            return gateway;
        }
        throw new IllegalStateException("carenexus.billing.provider '" + provider + "' is not a known payment gateway; "
                + "supported: " + BillingProperties.PROVIDER_LOCAL + " (set BILLING_PROVIDER)");
    }
}
