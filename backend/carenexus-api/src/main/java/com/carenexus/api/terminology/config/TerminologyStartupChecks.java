package com.carenexus.api.terminology.config;

import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.terminology.provider.BundledIcd10Provider;
import com.carenexus.api.terminology.provider.TerminologyProvider;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Applies the provider-seam startup rule to the terminology provider. The bundled ICD-10 catalogue is a complete,
 * intended production implementation (a subset plus a format check, reporting codes outside it as unverified), not
 * a local stand-in, so it is registered as such and does not require {@code carenexus.allow-local-providers}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TerminologyStartupChecks {

    private final LocalProviderGuard localProviderGuard;
    private final TerminologyProvider terminologyProvider;

    @PostConstruct
    void check() {
        localProviderGuard.check("terminology", terminologyProvider.providerName(), false);
        if (terminologyProvider instanceof BundledIcd10Provider bundled) {
            log.info("Terminology provider: {} ({} ICD-10 codes)", bundled.providerName(), bundled.size());
        } else {
            log.info("Terminology provider: {}", terminologyProvider.providerName());
        }
    }
}
