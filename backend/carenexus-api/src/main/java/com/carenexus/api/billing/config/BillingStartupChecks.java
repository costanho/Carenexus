package com.carenexus.api.billing.config;

import com.carenexus.api.billing.provider.LocalPaymentGateway;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup rules of the payment seam (CONTRACTS.v2 5.7):
 * <ul>
 *   <li>{@link LocalProviderGuard} decides whether the local gateway may run in this environment (production refuses
 *       it unless {@code carenexus.allow-local-providers=true}; other non-developer environments log one WARN);</li>
 *   <li>outside the developer profiles (local, docker, test) the local gateway refuses a development webhook secret
 *       (the committed code default, or any value marked dev-only, test-only or NOT-FOR-PRODUCTION) and a secret
 *       shorter than {@value #MIN_SECRET_LENGTH} characters, like {@code LiveKitStartupChecks};</li>
 *   <li>a missing secret never fails startup: online payments are then simply unavailable (one WARN line).</li>
 * </ul>
 * The secret itself is never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingStartupChecks {

    static final String SEAM = "payment-gateway";
    static final int MIN_SECRET_LENGTH = 32;

    private final BillingProperties properties;
    private final PaymentGateway paymentGateway;
    private final LocalProviderGuard localProviderGuard;
    private final EnvironmentInfo environmentInfo;

    @PostConstruct
    void check() {
        boolean local = paymentGateway instanceof LocalPaymentGateway;
        localProviderGuard.check(SEAM, paymentGateway.name(), local);
        if (!local || environmentInfo.isDevProfile()) {
            return;
        }
        BillingProperties.Local localSettings = properties.getLocal();
        if (localSettings == null || !localSettings.hasWebhookSecret()) {
            log.warn("Payments: carenexus.billing.local.webhook-secret is not set; card and M-PESA payments are "
                    + "unavailable (set BILLING_LOCAL_WEBHOOK_SECRET)");
            return;
        }
        if (localSettings.isDevWebhookSecret() || localSettings.getWebhookSecret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("carenexus.billing.local.webhook-secret is a development value or shorter "
                    + "than " + MIN_SECRET_LENGTH + " characters; set BILLING_LOCAL_WEBHOOK_SECRET to a random secret");
        }
    }
}
