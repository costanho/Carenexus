package com.carenexus.api.common.exception;

/**
 * Thrown when a request conflicts with the current state of a resource
 * (duplicate entry, double booking, illegal state transition, ...).
 * GlobalExceptionHandler maps it to HTTP 409 and returns the message as-is, so the
 * message must be safe to show to end users, e.g. "This time slot is already booked".
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    /**
     * @param code stable machine-readable reason returned as {@code code} in the error envelope
     *             (for example IDEMPOTENCY_IN_PROGRESS); may be null
     */
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
