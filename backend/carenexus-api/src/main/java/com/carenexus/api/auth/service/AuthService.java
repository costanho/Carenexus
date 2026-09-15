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

        // Create user
        String passwordHash = passwordEncoder.encode(request.getPassword());
        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .passwordHash(passwordHash)
                .role(request.getRole())
                .isActive(true)
                .build();

        User savedUser = userRepository.save(user);

        // Auto-cascade: Create role-specific profile with defaults
        switch (request.getRole().toUpperCase()) {
            case "PATIENT":
                Patient patient = Patient.builder()
                        .userId(savedUser.getUserId())
                        .healthStatus("STABLE")
                        .build();
                patientRepository.save(patient);
                break;

            case "DOCTOR":
                Doctor doctor = Doctor.builder()
                        .userId(savedUser.getUserId())
                        .isActive(true)
                        .build();
                doctorRepository.save(doctor);
                break;

            case "CAREGIVER":
                Caregiver caregiver = Caregiver.builder()
                        .userId(savedUser.getUserId())
                        .build();
                caregiverRepository.save(caregiver);
                break;

            case "ADMIN":
                // Admin users don't need additional profiles
                break;

            default:
                throw new RuntimeException("Invalid role: " + request.getRole());
        }

        // Generate JWT token
        String accessToken = jwtUtil.generateToken(savedUser.getUserId(), savedUser.getEmail(), savedUser.getRole());

        // Create refresh token
        RefreshToken refreshToken = createRefreshToken(savedUser, "web", "Registration");

        // Return response
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getTokenHash())
                .tokenType("Bearer")
                .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                .userId(savedUser.getUserId())
                .firstName(savedUser.getFirstName())
                .lastName(savedUser.getLastName())
                .role(savedUser.getRole())
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
