package com.carenexus.api.billing.receipt;

import com.carenexus.api.common.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Keeps the receipt endpoint's error responses the shared JSON envelope with {@code Content-Type: application/json}
 * whatever the request's {@code Accept} header says (a browser asks for {@code text/html}).
 * <p>
 * Every exception raised by {@link InvoiceReceiptController} is handed to the {@link GlobalExceptionHandler} method
 * that would have handled it anyway (same status, message, code, audit and logging); only the content type of its
 * answer is fixed here, so content negotiation cannot turn the error into a 406 or an HTML error page.
 */
@RestControllerAdvice(assignableTypes = InvoiceReceiptController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReceiptErrorAdvice {

    private final GlobalExceptionHandler globalHandler;
    private final ExceptionHandlerMethodResolver resolver;

    public ReceiptErrorAdvice(GlobalExceptionHandler globalHandler) {
        this.globalHandler = globalHandler;
        this.resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handle(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        Method method = resolver.resolveMethodByThrowable(ex);
        if (method == null) {
            throw ex;
        }
        Object result;
        try {
            result = method.invoke(globalHandler, arguments(method, ex, request, response));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failure;
        }
        if (!(result instanceof ResponseEntity<?> entity)) {
            throw ex;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(entity.getHeaders());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return ResponseEntity.status(entity.getStatusCode() == null ? HttpStatus.INTERNAL_SERVER_ERROR
                        : entity.getStatusCode())
                .headers(headers)
                .body(entity.getBody());
    }

    /** Fills the handler's parameters by type: the exception, the request, the response; anything else null. */
    private static Object[] arguments(Method method, Exception ex, HttpServletRequest request,
                                      HttpServletResponse response) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i].isInstance(ex)) {
                args[i] = ex;
            } else if (types[i].isAssignableFrom(HttpServletRequest.class)) {
                args[i] = request;
            } else if (types[i].isAssignableFrom(HttpServletResponse.class)) {
                args[i] = response;
            }
        }
        return args;
    }
}
