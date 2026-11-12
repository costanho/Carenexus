package com.carenexus.api.common.exception;

/**
 * Thrown when the request itself is wrong (missing or invalid value that Bean Validation cannot express).
 * GlobalExceptionHandler maps it to HTTP 400 and returns the message as-is, so the message must be safe to show
 * to end users and stay under 120 characters, e.g. "Appointment time is in the past".
 */
public class BadRequestException extends RuntimeException {

    private final String code;

    public BadRequestException(String message) {
        this(null, message);
    }

    /**
     * @param code stable machine-readable reason returned as {@code code} in the error envelope (may be null)
     */
    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
