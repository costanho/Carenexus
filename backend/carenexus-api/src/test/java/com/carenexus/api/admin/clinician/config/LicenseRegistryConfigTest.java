package com.carenexus.api.admin.clinician.config;

import com.carenexus.api.admin.clinician.provider.LicenseRegistryClient;
import com.carenexus.api.admin.clinician.provider.ManualReviewLicenseRegistryClient;
import com.carenexus.api.admin.clinician.provider.RegistryStatus;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The licence registry seam: selection by {@code carenexus.auth.licence-registry}, the manual-review answer, and the
 * provider-seam startup rule.
 */
class LicenseRegistryConfigTest {

    private final LicenseRegistryConfig config = new LicenseRegistryConfig();

    @Test
    void manual_isTheDefault_andAlwaysAsksForManualReview() {
        for (String value : new String[]{null, "", "  ", "manual", " MANUAL "}) {
            LicenseRegistryClient client = config.licenseRegistryClient(value);
            assertInstanceOf(ManualReviewLicenseRegistryClient.class, client);
            assertEquals("manual", client.providerName());
        }
        LicenseRegistryClient client = config.licenseRegistryClient("manual");
        assertEquals(RegistryStatus.UNVERIFIED_MANUAL_REVIEW, client.lookup("MDPCZ-2019-04417", "Council", "ZW"));
        assertEquals(RegistryStatus.UNVERIFIED_MANUAL_REVIEW, client.lookup("X", null, null));
    }

    @Test
    void anUnknownProvider_failsStartup() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> config.licenseRegistryClient("mdpcz-api"));
        assertTrue(failure.getMessage().contains("carenexus.auth.licence-registry"));
    }

    @Test
    void theManualClient_isRefusedInProductionUnlessExplicitlyAllowed() {
        LicenseRegistryClient manual = config.licenseRegistryClient("manual");

        assertDoesNotThrow(() -> new LicenseRegistryStartupChecks(guard("development", false, "test"), manual).check());
        assertDoesNotThrow(() -> new LicenseRegistryStartupChecks(guard("staging", false), manual).check());
        assertThrows(IllegalStateException.class,
                () -> new LicenseRegistryStartupChecks(guard("production", false), manual).check());
        assertDoesNotThrow(() -> new LicenseRegistryStartupChecks(guard("production", true), manual).check());

        // A real registry adapter is fine everywhere.
        LicenseRegistryClient real = new LicenseRegistryClient() {
            @Override
            public String providerName() {
                return "council-api";
            }

            @Override
            public RegistryStatus lookup(String licenseNo, String licensingBody, String country) {
                return RegistryStatus.VERIFIED;
            }
        };
        assertDoesNotThrow(() -> new LicenseRegistryStartupChecks(guard("production", false), real).check());
    }

    private static LocalProviderGuard guard(String environment, boolean allowLocal, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        return new LocalProviderGuard(new EnvironmentInfo(spring, environment), allowLocal);
    }
}
