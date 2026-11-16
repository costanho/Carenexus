package com.carenexus.api.ai.detection.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Disease-risk screening settings. Defaults live here; application.yml only overrides.
 *
 * <pre>
 * carenexus.ai.provider              = rules   RiskDetectionProvider: rules (rules-v1) | disabled
 * carenexus.ai.assessment-timeout-ms = 3000    longest wait for one assessment; slower is treated as a failure
 * </pre>
 *
 * The {@code carenexus.ai} prefix is shared with the alert generators, which bind their own keys
 * (missed-dose-threshold, anomaly-deviation-percent, interaction-provider, follow-up-lookback-days) with a separate
 * properties class; this class binds only the two keys above.
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.ai")
public class AiDetectionProperties {

    public static final String RULES = "rules";
    public static final String DISABLED = "disabled";

    static final long DEFAULT_TIMEOUT_MS = 3000;
    static final long MIN_TIMEOUT_MS = 50;
    static final long MAX_TIMEOUT_MS = 60_000;

    /** Risk detection provider id: {@code rules} or {@code disabled}. */
    private String provider = RULES;

    /** Longest wait for one assessment in milliseconds, clamped to 50..60000. */
    private long assessmentTimeoutMs = DEFAULT_TIMEOUT_MS;

    /** The provider id trimmed and lower-cased; blank means {@code rules}. */
    public String providerId() {
        String id = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? RULES : id;
    }

    /** The assessment timeout clamped to a sane range. */
    public long effectiveTimeoutMs() {
        return Math.min(Math.max(assessmentTimeoutMs, MIN_TIMEOUT_MS), MAX_TIMEOUT_MS);
    }
}
