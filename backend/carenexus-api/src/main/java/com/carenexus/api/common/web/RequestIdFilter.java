package com.carenexus.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter of every request (servlet filter with the highest precedence, so it runs before the Spring Security
 * chain and covers actuator, webhooks and requests the security chain refuses).
 *
 * <ol>
 *   <li>Takes the caller's {@code X-Request-Id} when it is well formed ({@link RequestCorrelation#isWellFormed}),
 *       otherwise generates a UUID.</li>
 *   <li>Writes it on the response before anything else can commit the response.</li>
 *   <li>Puts it into the MDC under {@code requestId} for the duration of the request and removes it afterwards
 *       (together with {@code systemId}, which {@link CallerMdcFilter} adds once the caller is authenticated), so
 *       pooled threads never carry an id into the next request.</li>
 * </ol>
 *
 * The id is also kept as a request attribute: an error dispatch ({@code /error}) or an async dispatch of the same
 * request runs this filter again and reuses the same id instead of generating a second one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolve(request);
        request.setAttribute(RequestCorrelation.REQUEST_ATTRIBUTE, requestId);
        response.setHeader(RequestCorrelation.HEADER, requestId);

        String previousRequestId = MDC.get(RequestCorrelation.MDC_REQUEST_ID);
        String previousSystemId = MDC.get(RequestCorrelation.MDC_SYSTEM_ID);
        MDC.put(RequestCorrelation.MDC_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            restore(RequestCorrelation.MDC_REQUEST_ID, previousRequestId);
            restore(RequestCorrelation.MDC_SYSTEM_ID, previousSystemId);
        }
    }

    /** Error dispatches (for example a container 413/500 rendered on /error) are correlated too. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** Async dispatches resume on another thread: the MDC has to be set there as well. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static String resolve(HttpServletRequest request) {
        Object assigned = request.getAttribute(RequestCorrelation.REQUEST_ATTRIBUTE);
        if (assigned instanceof String existing && RequestCorrelation.isWellFormed(existing)) {
            return existing;
        }
        String supplied = request.getHeader(RequestCorrelation.HEADER);
        if (supplied != null) {
            supplied = supplied.trim();
        }
        return RequestCorrelation.isWellFormed(supplied) ? supplied : RequestCorrelation.newRequestId();
    }

    /**
     * Puts back what the MDC held before this request: nothing for a normal request on a pooled thread, the outer
     * request's values for a nested (error or include) dispatch on the same thread.
     */
    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }
}
