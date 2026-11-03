package com.carenexus.api.common.exception;

/**
 * Thrown when a request conflicts with the current state of a resource
 * (duplicate entry, double booking, illegal state transition, ...).
 * GlobalExceptionHandler maps it to HTTP 409 and returns the message as-is, so the
 * message must be safe to show to end users, e.g. "This time slot is already booked".
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
