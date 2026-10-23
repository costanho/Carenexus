package com.carenexus.api.auth.service;

import com.carenexus.api.auth.config.JwtUtil;
import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.request.TokenRefreshRequest;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.model.RefreshToken;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.RefreshTokenRepository;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.core.model.Caregiver;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.carenexus.api.common.service.AuditService;

import java.time.LocalDateTime;
import java.util.List;
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
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final CaregiverRepository caregiverRepository;
    private final SystemIdGenerator systemIdGenerator;

    private String normalizeRole(String role) {
        if (role == null) return null;
        String lower = role.toLowerCase();
        if (lower.equals("caregiver")) {
            return "proxy";
        }
        return lower;
    }

    /**
     * Register a new user account with role-based cascading
     * Creates user in users table and automatically cascades to create
     * corresponding role-specific profile (patient/doctor/caregiver) with defaults
     */
    public AuthResponse register(RegisterRequest request) {
        // Validate input
        if (request.getFirstName() == null || request.getFirstName().isEmpty()) {
            throw new RuntimeException("First name is required");
        }
        if (request.getLastName() == null || request.getLastName().isEmpty()) {
            throw new RuntimeException("Last name is required");
        }
        if (request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new RuntimeException("Email is required");
        }
        if (request.getPassword() == null || request.getPassword().isEmpty()) {
            throw new RuntimeException("Password is required");
        }
        if (request.getRole() == null || request.getRole().isEmpty()) {
            throw new RuntimeException("Role is required");
        }

        // Check if email already exists
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new RuntimeException("Email already registered");
        }

        // Create user with system_id
        String passwordHash = passwordEncoder.encode(request.getPassword());
        String systemId = systemIdGenerator.generateSystemId(request.getRole());
        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .passwordHash(passwordHash)
                .role(request.getRole())
                .systemId(systemId)
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        // Auto-cascade: Create role-specific profile with defaults
        switch (request.getRole().toUpperCase()) {
            case "PATIENT":
                Patient patient = Patient.builder()
                        .systemId(savedUser.getSystemId())
                        .healthStatus("STABLE")
                        .build();
                patientRepository.save(patient);
                break;

            case "DOCTOR":
                Doctor doctor = Doctor.builder()
                        .systemId(savedUser.getSystemId())
                        .isActive(true)
                        .build();
                doctorRepository.save(doctor);
                break;

            case "CAREGIVER":
                Caregiver caregiver = Caregiver.builder()
                        .systemId(savedUser.getSystemId())
                        .build();
                caregiverRepository.save(caregiver);
                break;

            case "ADMIN":
                // Admin users don't need additional profiles
                break;

            default:
                throw new RuntimeException("Invalid role: " + request.getRole());
        }

        // Return user info only — no tokens until login
        return AuthResponse.builder()
                .message("Account created successfully. Please log in.")
                .user(AuthResponse.AuthUser.builder()
                        .user_id(savedUser.getUserId())
                        .system_id(savedUser.getSystemId())
                        .name(savedUser.getFirstName() + " " + savedUser.getLastName())
                        .email(savedUser.getEmail())
                        .role(normalizeRole(savedUser.getRole()))
                        .build())
                .build();
    }

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
            String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

            // Create and save refresh token
            RefreshToken refreshToken = createRefreshToken(user, request.getDeviceType(), request.getDeviceInfo());

            // Log successful login
            auditService.logLogin(user.getSystemId(), ipAddress, userAgent);

            // Build response
            return AuthResponse.builder()
                    .tokens(AuthResponse.AuthTokens.builder()
                            .accessToken(accessToken)
                            .refreshToken(refreshToken.getTokenHash())
                            .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                            .build())
                    .user(AuthResponse.AuthUser.builder()
                            .user_id(user.getUserId())
                            .system_id(user.getSystemId())
                            .name(user.getFirstName() + " " + user.getLastName())
                            .email(user.getEmail())
                            .role(normalizeRole(user.getRole()))
                            .build())
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
                .systemId(user.getSystemId())
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
        User user = userRepository.findBySystemId(refreshToken.getSystemId())
                .orElseThrow(() -> new RuntimeException("User not found or inactive"));

        if (!user.getIsActive()) {
            throw new RuntimeException("User account is inactive");
        }

        // Generate new JWT
        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .tokens(AuthResponse.AuthTokens.builder()
                        .accessToken(accessToken)
                        .refreshToken(request.getRefreshToken())
                        .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                        .build())
                .user(AuthResponse.AuthUser.builder()
                        .user_id(user.getUserId())
                        .system_id(user.getSystemId())
                        .name(user.getFirstName() + " " + user.getLastName())
                        .email(user.getEmail())
                        .role(normalizeRole(user.getRole()))
                        .build())
                .build();
    }

    /**
     * Logout - revoke refresh token
     */
    public void logout(String systemId, String ipAddress) {
        List<RefreshToken> refreshTokens = refreshTokenRepository.findBySystemIdAndIsRevokedFalse(systemId);
        refreshTokens.forEach(token -> {
            token.setIsRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
            refreshTokenRepository.save(token);
        });

        // Log logout
        auditService.logLogout(systemId, ipAddress);
    }

    /**
     * Get current user information by system ID
     */
    public AuthResponse.AuthUser getCurrentUserBySystemId(String systemId) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return AuthResponse.AuthUser.builder()
                .user_id(user.getUserId())
                .system_id(user.getSystemId())
                .name(user.getFirstName() + " " + user.getLastName())
                .email(user.getEmail())
                .role(normalizeRole(user.getRole()))
                .build();
    }

    /**
     * Generate random token string (should be hashed before storing)
     */
    private String generateRandomToken() {
        return java.util.UUID.randomUUID().toString();
    }
}
