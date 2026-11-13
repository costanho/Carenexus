package com.carenexus.api.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Typed escape hatch for errors that need a specific status and a machine-readable code, for example
 * {@code new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email address to continue")} or
 * {@code new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", "Payments are temporarily unavailable")}.
 * GlobalExceptionHandler returns the status, the code and the message as-is, so the message must be safe to show
 * to end users.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
