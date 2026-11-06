package com.carenexus.api.common.config;

import com.carenexus.api.auth.config.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** Comma-separated list of browser origins allowed to call the API (no wildcard). */
    @Value("${carenexus.cors.allowed-origins:http://localhost:8081,http://localhost:19006}")
    private List<String> allowedOrigins;

    /** Swagger/OpenAPI paths are only public when springdoc is enabled (local/docker profiles). */
    @Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = allowedOrigins.stream()
                .map(String::trim)
                .filter(o -> !o.isEmpty() && !o.equals("*"))
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        config.setExposedHeaders(List.of("Content-Disposition"));
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
            // Missing/invalid/expired token -> 401 (lets the client refresh); authenticated but forbidden -> 403
            .exceptionHandling(e -> e
                .authenticationEntryPoint((request, response, ex) ->
                    writeJsonError(response, request.getRequestURI(), HttpStatus.UNAUTHORIZED, "Authentication required"))
                .accessDeniedHandler((request, response, ex) ->
                    writeJsonError(response, request.getRequestURI(), HttpStatus.FORBIDDEN,
                            "You don't have permission to access this resource"))
            )
            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no token needed
                .requestMatchers(publicPaths.toArray(String[]::new)).permitAll()
                // Role gates (first match wins; controllers still do their own self/ownership checks)
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Doctor directory used by patients and caregivers for booking: any signed-in user
                .requestMatchers(HttpMethod.GET,
                        "/api/doctors/profile/list/active",
                        "/api/doctors/profile/public/**",
                        "/api/doctors/profile/search/**",
                        "/api/doctors/profile/filter/**",
                        "/api/doctors/profile/available",
                        "/api/doctors/profile/unavailable").authenticated()
                .requestMatchers("/api/doctors/**").hasAnyRole("DOCTOR", "ADMIN")
                .requestMatchers("/api/caregiver/**").hasRole("CAREGIVER")
                .requestMatchers("/api/patients/me/caregivers", "/api/patients/me/caregivers/**").hasAnyRole("PATIENT", "ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // Add JWT filter before username/password filter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, String path, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"timestamp\":\"" + LocalDateTime.now()
                + "\",\"status\":" + status.value()
                + ",\"error\":\"" + status.getReasonPhrase()
                + "\",\"message\":\"" + message
                + "\",\"path\":\"" + jsonEscape(path) + "\"}");
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
