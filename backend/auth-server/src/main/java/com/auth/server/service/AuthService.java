package com.auth.server.service;

import com.auth.server.config.JwtUtil;
import com.auth.server.dto.request.LoginRequest;
import com.auth.server.dto.request.TokenRefreshRequest;
import com.auth.server.dto.response.AuthResponse;
import com.auth.server.model.RefreshToken;
import com.auth.server.model.User;
import com.auth.server.repository.RefreshTokenRepository;
import com.auth.server.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    /**
     * Authenticate user with email/phone and password
     * Returns JWT + Refresh token if credentials are valid
     */
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        try {
            // Find user by email
            User user = userRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new RuntimeException("Invalid credentials"));

            // Verify password
            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                auditService.logFailedLogin(request.getEmail(), ipAddress, userAgent, "Invalid password");
                throw new RuntimeException("Invalid credentials");
            }

            // Check if account is active
            if (!user.getIsActive()) {
                auditService.logFailedLogin(request.getEmail(), ipAddress, userAgent, "Account inactive");
                throw new RuntimeException("Account is inactive");
            }

            // Generate JWT token
            String accessToken = jwtUtil.generateToken(user.getUserId(), user.getEmail(), user.getRole());

            // Create and save refresh token
            RefreshToken refreshToken = createRefreshToken(user, request.getDeviceType(), request.getDeviceInfo());

            // Log successful login
            auditService.logLogin(user.getUserId(), ipAddress, userAgent);

            // Build response
            return AuthResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken.getTokenHash())
                    .tokenType("Bearer")
                    .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                    .userId(user.getUserId())
                    .firstName(user.getFirstName())
                    .lastName(user.getLastName())
                    .role(user.getRole())
                    .build();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    /**
     * Create a new refresh token (stored as hash in DB)
     */
    private RefreshToken createRefreshToken(User user, String deviceType, String deviceInfo) {
        // In production, hash the token before storing
        String tokenHash = generateRandomToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .userId(user.getUserId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .isRevoked(false)
                .deviceType(deviceType)
                .deviceInfo(deviceInfo)
                .build();

        return refreshTokenRepository.save(refreshToken);
    }

    /**
     * Refresh JWT token using refresh token
     */
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        // Find valid refresh token
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(request.getRefreshToken())
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        // Check if revoked
        if (refreshToken.getIsRevoked()) {
            throw new RuntimeException("Refresh token has been revoked");
        }

        // Check if expired
        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Refresh token has expired");
        }

        // Get user
        User user = userRepository.findByUserIdAndIsActiveTrue(refreshToken.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found or inactive"));

        // Generate new JWT
        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(request.getRefreshToken())
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                .userId(user.getUserId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .build();
    }

    /**
     * Logout - revoke refresh token
     */
    public void logout(Integer userId, String ipAddress) {
        Optional<RefreshToken> refreshToken = refreshTokenRepository.findByUserIdAndIsRevokedFalse(userId);
        refreshToken.ifPresent(token -> {
            token.setIsRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(token);
        });

        // Log logout
        auditService.logLogout(userId, ipAddress);
    }

    /**
     * Generate random token string (should be hashed before storing)
     */
    private String generateRandomToken() {
        return java.util.UUID.randomUUID().toString();
    }
}
