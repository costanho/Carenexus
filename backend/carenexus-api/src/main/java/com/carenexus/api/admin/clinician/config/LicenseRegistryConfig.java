package com.carenexus.api.admin.clinician.config;

import com.carenexus.api.admin.clinician.provider.LicenseRegistryClient;
import com.carenexus.api.admin.clinician.provider.ManualReviewLicenseRegistryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the {@link LicenseRegistryClient} from {@code carenexus.auth.licence-registry} (default {@code manual}).
 * {@code manual} is the only value shipped; a real registry adapter is added here as another branch, or as a bean,
 * which takes precedence. An unknown value fails startup so a typing mistake never changes the behaviour silently.
 * The production rule for the local implementation is applied by {@link LicenseRegistryStartupChecks}.
 */
@Configuration
public class LicenseRegistryConfig {

    public static final String PROPERTY = "carenexus.auth.licence-registry";

    @Bean
    @ConditionalOnMissingBean(LicenseRegistryClient.class)
    public LicenseRegistryClient licenseRegistryClient(
            @Value("${" + PROPERTY + ":" + ManualReviewLicenseRegistryClient.PROVIDER + "}") String configured) {
        String provider = configured == null || configured.isBlank()
                ? ManualReviewLicenseRegistryClient.PROVIDER : configured.trim().toLowerCase(Locale.ROOT);
        if (!ManualReviewLicenseRegistryClient.PROVIDER.equals(provider)) {
            throw new IllegalStateException("Unsupported " + PROPERTY + " '" + configured + "'. Supported: "
                    + ManualReviewLicenseRegistryClient.PROVIDER);
        }
        return new ManualReviewLicenseRegistryClient();
    }
}
