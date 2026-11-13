package com.carenexus.api.common.config;

import com.carenexus.api.auth.config.JwtAuthenticationFilter;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.web.CallerMdcFilter;
import com.carenexus.api.common.web.RequestCorrelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Route security. Rules are first-match-wins:
 * <ol>
 *   <li>public: {@code /api/auth/{login,refresh,register,logout}}, {@code POST /api/auth/password/{forgot,reset}},
 *       {@code POST /api/auth/email/{verify,resend}}, {@code POST /api/auth/email-change/confirm},
 *       {@code POST /api/auth/mfa/**} (authenticated by the challenge token in the body), {@code GET /api/legal/**},
 *       health and info;</li>
 *   <li>provider webhooks (no user token, the controller verifies the provider signature):
 *       {@code POST /api/integrations/livekit/webhook}, {@code POST /api/integrations/payments/{provider}/webhook};
 *       every other {@code /api/integrations/**} path is denied;</li>
 *   <li>{@code /actuator/prometheus}: ADMIN, or the static scrape token ({@link MetricsScrapeAuthenticationFilter});
 *       {@code /api/admin/**}: ADMIN;</li>
 *   <li>the doctor directory used for booking ({@code GET /api/doctors/profile/directory},
 *       {@code /public/**} and the legacy {@code /list/active}): any signed-in user;</li>
 *   <li>{@code /api/doctors/**}: DOCTOR or ADMIN, so nothing a patient or caregiver calls may live there;</li>
 *   <li>{@code /api/caregiver/**}: CAREGIVER;</li>
 *   <li>everything else: authenticated, and the services decide (ownership, relationship, caregiver permission).</li>
 * </ol>
 * Errors raised here (401, 403) use the same {@link ErrorResponse} envelope as GlobalExceptionHandler, including the
 * request's correlation id. Request correlation: RequestIdFilter (a servlet filter ahead of this chain) assigns the
 * {@code X-Request-Id}; {@link CallerMdcFilter}, placed right after the JWT filter, adds the caller's system id to the
 * logging context.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** One year, the value browsers expect before they honour HSTS preload rules. */
    private static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /** Comma-separated list of browser origins allowed to call the API (no wildcard). */
    @Value("${carenexus.cors.allowed-origins:http://localhost:8081,http://localhost:19006}")
    private List<String> allowedOrigins;

    /** Swagger/OpenAPI paths are only public when springdoc is enabled (local/docker profiles). */
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    /**
     * Static bearer token a Prometheus server sends to /actuator/prometheus (empty: only an ADMIN token can scrape).
     * A user access token cannot serve a scraper: it expires after 15 minutes and ADMIN sign-in needs MFA.
     */
    @Value("${carenexus.metrics.scrape-token:}")
    private String metricsScrapeToken;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = allowedOrigins.stream()
                .map(String::trim)
                .filter(o -> !o.isEmpty() && !o.equals("*"))
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-Requested-With",
                "Idempotency-Key", "X-Request-Id"));
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "X-Request-Id"));
        // Auth is a bearer header, not cookies
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http)
            throws Exception {
        List<String> publicPaths = new ArrayList<>(List.of(
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/auth/register",
                "/api/auth/logout",
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/info",
                "/error"
        ));
        if (apiDocsEnabled) {
            publicPaths.addAll(List.of(
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/v3/api-docs.yaml",
                    "/webjars/**"
            ));
        }

        http
            // Enable CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // Disable CSRF (stateless bearer-token auth, no cookies)
            .csrf(csrf -> csrf.disable())
            // Stateless session management (no server-side sessions)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // Response headers: no MIME sniffing, no framing, no referrer, HTTPS only once seen over HTTPS
            // (the HSTS header is only written on secure requests).
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
            )
            // Missing/invalid/expired token -> 401 (lets the client refresh); authenticated but forbidden -> 403
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    writeJsonError(request, response, HttpStatus.UNAUTHORIZED, "Authentication required"))
                .accessDeniedHandler((request, response, ex) ->
                    writeJsonError(request, response, HttpStatus.FORBIDDEN,
                            "You don't have permission to access this resource"))
            )
            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no token needed
                .requestMatchers(publicPaths.toArray(String[]::new)).permitAll()
                // Account recovery, email verification, email-change confirmation and MFA challenge steps: the caller
                // has no session yet; each endpoint authenticates the one-time token (or challenge token) it receives.
                .requestMatchers(HttpMethod.POST,
                        "/api/auth/password/forgot",
                        "/api/auth/password/reset",
                        "/api/auth/email/verify",
                        "/api/auth/email/resend",
                        "/api/auth/email-change/confirm",
                        "/api/auth/mfa/**").permitAll()
                // Terms of service / privacy notice texts shown before sign-up
                .requestMatchers(HttpMethod.GET, "/api/legal/**").permitAll()
                // Provider webhooks: no user token; the webhook controllers verify the provider signature.
                // Must stay before the /api/integrations/** denyAll below.
                .requestMatchers(HttpMethod.POST, "/api/integrations/livekit/webhook").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/integrations/payments/*/webhook").permitAll()
                // Metrics scrape: the Prometheus scrape token (MetricsScrapeAuthenticationFilter) or an administrator
                // (health and info stay public above). The scrape token is accepted on this path only.
                .requestMatchers(MetricsScrapeAuthenticationFilter.SCRAPE_PATH)
                        .hasAnyRole("ADMIN", MetricsScrapeAuthenticationFilter.ROLE)
                // Role gates (first match wins; controllers still do their own self/ownership checks)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Doctor directory used by patients and caregivers for booking: any signed-in user.
                // /list/active is the legacy alias the booking hooks still call.
                .requestMatchers(HttpMethod.GET,
                        "/api/doctors/profile/directory",
                        "/api/doctors/profile/directory/**",
                        "/api/doctors/profile/public/**",
                        "/api/doctors/profile/list/active").authenticated()
                .requestMatchers("/api/doctors/**").hasAnyRole("DOCTOR", "ADMIN")
                .requestMatchers("/api/caregiver/**").hasRole("CAREGIVER")
                .requestMatchers("/api/patients/me/caregivers", "/api/patients/me/caregivers/**").hasAnyRole("PATIENT", "ADMIN")
                .requestMatchers("/api/patients/me/caregiver-access", "/api/patients/me/caregiver-access/**").hasAnyRole("PATIENT", "ADMIN")
                // Messaging and notifications: services check membership / relationship / consent on every request
                .requestMatchers("/api/communications/**", "/api/notifications/**").authenticated()
                // Any other integration endpoint is closed
                .requestMatchers("/api/integrations/**").denyAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // Add JWT filter before username/password filter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // Prometheus scrape token, after the JWT filter so it only applies when no user token authenticated
            .addFilterAfter(new MetricsScrapeAuthenticationFilter(metricsScrapeToken), JwtAuthenticationFilter.class)
            // Caller's system id into the logging context, once the JWT filter has authenticated the request
            .addFilterAfter(new CallerMdcFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** Writes the shared error envelope for failures decided by the filter chain (before any controller runs). */
    private void writeJsonError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                                String message) throws IOException {
        ErrorResponse body = ErrorResponse.of(status, message, request.getRequestURI());
        if (body.requestId() == null && request.getAttribute(RequestCorrelation.REQUEST_ATTRIBUTE) instanceof String id) {
            body = body.withRequestId(id);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
