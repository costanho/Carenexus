package com.carenexus.api.ai.detection.service;

/**
 * The risk provider could not produce a result: it is switched off ({@link Reason#DISABLED}), threw or returned an
 * unusable result ({@link Reason#FAILED}), or did not answer within the assessment timeout
 * ({@link Reason#TIMEOUT}). Nothing is stored when this is thrown. The message never carries clinical values.
 */
public class RiskAssessmentException extends RuntimeException {

    public enum Reason { DISABLED, FAILED, TIMEOUT }

    private final Reason reason;

    public RiskAssessmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RiskAssessmentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
