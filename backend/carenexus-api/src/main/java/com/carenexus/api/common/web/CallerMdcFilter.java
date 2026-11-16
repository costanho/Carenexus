package com.carenexus.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Puts the authenticated caller's system id into the MDC ({@code systemId}) for the rest of the request.
 *
 * Runs inside the Spring Security chain right after the JWT filter (SecurityConfig registers it; it is deliberately
 * not a bean, so it is not registered a second time as a servlet filter). The JWT filter stores the caller's system
 * id as the request attribute {@code systemId}; an anonymous request has none and leaves the MDC untouched.
 * The value is removed again when the request leaves this filter; {@link RequestIdFilter} clears it as well.
 */
public class CallerMdcFilter extends OncePerRequestFilter {

    /** Request attribute written by JwtAuthenticationFilter. */
    static final String SYSTEM_ID_ATTRIBUTE = "systemId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object systemId = request.getAttribute(SYSTEM_ID_ATTRIBUTE);
        if (!(systemId instanceof String id) || id.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String previous = MDC.get(RequestCorrelation.MDC_SYSTEM_ID);
        MDC.put(RequestCorrelation.MDC_SYSTEM_ID, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(RequestCorrelation.MDC_SYSTEM_ID);
            } else {
                MDC.put(RequestCorrelation.MDC_SYSTEM_ID, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
