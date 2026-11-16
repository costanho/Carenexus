package com.carenexus.api.admin.clinician.config;

import com.carenexus.api.admin.clinician.provider.LicenseRegistryClient;
import com.carenexus.api.admin.clinician.provider.ManualReviewLicenseRegistryClient;
import com.carenexus.api.common.config.LocalProviderGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Applies the provider-seam startup rule ({@link LocalProviderGuard}) to the licence registry: the manual-review
 * client is fine on developer profiles, logs a warning on staging and refuses to start in production unless
 * {@code carenexus.allow-local-providers=true}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LicenseRegistryStartupChecks {

    private final LocalProviderGuard localProviderGuard;
    private final LicenseRegistryClient licenseRegistryClient;

    @PostConstruct
    void check() {
        localProviderGuard.check("licence registry", licenseRegistryClient.providerName(),
                licenseRegistryClient instanceof ManualReviewLicenseRegistryClient);
        log.info("Licence registry provider: {}", licenseRegistryClient.providerName());
    }
}
