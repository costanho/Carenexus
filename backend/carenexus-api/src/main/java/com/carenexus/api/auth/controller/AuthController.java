package com.carenexus.api.auth.controller;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.request.TokenRefreshRequest;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final UserContext userContext;

    /**
     * POST /api/auth/register
     * Register a patient, doctor (pending verification) or proxy/caregiver account.
     * Returns the created user; no tokens (the client logs in afterwards).
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/auth/login
     * Login with email and password
     * Returns JWT + Refresh token
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        AuthResponse response = authService.login(request, ipAddress, userAgent);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/refresh  body {refreshToken}
     * Returns the same shape as login. The refresh token is rotated: the response carries a NEW
     * refreshToken and the presented one stops working. Invalid/expired/reused tokens -> 401.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) TokenRefreshRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/logout  body {refreshToken} (optional). Public endpoint, always 200.
     * - With a refreshToken: revokes that session's refresh token (works even after the access token expired).
     * - Without a body but authenticated: revokes all of the user's refresh tokens (logout everywhere).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) TokenRefreshRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        String refreshToken = request == null ? null : request.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logoutRefreshToken(refreshToken, ipAddress);
        } else {
            String systemId = userContext.getCurrentUserSystemId();
            if (systemId != null) {
                authService.logout(systemId, ipAddress);
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Refresh-token failures are thrown as Spring AuthenticationExceptions with messages written in
     * AuthService (never token contents), so they are safe to echo. Returned as 401.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("401 {} {} -> {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("error", "Unauthorized");
        body.put("message", ex.getMessage());
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Client IP for audit and login throttling. X-Forwarded-For is NOT read here: proxy headers are only
     * honoured by Tomcat's RemoteIpValve for trusted (internal) proxies via server.forward-headers-strategy,
     * which already rewrites getRemoteAddr(). Reading the header directly would let any client spoof it.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
