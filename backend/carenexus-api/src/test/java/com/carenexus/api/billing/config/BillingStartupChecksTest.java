package com.carenexus.api.billing.config;

import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.provider.LocalPaymentGateway;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup rules of the payment seam: LocalProviderGuard for the local gateway, and the local webhook secret outside
 * the developer profiles (no development value, at least 32 characters; a missing secret only disables online
 * payments).
 */
class BillingStartupChecksTest {

    private static final String REAL_SECRET = "9f2c4e7a1b3d5f60718293a4b5c6d7e8f9a0b1c2";

    private static BillingProperties properties(String secret) {
        BillingProperties properties = new BillingProperties();
        properties.getLocal().setWebhookSecret(secret);
        return properties;
    }

    private static EnvironmentInfo environment(String environment, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new EnvironmentInfo(env, environment);
    }

    private static BillingStartupChecks checks(BillingProperties properties, EnvironmentInfo env, boolean allowLocal) {
        PaymentGateway gateway = new BillingGatewayConfig().paymentGateway(properties, new ObjectMapper());
        return new BillingStartupChecks(properties, gateway, new LocalProviderGuard(env, allowLocal), env);
    }

    @Test
    void developerProfiles_acceptTheCommittedDevelopmentSecret() {
        for (String profile : List.of("local", "docker", "test")) {
            assertThatCode(() -> checks(properties(BillingProperties.Local.DEV_WEBHOOK_SECRET),
                    environment("development", profile), false).check()).doesNotThrowAnyException();
        }
    }

    @Test
    void outsideDeveloperProfiles_aDevelopmentSecretFailsStartup() {
        assertThatThrownBy(() -> checks(properties(BillingProperties.Local.DEV_WEBHOOK_SECRET),
                environment("staging", "prod"), false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-secret");
        assertThatThrownBy(() -> checks(properties("local-dev-only-billing-webhook-secret-NOT-FOR-PRODUCTION"),
                environment("staging", "prod"), false).check())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> checks(properties("test-only-billing-webhook-secret-NOT-FOR-PRODUCTION"),
                environment("development"), false).check())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outsideDeveloperProfiles_aShortSecretFailsStartup() {
        assertThatThrownBy(() -> checks(properties("short-but-random-7Hq2"), environment("staging", "prod"), false)
                .check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void outsideDeveloperProfiles_aRealSecretStarts_andAMissingSecretOnlyDisablesOnlinePayments() {
        assertThatCode(() -> checks(properties(REAL_SECRET), environment("staging", "prod"), false).check())
                .doesNotThrowAnyException();

        BillingProperties noSecret = properties("");
        assertThatCode(() -> checks(noSecret, environment("staging", "prod"), false).check())
                .doesNotThrowAnyException();
        LocalPaymentGateway gateway = new LocalPaymentGateway(noSecret, new ObjectMapper());
        assertThat(gateway.isConfigured()).isFalse();
        assertThat(gateway.supportedMethods()).containsExactly(PaymentMethod.TYPE_INSURANCE, PaymentMethod.TYPE_CASH,
                PaymentMethod.TYPE_BANK_TRANSFER);
    }

    @Test
    void production_refusesTheLocalGateway_unlessLocalProvidersAreAllowed() {
        assertThatThrownBy(() -> checks(properties(REAL_SECRET), environment("production", "prod"), false).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment-gateway");
        assertThatCode(() -> checks(properties(REAL_SECRET), environment("production", "prod"), true).check())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> checks(properties(BillingProperties.Local.DEV_WEBHOOK_SECRET),
                environment("production", "prod"), true).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-secret");
    }

    @Test
    void anUnknownProviderFailsStartup() {
        BillingProperties properties = properties(REAL_SECRET);
        properties.setProvider("stripe");
        assertThatThrownBy(() -> new BillingGatewayConfig().paymentGateway(properties, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe");
    }
}
