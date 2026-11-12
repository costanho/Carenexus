package com.carenexus.api.ai.detection.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Review state of a detection (CHECK {@code ck_disease_detection_review_status}, V48). PENDING_REVIEW is the only
 * state a review may leave; CONFIRMED and REJECTED are terminal. Only CONFIRMED detections reach the patient and
 * caregivers.
 */
public enum DetectionReviewStatus {

    PENDING_REVIEW,
    CONFIRMED,
    REJECTED;

    /** The status named by {@code value} (trimmed, any case), or empty for null, blank or unknown values. */
    public static Optional<DetectionReviewStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DetectionReviewStatus status : values()) {
            if (status.name().equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
