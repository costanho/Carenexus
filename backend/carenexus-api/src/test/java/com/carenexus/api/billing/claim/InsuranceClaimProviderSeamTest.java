package com.carenexus.api.billing.claim;

import com.carenexus.api.billing.config.BillingClaimsConfig;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.provider.InsuranceClaimProvider;
import com.carenexus.api.billing.provider.ManualInsuranceClaimProvider;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The insurance claims provider seam: selection by {@code carenexus.billing.claims-provider}, the manual provider's
 * behaviour, and the provider-seam startup rule (CONTRACTS.v2 5.7).
 */
class InsuranceClaimProviderSeamTest {

    @Test
    void manual_isTheDefault_andTheNameIsCaseInsensitive() {
        for (String value : new String[]{null, "", "  ", "manual", " MANUAL "}) {
            InsuranceClaimProvider provider = BillingClaimsConfig.select(value);
            assertInstanceOf(ManualInsuranceClaimProvider.class, provider);
            assertEquals("manual", provider.name());
        }
        assertEquals(BillingProperties.CLAIMS_PROVIDER_MANUAL, new BillingProperties().getClaimsProvider());
    }

    @Test
    void anUnknownProvider_failsStartupWithTheProperty() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> BillingClaimsConfig.select("acme-clearing"));
        assertTrue(failure.getMessage().contains("carenexus.billing.claims-provider"), failure.getMessage());
        assertTrue(failure.getMessage().contains("acme-clearing"), failure.getMessage());
    }

    @Test
    void theManualProvider_returnsAManualReference_withoutCallingAnything() {
        ManualInsuranceClaimProvider provider = new ManualInsuranceClaimProvider();
        InsuranceClaim claim = InsuranceClaim.builder()
                .claimNo("CLM-2026-000017")
                .patientSystemId("PAT-00001")
                .insuranceProvider("Jubilee Health")
                .policyNumber("JH-88213-K")
                .claimAmount(new BigDecimal("2320.00"))
                .build();
        assertEquals("MANUAL-CLM-2026-000017", provider.submit(claim));
        assertEquals(Optional.empty(), provider.parseStatusUpdate("{\"status\":\"APPROVED\"}", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> provider.submit(new InsuranceClaim()));
    }

    @Test
    void theManualProvider_isRefusedInProductionUnlessExplicitlyAllowed() {
        InsuranceClaimProvider manual = BillingClaimsConfig.select("manual");

        assertDoesNotThrow(() -> BillingClaimsConfig.startupCheck(guard("development", false, "test"), manual));
        assertDoesNotThrow(() -> BillingClaimsConfig.startupCheck(guard("staging", false), manual));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> BillingClaimsConfig.startupCheck(guard("production", false), manual));
        assertTrue(refused.getMessage().contains("insurance-claims"), refused.getMessage());
        assertDoesNotThrow(() -> BillingClaimsConfig.startupCheck(guard("production", true), manual));

        // A real insurer adapter is fine everywhere.
        InsuranceClaimProvider real = new InsuranceClaimProvider() {
            @Override
            public String name() {
                return "clearing-house";
            }

            @Override
            public String submit(InsuranceClaim claim) {
                return "CH-1";
            }

            @Override
            public Optional<ClaimStatusUpdate> parseStatusUpdate(String rawBody, Map<String, String> headers) {
                return Optional.empty();
            }
        };
        assertDoesNotThrow(() -> BillingClaimsConfig.startupCheck(guard("production", false), real));
    }

    @Test
    void theStartupCheckBean_appliesTheRuleToTheActiveProvider_whereverItIsDefined() throws Exception {
        BillingClaimsConfig config = new BillingClaimsConfig();
        InsuranceClaimProvider provider = config.insuranceClaimProvider(new BillingProperties());
        assertEquals("manual", provider.name());
        assertThrows(IllegalStateException.class,
                () -> config.insuranceClaimProviderStartupCheck(provider, guard("production", false)).afterPropertiesSet());
        assertDoesNotThrow(() -> config.insuranceClaimProviderStartupCheck(provider, guard("development", false, "local"))
                .afterPropertiesSet());

        // A manual provider supplied by another bean definition (the factory method above then backs off) is still
        // refused in production: the check no longer lives inside the factory method.
        InsuranceClaimProvider elsewhere = new ManualInsuranceClaimProvider();
        assertThrows(IllegalStateException.class,
                () -> config.insuranceClaimProviderStartupCheck(elsewhere, guard("production", false)).afterPropertiesSet());
    }

    private static LocalProviderGuard guard(String environment, boolean allowLocal, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        return new LocalProviderGuard(new EnvironmentInfo(spring, environment), allowLocal);
    }
}
