package com.carenexus.api.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Lets a Prometheus server scrape {@code /actuator/prometheus} with a static bearer token
 * ({@code carenexus.metrics.scrape-token}, env {@code METRICS_SCRAPE_TOKEN}) instead of an ADMIN access token, which
 * expires after 15 minutes and needs an interactive MFA sign-in to renew.
 *
 * <p>Only that one path is concerned: a matching token authenticates the request as {@link #PRINCIPAL} with the
 * single authority {@link #AUTHORITY}, which SecurityConfig accepts on the scrape path and nowhere else. A wrong or
 * missing token leaves the request unauthenticated (401 from the entry point). With no token configured the filter
 * does nothing and only an ADMIN token can scrape. The comparison is constant-time; the token is never logged.
 *
 * <p>Not a Spring bean on purpose: SecurityConfig adds it to the security chain only, so it is not also registered as
 * a servlet filter.
 */
public class MetricsScrapeAuthenticationFilter extends OncePerRequestFilter {

    public static final String SCRAPE_PATH = "/actuator/prometheus";
    public static final String ROLE = "METRICS_SCRAPER";
    public static final String AUTHORITY = "ROLE_" + ROLE;
    public static final String PRINCIPAL = "metrics-scraper";
    /** 32 characters, for example {@code openssl rand -hex 32} (64). Shorter values refuse to start. */
    static final int MIN_TOKEN_LENGTH = 32;

    private final byte[] token;

    public MetricsScrapeAuthenticationFilter(String configuredToken) {
        String value = configuredToken == null ? "" : configuredToken.trim();
        if (!value.isEmpty() && value.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("carenexus.metrics.scrape-token (METRICS_SCRAPE_TOKEN) must be at least "
                    + MIN_TOKEN_LENGTH + " characters, for example the output of: openssl rand -hex 32");
        }
        this.token = value.isEmpty() ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return token != null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (token == null) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !SCRAPE_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            byte[] presented = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(token, presented)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        PRINCIPAL, null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        filterChain.doFilter(request, response);
    }
}
