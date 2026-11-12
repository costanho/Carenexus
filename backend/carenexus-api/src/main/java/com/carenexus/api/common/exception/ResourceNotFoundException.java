package com.carenexus.api.common.exception;

/**
 * Thrown when a requested resource does not exist (or is not visible to the caller).
 * GlobalExceptionHandler maps it to HTTP 404 and returns the message as-is, so the
 * message must be safe to show to end users, e.g. "Appointment not found".
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
