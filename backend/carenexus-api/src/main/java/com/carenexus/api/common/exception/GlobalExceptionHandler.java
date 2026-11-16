package com.carenexus.api.common.exception;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.web.RequestCorrelation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.Errors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Maps exceptions to the JSON error envelope {@link ErrorResponse}:
 * {@code {timestamp, status, error, message, path, requestId}} plus the optional {@code code} and {@code errors}.
 * The frontend reads {@code message}; {@code timestamp} is an ISO instant with {@code Z}; {@code requestId} is the
 * request's correlation id (the {@code X-Request-Id} response header set by RequestIdFilter), which every log line of
 * the request also carries (MDC), so a 500 reported by a user can be found in the server log.
 *
 * Rules:
 * - Messages are echoed to the client ONLY for exceptions the application writes itself:
 *   {@link BadRequestException}, {@link AuthorizationException}, {@link ResourceNotFoundException},
 *   {@link ConflictException}, {@link TooManyRequestsException}, {@link ApiException}, and (legacy bridge) plain
 *   {@link RuntimeException} / {@link IllegalArgumentException} and @ResponseStatus-annotated app exceptions.
 *   New code throws the typed exceptions, never a bare RuntimeException, for client errors.
 * - Bean Validation failures are 400 with code VALIDATION_FAILED, message {@code "<first field>: <message>"} and
 *   every invalid field in {@code errors}.
 * - Everything else (DataAccessException, SQL errors, NPE, ClassCastException, ...) returns a generic
 *   "An unexpected error occurred" with status 500, and is logged server-side with its stack trace.
 * - Expected 4xx are logged at WARN without stack traces. Request bodies, headers and tokens are never logged.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    static final String GENERIC_ERROR = "An unexpected error occurred";
    static final int MAX_MESSAGE_LENGTH = 120;

    // ==================== APPLICATION EXCEPTIONS ====================

    /**
     * AuthorizationException (403 Forbidden)
     */
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ErrorResponse> handleAuthorizationException(AuthorizationException ex, HttpServletRequest request) {
        logClientError(HttpStatus.FORBIDDEN, request, ex.getMessage());
        ErrorResponse body = body(HttpStatus.FORBIDDEN, ex.getMessage(), request, null, null)
                .withResource(ex.getResourceType(), ex.getResourceId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * ResourceNotFoundException (404 Not Found)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_FOUND, request, ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * ConflictException (409 Conflict), with its code when it carries one (for example IDEMPOTENCY_IN_PROGRESS)
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        logClientError(HttpStatus.CONFLICT, request, ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, ex.getCode());
    }

    /**
     * BadRequestException (400 Bad Request)
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        logClientError(HttpStatus.BAD_REQUEST, request, ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, ex.getCode());
    }

    /**
     * ApiException: the status, code and message chosen by the thrower.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();
        logClientError(status, request, (ex.getCode() != null ? ex.getCode() + ": " : "") + ex.getMessage());
        return build(status, ex.getMessage(), request, ex.getCode());
    }

    /**
     * Rate limits, including the login throttle (429 Too Many Requests, code RATE_LIMITED, Retry-After header)
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest request) {
        logClientError(HttpStatus.TOO_MANY_REQUESTS, request, ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request, ErrorResponse.RATE_LIMITED, null));
    }

    // ==================== SECURITY ====================

    /**
     * Spring Security AuthenticationException (401 Unauthorized). The exception text is not echoed.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        logClientError(HttpStatus.UNAUTHORIZED, request, ex.getClass().getSimpleName());
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    /**
     * Spring Security AccessDeniedException raised inside a controller (403 Forbidden).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.FORBIDDEN, request, ex.getClass().getSimpleName());
        return build(HttpStatus.FORBIDDEN, "You don't have permission to access this resource", request);
    }

    // ==================== DATABASE ====================

    /**
     * Constraint violations (409 Conflict). Never echoes SQL; maps known constraints to safe text.
     * The words "email" / "phone" are kept because the frontend's parseApiError keys on them.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        String constraint = findConstraintName(ex);
        log.warn("409 {} {} -> data integrity violation (constraint: {})",
                request.getMethod(), request.getRequestURI(), constraint != null ? constraint : "unknown");

        String c = constraint != null ? constraint.toLowerCase(Locale.ROOT) : "";
        String message;
        if (c.contains("users_email")) {
            message = "An account with this email already exists";
        } else if (c.contains("users_phone")) {
            message = "An account with this phone number already exists";
        } else {
            message = "Conflicting or invalid data";
        }
        return build(HttpStatus.CONFLICT, message, request);
    }

    // ==================== FRAMEWORK 4xx ====================

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoRoute(Exception ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_FOUND, request, "no route");
        return build(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.METHOD_NOT_ALLOWED, request, ex.getMethod());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, request, String.valueOf(ex.getContentType()));
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_ACCEPTABLE, request, "not acceptable");
        return build(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Jackson messages can contain fragments of the body: log the type only.
        Throwable cause = ex.getMostSpecificCause();
        logClientError(HttpStatus.BAD_REQUEST, request, "unreadable body (" + cause.getClass().getSimpleName() + ")");
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleGenericTypeMismatch(TypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "request";
        String message = "Invalid value for parameter '" + name + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Missing required parameter '" + ex.getParameterName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String message = "Missing required header '" + ex.getHeaderName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Other binding errors. A missing @RequestAttribute("systemId"/"userRole") means the JWT filter did not
     * authenticate the request, so that case is reported as 401.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleBinding(ServletRequestBindingException ex, HttpServletRequest request) {
        String text = ex.getMessage() != null ? ex.getMessage() : "";
        if (text.contains("request attribute")) {
            logClientError(HttpStatus.UNAUTHORIZED, request, "missing request attribute");
            return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
        }
        logClientError(HttpStatus.BAD_REQUEST, request, ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
    }

    // ==================== BEAN VALIDATION ====================

    /**
     * {@code @Valid @RequestBody} failures (400, code VALIDATION_FAILED).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return validationFailed(fieldErrorsOf(ex.getBindingResult()), request);
    }

    /**
     * Constraints on controller method parameters ({@code @RequestParam}, {@code @PathVariable}, {@code @RequestHeader}
     * and validated bodies of methods that also constrain other parameters), raised by Spring's built-in method
     * validation (400, code VALIDATION_FAILED).
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof Errors beanErrors) {
                errors.addAll(fieldErrorsOf(beanErrors));
                continue;
            }
            String name = parameterName(result);
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new ErrorResponse.FieldError(name, messageOrDefault(error.getDefaultMessage())));
            }
        }
        return validationFailed(errors, request);
    }

    /**
     * Constraints checked by a {@code @Validated} class (controllers with constrained parameters, services)
     * (400, code VALIDATION_FAILED). The field is the last node of the property path (the parameter or property name).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (ex.getConstraintViolations() != null) {
            for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
                errors.add(new ErrorResponse.FieldError(lastNode(violation.getPropertyPath()),
                        messageOrDefault(violation.getMessage())));
            }
        }
        return validationFailed(errors, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        logClientError(HttpStatus.PAYLOAD_TOO_LARGE, request, "upload too large");
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file is too large", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        logClientError(HttpStatus.BAD_REQUEST, request, "invalid multipart request");
        return build(HttpStatus.BAD_REQUEST, "Invalid file upload request", request);
    }

    // ==================== FALLBACKS ====================

    /**
     * RuntimeException. Only messages written by the application are echoed; any other subclass is internal.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
        ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (responseStatus != null) {
            HttpStatus status = responseStatus.code();
            String message = !responseStatus.reason().isEmpty() ? responseStatus.reason() : ex.getMessage();
            if (status.is4xxClientError()) {
                logClientError(status, request, message);
                return build(status, message, request);
            }
        }

        if (isApplicationMessage(ex)) {
            String message = ex.getMessage();
            // Bridge until every "... not found" throw uses ResourceNotFoundException.
            HttpStatus status = isNotFoundMessage(message) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            logClientError(status, request, message);
            return build(status, message, request);
        }

        return internalError(ex, request);
    }

    /**
     * Any other exception (500), unless it is a Spring {@link org.springframework.web.ErrorResponse} that already
     * carries a 4xx status.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatusCode code = errorResponse.getStatusCode();
            if (code.is4xxClientError()) {
                HttpStatus status = HttpStatus.valueOf(code.value());
                logClientError(status, request, ex.getClass().getSimpleName());
                return build(status, status.getReasonPhrase(), request);
            }
        }
        return internalError(ex, request);
    }

    // ==================== HELPERS ====================

    private ResponseEntity<ErrorResponse> internalError(Exception ex, HttpServletRequest request) {
        log.error("500 {} {} -> {}", request.getMethod(), request.getRequestURI(), ex.getClass().getName(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_ERROR, request);
    }

    private static boolean isApplicationMessage(RuntimeException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return false;
        }
        // new RuntimeException(cause) copies cause.toString() into the message: that is internal text.
        if (ex.getCause() != null && ex.getMessage().equals(ex.getCause().toString())) {
            return false;
        }
        Class<?> type = ex.getClass();
        return type == RuntimeException.class || type == IllegalArgumentException.class;
    }

    private static boolean isNotFoundMessage(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("not found");
    }

    private static String findConstraintName(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve && cve.getConstraintName() != null) {
                return cve.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }

    private void logClientError(HttpStatus status, HttpServletRequest request, String detail) {
        log.warn("{} {} {} -> {}", status.value(), request.getMethod(), request.getRequestURI(), detail);
    }

    private ResponseEntity<ErrorResponse> validationFailed(List<ErrorResponse.FieldError> errors, HttpServletRequest request) {
        List<ErrorResponse.FieldError> sorted = errors.stream()
                .sorted(Comparator.comparing(ErrorResponse.FieldError::field, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ErrorResponse.FieldError::message))
                .toList();
        String message = sorted.isEmpty()
                ? "Validation failed"
                : clip((sorted.get(0).field() != null ? sorted.get(0).field() + ": " : "") + sorted.get(0).message());
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(body(HttpStatus.BAD_REQUEST, message, request, ErrorResponse.VALIDATION_FAILED, sorted));
    }

    private static List<ErrorResponse.FieldError> fieldErrorsOf(Errors errors) {
        List<ErrorResponse.FieldError> result = new ArrayList<>();
        errors.getFieldErrors().forEach(fe ->
                result.add(new ErrorResponse.FieldError(fe.getField(), messageOrDefault(fe.getDefaultMessage()))));
        // Class-level constraints (cross-field rules) have no field: report them under the object name.
        errors.getGlobalErrors().forEach(ge ->
                result.add(new ErrorResponse.FieldError(ge.getObjectName(), messageOrDefault(ge.getDefaultMessage()))));
        return result;
    }

    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        if (name == null) {
            name = result.getMethodParameter().getParameter().getName();
        }
        return name;
    }

    private static String lastNode(Path path) {
        String last = null;
        if (path != null) {
            for (Path.Node node : path) {
                if (node.getName() != null) {
                    last = node.getName();
                }
            }
        }
        return last;
    }

    private static String messageOrDefault(String message) {
        return message != null && !message.isBlank() ? message : "invalid value";
    }

    /** The client-facing message is one sentence under 120 characters. */
    private static String clip(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return build(status, message, request, null);
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request, String code) {
        return ResponseEntity.status(status).body(body(status, message, request, code, null));
    }

    private static ErrorResponse body(HttpStatus status, String message, HttpServletRequest request, String code,
                                      List<ErrorResponse.FieldError> errors) {
        ErrorResponse body = ErrorResponse.of(status, message, request != null ? request.getRequestURI() : null, code, errors);
        if (body.requestId() == null && request != null
                && request.getAttribute(RequestCorrelation.REQUEST_ATTRIBUTE) instanceof String assigned) {
            // The MDC is per thread; the request attribute survives a dispatch that resumed on another thread.
            body = body.withRequestId(assigned);
        }
        return body;
    }
}
