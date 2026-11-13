package com.carenexus.api.ai.detection.model;

/**
 * What one screening run produced (api/be-ai-detection.md 2.4). The message is the fixed sentence the web shows as
 * received.
 */
public enum DetectionRunOutcome {

    /** The screening reached MEDIUM or higher and a new detection was stored (and its alert raised). */
    DETECTION_CREATED("Screening flagged a possible risk. Review the result below."),
    /** The screening reached MEDIUM or higher, but the patient already has a detection for that primary condition today. */
    DETECTION_EXISTS("Screening flagged a risk that was already recorded today."),
    /** There was data to screen and nothing reached MEDIUM. */
    NO_FINDING("Screening found nothing that needs review."),
    /** No reading in the last 7 days and no symptom log in the last 14 days; the provider was not called. */
    NO_DATA("There are no recent symptoms or readings to screen.");

    private final String message;

    DetectionRunOutcome(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
