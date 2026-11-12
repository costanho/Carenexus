package com.carenexus.api.common.exception;

import com.carenexus.api.auth.service.TooManyLoginAttemptsException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps exceptions to JSON error responses of the shape
 * {@code {timestamp, status, error, message, path}}. The frontend reads {@code message}.
 *
 * Rules:
 * - Messages are echoed to the client ONLY for exceptions the application writes itself:
 *   plain {@link RuntimeException}, {@link IllegalArgumentException}, {@link AuthorizationException},
 *   {@link ResourceNotFoundException}, {@link ConflictException} (and @ResponseStatus-annotated app exceptions).
 * - Everything else (DataAccessException, SQL errors, NPE, ClassCastException, ...) returns a generic
 *   "An unexpected error occurred" with status 500, and is logged server-side with its stack trace.
 * - Expected 4xx are logged at WARN without stack traces. Request bodies, headers and tokens are never logged.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    static final String GENERIC_ERROR = "An unexpected error occurred";

    // ==================== APPLICATION EXCEPTIONS ====================

    /**
     * AuthorizationException (403 Forbidden)
     */
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthorizationException(AuthorizationException ex, HttpServletRequest request) {
        logClientError(HttpStatus.FORBIDDEN, request, ex.getMessage());
        ResponseEntity<Map<String, Object>> response = build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
        if (ex.getResourceType() != null) {
            response.getBody().put("resourceType", ex.getResourceType());
        }
        if (ex.getResourceId() != null) {
            response.getBody().put("resourceId", ex.getResourceId());
        }
        return response;
    }

    /**
     * ResourceNotFoundException (404 Not Found)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_FOUND, request, ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * ConflictException (409 Conflict)
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex, HttpServletRequest request) {
        logClientError(HttpStatus.CONFLICT, request, ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Login throttle (429 Too Many Requests)
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex, HttpServletRequest request) {
        logClientError(HttpStatus.TOO_MANY_REQUESTS, request, ex.getMessage());
        return build(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    // ==================== SECURITY ====================

    /**
     * Spring Security AuthenticationException (401 Unauthorized). The exception text is not echoed.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        logClientError(HttpStatus.UNAUTHORIZED, request, ex.getClass().getSimpleName());
        return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
    }

    /**
     * Spring Security AccessDeniedException raised inside a controller (403 Forbidden).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.FORBIDDEN, request, ex.getClass().getSimpleName());
        return build(HttpStatus.FORBIDDEN, "You don't have permission to access this resource", request);
    }

    // ==================== DATABASE ====================

    /**
     * Constraint violations (409 Conflict). Never echoes SQL; maps known constraints to safe text.
     * The words "email" / "phone" are kept because the frontend's parseApiError keys on them.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
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
    public ResponseEntity<Map<String, Object>> handleNoRoute(Exception ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_FOUND, request, "no route");
        return build(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.METHOD_NOT_ALLOWED, request, ex.getMethod());
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        logClientError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, request, String.valueOf(ex.getContentType()));
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        logClientError(HttpStatus.NOT_ACCEPTABLE, request, "not acceptable");
        return build(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Jackson messages can contain fragments of the body: log the type only.
        Throwable cause = ex.getMostSpecificCause();
        logClientError(HttpStatus.BAD_REQUEST, request, "unreadable body (" + cause.getClass().getSimpleName() + ")");
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleGenericTypeMismatch(TypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "request";
        String message = "Invalid value for parameter '" + name + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Missing required parameter '" + ex.getParameterName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String message = "Missing required header '" + ex.getHeaderName() + "'";
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    /**
     * Other binding errors. A missing @RequestAttribute("systemId"/"userRole") means the JWT filter did not
     * authenticate the request, so that case is reported as 401.
     */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> handleBinding(ServletRequestBindingException ex, HttpServletRequest request) {
        String text = ex.getMessage() != null ? ex.getMessage() : "";
        if (text.contains("request attribute")) {
            logClientError(HttpStatus.UNAUTHORIZED, request, "missing request attribute");
            return build(HttpStatus.UNAUTHORIZED, "Authentication required", request);
        }
        logClientError(HttpStatus.BAD_REQUEST, request, ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "Invalid request", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + (fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid value"))
                .orElse("Validation failed");
        logClientError(HttpStatus.BAD_REQUEST, request, message);
        return build(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        logClientError(HttpStatus.PAYLOAD_TOO_LARGE, request, "upload too large");
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file is too large", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex, HttpServletRequest request) {
        logClientError(HttpStatus.BAD_REQUEST, request, "invalid multipart request");
        return build(HttpStatus.BAD_REQUEST, "Invalid file upload request", request);
    }

    // ==================== FALLBACKS ====================

    /**
     * RuntimeException. Only messages written by the application are echoed; any other subclass is internal.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex, HttpServletRequest request) {
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
     * Any other exception (500), unless it is a Spring {@link ErrorResponse} that already carries a 4xx status.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
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

    private ResponseEntity<Map<String, Object>> internalError(Exception ex, HttpServletRequest request) {
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

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (request != null) {
            body.put("path", request.getRequestURI());
        }
        return new ResponseEntity<>(body, status);
    }
}
