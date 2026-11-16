package com.carenexus.api.ai.provider;

/**
 * Selected by {@code carenexus.ai.provider=disabled}: automated screening is switched off. Automatic screenings are
 * skipped and a doctor's manual run answers 503 {@code PROVIDER_UNAVAILABLE}. Not a stand-in for a real engine (it
 * pretends nothing), so it is accepted in every environment.
 */
public class DisabledRiskDetectionProvider implements RiskDetectionProvider {

    public static final String MODEL_VERSION = "disabled";

    @Override
    public String modelVersion() {
        return MODEL_VERSION;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public DetectionResult assess(DetectionInput input) {
        throw new IllegalStateException("Automated risk screening is switched off");
    }
}
