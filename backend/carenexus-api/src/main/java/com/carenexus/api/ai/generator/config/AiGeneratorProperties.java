package com.carenexus.api.ai.generator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Settings of the alert generators ({@code carenexus.ai.*}). Defaults live here; application.yml only overrides.
 * The detection pipeline binds the same prefix with its own properties class and other keys.
 *
 * <pre>
 * carenexus.ai.missed-dose-threshold      3      consecutive missed doses that raise MISSED_MEDICATION
 * carenexus.ai.anomaly-deviation-percent  25     deviation from the 30-day median that raises ANOMALY_DETECTED
 * carenexus.ai.interaction-provider       rules  medication-interaction checker: rules | disabled
 * carenexus.ai.follow-up-lookback-days    30     oldest overdue follow-up date the follow-up-due job considers
 * </pre>
 *
 * The job interval is {@code carenexus.jobs.follow-up-due.interval-ms} (default 86400000, daily).
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.ai")
public class AiGeneratorProperties {

    public static final String PROVIDER_RULES = "rules";
    public static final String PROVIDER_DISABLED = "disabled";

    /** A MISSED_MEDICATION alert is raised when a missed dose ends a run of at least this many misses (min 1). */
    private int missedDoseThreshold = 3;

    /** ANOMALY_DETECTED when a normal reading deviates from the median by strictly more than this percentage. */
    private BigDecimal anomalyDeviationPercent = new BigDecimal("25");

    /** Medication-interaction checker id. */
    private String interactionProvider = PROVIDER_RULES;

    /** Overdue follow-ups older than this many days before the clinic's today are left alone (min 1). */
    private int followUpLookbackDays = 30;

    /** The configured provider id, trimmed and lower case; {@code rules} when unset. */
    public String interactionProviderId() {
        String id = interactionProvider == null ? "" : interactionProvider.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? PROVIDER_RULES : id;
    }
}
