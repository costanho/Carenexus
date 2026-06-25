package com.auth.server.controller;

import com.auth.server.config.UserContext;
import com.auth.server.dto.request.LoginRequest;
import com.auth.server.dto.request.TokenRefreshRequest;
import com.auth.server.dto.response.AuthResponse;
import com.auth.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserContext userContext;

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
     * POST /api/auth/refresh
     * Refresh JWT token using refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody TokenRefreshRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/logout
     * Logout and revoke refresh token
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        Integer userId = userContext.getCurrentUserId();
        if (userId != null) {
            String ipAddress = getClientIpAddress(httpRequest);
            authService.logout(userId, ipAddress);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Extract client IP address from request
     * Handles X-Forwarded-For header (for proxies/load balancers)
     */
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
