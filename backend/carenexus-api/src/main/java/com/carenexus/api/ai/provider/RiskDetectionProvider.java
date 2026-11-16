package com.carenexus.api.ai.provider;

/**
 * Seam for the disease-risk screening engine (CONTRACTS.v2 section 9). The launch implementation is
 * {@link RuleBasedRiskDetectionProvider} ({@code rules-v1}); a hosted model is plugged in by adding an adapter and
 * selecting it with {@code carenexus.ai.provider} in {@code AiDetectionConfig}.
 * <p>
 * Implementations receive only the de-identified {@link DetectionInput} and must not touch the database. The caller
 * runs {@link #assess} with a timeout ({@code carenexus.ai.assessment-timeout-ms}) on a worker thread, so an
 * implementation may block on network I/O but must be safe to interrupt.
 */
public interface RiskDetectionProvider {

    /** Version stored with every detection the provider produces, for example {@code rules-v1}. */
    String modelVersion();

    /** False when screening is switched off; {@link #assess} must then not be called. */
    boolean isConfigured();

    /**
     * Screens one patient snapshot.
     *
     * @throws RuntimeException when the assessment cannot be completed (the caller stores nothing)
     */
    DetectionResult assess(DetectionInput input);
}
