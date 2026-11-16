package com.carenexus.api.auth.service;

/**
 * Thrown when the login throttle blocks an email+IP pair. AuthController maps it to HTTP 429.
 */
public class TooManyLoginAttemptsException extends RuntimeException {
    public TooManyLoginAttemptsException(String message) {
        super(message);
    }
}
