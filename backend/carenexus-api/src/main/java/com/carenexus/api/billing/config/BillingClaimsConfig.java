package com.carenexus.api.billing.config;

import com.carenexus.api.billing.provider.InsuranceClaimProvider;
import com.carenexus.api.billing.provider.ManualInsuranceClaimProvider;
import com.carenexus.api.common.config.LocalProviderGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Selects the {@link InsuranceClaimProvider} from {@link BillingProperties#getClaimsProvider()}
 * ({@code carenexus.billing.claims-provider}, default {@code manual}). {@code manual} is the only implementation
 * shipped; a real insurer adapter is added here as another branch. Any other value fails startup with a clear
 * message, so a typing mistake never changes the behaviour silently.
 * <p>
 * Startup check (CONTRACTS.v2 5.7): the selected provider is reported to
 * {@link LocalProviderGuard#check(String, String, boolean)} as {@code ("insurance-claims", "manual", true)} when the
 * manual provider is selected. The guard accepts it on developer profiles, logs a warning on staging and refuses to
 * start in production unless {@code carenexus.allow-local-providers=true}. The check is its own bean and applies to
 * whichever InsuranceClaimProvider bean is active, so a provider bean defined elsewhere (which replaces the one built
 * here) is checked as well.
 */
@Configuration
@Slf4j
public class BillingClaimsConfig {

    public static final String PROPERTY = "carenexus.billing.claims-provider";

    /** Seam name reported to {@link LocalProviderGuard}. */
    public static final String SEAM = "insurance-claims";

    @Bean
    @ConditionalOnMissingBean(InsuranceClaimProvider.class)
    public InsuranceClaimProvider insuranceClaimProvider(BillingProperties properties) {
        return select(properties.getClaimsProvider());
    }

    /** Runs {@link #startupCheck} once, at startup, for the active provider bean, wherever it was defined. */
    @Bean
    public InitializingBean insuranceClaimProviderStartupCheck(InsuranceClaimProvider provider,
                                                              LocalProviderGuard localProviderGuard) {
        return () -> startupCheck(localProviderGuard, provider);
    }

    /**
     * The provider for a configured name (trimmed, case-insensitive; blank means {@code manual}).
     *
     * @throws IllegalStateException for an unsupported name
     */
    public static InsuranceClaimProvider select(String configured) {
        String name = configured == null || configured.isBlank()
                ? BillingProperties.CLAIMS_PROVIDER_MANUAL : configured.trim().toLowerCase(Locale.ROOT);
        if (ManualInsuranceClaimProvider.NAME.equals(name)) {
            return new ManualInsuranceClaimProvider();
        }
        throw new IllegalStateException("Unsupported " + PROPERTY + " '" + configured + "'. Supported: "
                + ManualInsuranceClaimProvider.NAME);
    }

    /** Applies the provider-seam startup rule to the selected provider. */
    public static void startupCheck(LocalProviderGuard localProviderGuard, InsuranceClaimProvider provider) {
        localProviderGuard.check(SEAM, provider.name(), provider instanceof ManualInsuranceClaimProvider);
        log.info("Insurance claims provider: {}", provider.name());
    }
}
