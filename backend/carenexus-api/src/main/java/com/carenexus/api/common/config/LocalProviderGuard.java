package com.carenexus.api.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one startup rule for provider seams (email, SMS, push, storage, scanning, payments, AI, ...). The in-memory
 * rate-limit store is exempt: it is a complete implementation that only counts per instance, see
 * {@code RateLimitConfig}.
 * Each seam's startup check calls {@link #check} with the implementation that configuration selected:
 * <ul>
 *   <li>a real implementation: always fine;</li>
 *   <li>a local/fake implementation on a developer profile (local, docker, test): fine;</li>
 *   <li>a local/fake implementation with {@code carenexus.environment=production}: startup fails, unless
 *       {@code carenexus.allow-local-providers=true} explicitly accepts it;</li>
 *   <li>a local/fake implementation in any other non-developer environment: one WARN line.</li>
 * </ul>
 */
@Component
@Slf4j
public class LocalProviderGuard {

    private final EnvironmentInfo environmentInfo;
    private final boolean allowLocalProviders;

    public LocalProviderGuard(EnvironmentInfo environmentInfo,
                              @Value("${carenexus.allow-local-providers:false}") boolean allowLocalProviders) {
        this.environmentInfo = environmentInfo;
        this.allowLocalProviders = allowLocalProviders;
    }

    /**
     * @param seam                  short seam name used in the message, for example "email", "storage", "payments"
     * @param providerName          the selected provider id, for example "local", "memory", "rules"
     * @param isLocalImplementation true when the selected implementation is the local/fake one
     * @throws IllegalStateException in production when a local implementation is selected and not explicitly allowed
     */
    public void check(String seam, String providerName, boolean isLocalImplementation) {
        if (!isLocalImplementation || environmentInfo.isDevProfile()) {
            return;
        }
        if (environmentInfo.isProduction() && !allowLocalProviders) {
            throw new IllegalStateException("The " + seam + " provider '" + providerName + "' is a local implementation "
                    + "and must not run in production. Configure a real provider for " + seam
                    + ", or set carenexus.allow-local-providers=true (CARENEXUS_ALLOW_LOCAL_PROVIDERS) to accept it.");
        }
        log.warn("The {} provider '{}' is a local implementation (environment: {}). Configure a real provider before launch.",
                seam, providerName, environmentInfo.environment());
    }
}
