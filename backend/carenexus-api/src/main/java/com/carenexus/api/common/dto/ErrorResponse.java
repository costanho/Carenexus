package com.carenexus.api.common.dto;

import com.carenexus.api.common.web.RequestCorrelation;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The error envelope returned for every non-2xx response (GlobalExceptionHandler, the security entry points and
 * the auth controller all serialise this record). Clients read {@code message}; {@code code} is a stable
 * machine-readable reason for the cases that need one; {@code errors} lists every invalid field of a validation
 * failure; {@code requestId} is the request's correlation id, equal to the {@code X-Request-Id} response header, so
 * a user report can be matched with the server log lines of that request. Optional fields are left out of the JSON
 * when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "ErrorResponse", description = "Error envelope of every non-2xx response")
public record ErrorResponse(
        @Schema(description = "When the error was produced (ISO-8601 instant, UTC)", example = "2026-11-10T09:00:00Z")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp,

        @Schema(description = "HTTP status code", example = "400")
        int status,

        @Schema(description = "HTTP reason phrase", example = "Bad Request")
        String error,

        @Schema(description = "One human-readable sentence, safe to show to the user",
                example = "scheduledAt: must be in the future")
        String message,

        @Schema(description = "Request path", example = "/api/appointments")
        String path,

        @Schema(description = "Stable machine-readable reason, present only where clients branch on it",
                example = "VALIDATION_FAILED", nullable = true)
        String code,

        @Schema(description = "Every invalid field of a validation failure", nullable = true)
        List<FieldError> errors,

        @Schema(description = "Correlation id of the request (same value as the X-Request-Id response header); "
                + "absent only for errors produced outside an HTTP request", example = "3f1c9a6e-0b6d-4a4e-9a51-2c1f0d6b7e10",
                nullable = true)
        String requestId,

        @Schema(description = "Resource type an authorization denial refers to", nullable = true)
        String resourceType,

        @Schema(description = "Resource id an authorization denial refers to", nullable = true)
        Integer resourceId) {

    // Codes in use across the API (CONTRACTS 3.1).
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String ACCOUNT_PENDING_VERIFICATION = "ACCOUNT_PENDING_VERIFICATION";
    public static final String ACCOUNT_REJECTED = "ACCOUNT_REJECTED";
    public static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String MFA_REQUIRED = "MFA_REQUIRED";
    public static final String MFA_SETUP_REQUIRED = "MFA_SETUP_REQUIRED";
    public static final String LEGAL_ACCEPTANCE_REQUIRED = "LEGAL_ACCEPTANCE_REQUIRED";
    public static final String VIDEO_UNAVAILABLE = "VIDEO_UNAVAILABLE";
    public static final String IDEMPOTENCY_IN_PROGRESS = "IDEMPOTENCY_IN_PROGRESS";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String FILE_REJECTED = "FILE_REJECTED";

    /** One invalid field of a validation failure. */
    @Schema(name = "ErrorResponseFieldError", description = "One invalid field")
    public record FieldError(
            @Schema(description = "Field or parameter name", example = "scheduledAt") String field,
            @Schema(description = "Why it is invalid", example = "must be in the future") String message) {
    }

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return of(status, message, path, null, null);
    }

    public static ErrorResponse of(HttpStatus status, String message, String path, String code) {
        return of(status, message, path, code, null);
    }

    /**
     * The envelope for {@code status}. {@code requestId} is the correlation id of the request handled on this thread
     * (the {@code X-Request-Id} response header, see {@link RequestCorrelation}), so every producer of the envelope
     * (GlobalExceptionHandler, the security entry points, controllers) carries it without passing it around.
     */
    public static ErrorResponse of(HttpStatus status, String message, String path, String code, List<FieldError> errors) {
        return new ErrorResponse(Instant.now().truncatedTo(ChronoUnit.MILLIS), status.value(), status.getReasonPhrase(),
                message, path, code, errors == null || errors.isEmpty() ? null : List.copyOf(errors),
                RequestCorrelation.currentRequestId(), null, null);
    }

    /** Copy carrying the request correlation id. */
    public ErrorResponse withRequestId(String id) {
        return new ErrorResponse(timestamp, status, error, message, path, code, errors, id, resourceType, resourceId);
    }

    /** Copy carrying the resource an authorization denial refers to. */
    public ErrorResponse withResource(String type, Integer id) {
        return new ErrorResponse(timestamp, status, error, message, path, code, errors, requestId, type, id);
    }
}
