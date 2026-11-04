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
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.core.model.Caregiver;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.service.AuditService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AuthService {

    private static final int REFRESH_TOKEN_DAYS = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    // Kept under 120 chars: the frontend falls back to a generic error for longer messages.
    static final String PENDING_DOCTOR_MESSAGE =
            "Your clinician account is pending verification or has been deactivated. Please contact support.";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final CaregiverRepository caregiverRepository;
    private final DependentAccessRepository dependentAccessRepository;
    private final SystemIdGenerator systemIdGenerator;
    private final LoginAttemptThrottle loginAttemptThrottle;
    private final ApplicationEventPublisher eventPublisher;

    /** Valid hash of a random value, compared against for unknown emails so timing does not reveal registered emails. */
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    private String normalizeRole(String role) {
        if (role == null) return null;
        String lower = role.toLowerCase();
        if (lower.equals("caregiver")) {
            return "proxy";
        }
        return lower;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Roles that may be self-registered through the public endpoint. ADMIN is never accepted here;
     * admins are provisioned out of band. "proxy" is the frontend name for CAREGIVER.
     */
    private static String resolveSelfRegistrationRole(String role) {
        String upper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PATIENT" -> "PATIENT";
            case "DOCTOR" -> "DOCTOR";
            case "CAREGIVER", "PROXY" -> "CAREGIVER";
            default -> throw new RuntimeException("Invalid role. Allowed roles are patient, doctor and proxy.");
        };
    }

    private static String requireName(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new RuntimeException(label + " is required");
        }
        if (trimmed.length() > 100) {
            throw new RuntimeException(label + " must be at most 100 characters");
        }
        return trimmed;
    }

    private static void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new RuntimeException("Password is required");
        }
        if (password.length() < 8 || !HAS_LETTER.matcher(password).find() || !HAS_DIGIT.matcher(password).find()) {
            throw new RuntimeException("Password must be at least 8 characters and contain at least one letter and one number");
        }
        // BCrypt only uses the first 72 bytes; reject longer input instead of silently truncating it.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new RuntimeException("Password must be at most 72 characters");
        }
    }

    /**
     * Register a new user account with role-based cascading.
     * Runs in a single transaction: if any step fails (including the caregiver link), nothing is created.
     */
    public AuthResponse register(RegisterRequest request) {
        if (request == null) {
            throw new RuntimeException("Registration details are required");
        }
        String firstName = requireName(request.getFirstName(), "First name");
        String lastName = requireName(request.getLastName(), "Last name");

        String email = normalizeEmail(request.getEmail());
        if (email == null || email.isEmpty()) {
            throw new RuntimeException("Email is required");
        }
        if (email.length() > 150 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new RuntimeException("Please enter a valid email address");
        }

        validatePassword(request.getPassword());

        if (request.getRole() == null || request.getRole().isBlank()) {
            throw new RuntimeException("Role is required");
        }
        String role = resolveSelfRegistrationRole(request.getRole());

        String phone = request.getPhone() == null || request.getPhone().isBlank() ? null : request.getPhone().trim();
        if (phone != null && phone.length() > 20) {
            throw new RuntimeException("Phone number must be at most 20 characters");
        }

        String caregiverSystemId = request.getCaregiverSystemId() == null || request.getCaregiverSystemId().isBlank()
                ? null : request.getCaregiverSystemId().trim();
        if (caregiverSystemId != null && !"PATIENT".equals(role)) {
            throw new RuntimeException("A caregiver link can only be supplied when registering a patient");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }
        if (phone != null && userRepository.findByPhone(phone).isPresent()) {
            throw new RuntimeException("Phone number already registered");
        }

        // Validate the caregiver link before creating anything
        if (caregiverSystemId != null) {
            boolean validCaregiver = userRepository.findBySystemId(caregiverSystemId)
                    .filter(u -> "CAREGIVER".equals(u.getRole()) && Boolean.TRUE.equals(u.getIsActive()))
                    .isPresent()
                    && caregiverRepository.findBySystemId(caregiverSystemId).isPresent();
            if (!validCaregiver) {
                throw new RuntimeException("Invalid caregiver: no active proxy/caregiver account exists with that ID");
            }
        }

        // Self-registered doctors start pending verification (inactive) until an administrator approves them
        boolean isDoctor = "DOCTOR".equals(role);

        String passwordHash = passwordEncoder.encode(request.getPassword());
        String systemId = systemIdGenerator.generateSystemId(role);
        User user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phone(phone)
                .passwordHash(passwordHash)
                .role(role)
                .systemId(systemId)
                .isActive(!isDoctor)
                .build();

        User savedUser = userRepository.save(user);

        switch (role) {
            case "PATIENT" -> {
                Patient patient = Patient.builder()
                        .systemId(savedUser.getSystemId())
                        .healthStatus("STABLE")
                        .build();
                patientRepository.save(patient);

                if (caregiverSystemId != null) {
                    String relationship = request.getRelationship() == null || request.getRelationship().isBlank()
                            ? "caregiver" : request.getRelationship().trim();
                    if (relationship.length() > 80) {
                        throw new RuntimeException("Relationship must be at most 80 characters");
                    }
                    DependentAccess access = DependentAccess.builder()
                            .patientSystemId(savedUser.getSystemId())
                            .caregiverSystemId(caregiverSystemId)
                            // The patient registering is the one granting access
                            .authorizedBySystemId(savedUser.getSystemId())
                            .relationship(relationship)
                            .accessLevel("VIEW_ONLY")
                            .isActive(true)
                            .build();
                    dependentAccessRepository.save(access);
                }
            }
            case "DOCTOR" -> {
                Doctor doctor = Doctor.builder()
                        .systemId(savedUser.getSystemId())
                        .isActive(false)
                        .build();
                doctorRepository.save(doctor);
            }
            case "CAREGIVER" -> {
                Caregiver caregiver = Caregiver.builder()
                        .systemId(savedUser.getSystemId())
                        .build();
                caregiverRepository.save(caregiver);
            }
            default -> throw new RuntimeException("Invalid role. Allowed roles are patient, doctor and proxy.");
        }

        String message = isDoctor
                ? "Account created. Your clinician account is pending verification; you will be able to sign in once an administrator has approved it."
                : "Account created successfully. Please log in.";

        // Return user info only — no tokens until login
        return AuthResponse.builder()
                .message(message)
                .user(toAuthUser(savedUser))
                .build();
    }

    /**
     * Create an ACTIVE users row with the same validation as self-registration (names, email format, password
     * strength, phone length). Duplicate email or phone gives ConflictException (409).
     *
     * It only creates the users row: the caller creates the role profile (patients/doctors/caregivers row) and any
     * links, and must already be in a transaction (Propagation.MANDATORY) so that everything rolls back together.
     * Used by CaregiverService.createDependent. ADMIN accounts are never created here.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User createActiveUserAccount(String firstName, String lastName, String rawEmail, String password,
                                        String rawPhone, String role) {
        String validFirstName = requireName(firstName, "First name");
        String validLastName = requireName(lastName, "Last name");

        String email = normalizeEmail(rawEmail);
        if (email == null || email.isEmpty()) {
            throw new RuntimeException("Email is required");
        }
        if (email.length() > 150 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new RuntimeException("Please enter a valid email address");
        }
        validatePassword(password);

        String resolvedRole = resolveSelfRegistrationRole(role);

        String phone = rawPhone == null || rawPhone.isBlank() ? null : rawPhone.trim();
        if (phone != null && phone.length() > 20) {
            throw new RuntimeException("Phone number must be at most 20 characters");
        }

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email already registered");
        }
        if (phone != null && userRepository.findByPhone(phone).isPresent()) {
            throw new ConflictException("Phone number already registered");
        }

        User user = User.builder()
                .firstName(validFirstName)
                .lastName(validLastName)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(password))
                .role(resolvedRole)
                .systemId(systemIdGenerator.generateSystemId(resolvedRole))
                .isActive(true)
                .build();
        return userRepository.save(user);
    }

    /**
     * Authenticate user with email and password.
     * Failure paths only write audit rows; noRollbackFor keeps them from being rolled back with the
     * thrown exception even if AuditService joins this transaction.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = normalizeEmail(request == null ? null : request.getEmail());
        String password = request == null ? null : request.getPassword();
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw new RuntimeException("Email and password are required");
        }

        loginAttemptThrottle.checkAllowed(email, ipAddress);

        Optional<User> found = findUserByEmail(email);
        if (found.isEmpty()) {
            // Spend the same bcrypt time as a real check so response timing does not reveal registered emails
            passwordEncoder.matches(password, dummyPasswordHash);
            throw failLogin(email, ipAddress, userAgent, "Unknown account");
        }
        User user = found.get();

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw failLogin(email, ipAddress, userAgent, "Invalid password");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            loginAttemptThrottle.recordSuccess(email, ipAddress);
            if ("DOCTOR".equals(user.getRole())
                    && doctorRepository.findBySystemId(user.getSystemId()).map(d -> !Boolean.TRUE.equals(d.getIsActive())).orElse(true)) {
                auditFailedLogin(email, ipAddress, userAgent, "Clinician pending verification");
                throw new RuntimeException(PENDING_DOCTOR_MESSAGE);
            }
            auditFailedLogin(email, ipAddress, userAgent, "Account inactive");
            throw new RuntimeException("Account is inactive");
        }

        loginAttemptThrottle.recordSuccess(email, ipAddress);

        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());
        String rawRefreshToken = createRefreshToken(user.getSystemId(), request.getDeviceType(), request.getDeviceInfo());

        auditService.logLogin(user.getSystemId(), ipAddress, userAgent);

        return buildAuthResponse(user, accessToken, rawRefreshToken);
    }

    private Optional<User> findUserByEmail(String normalizedEmail) {
        Optional<User> exact = userRepository.findByEmail(normalizedEmail);
        return exact.isPresent() ? exact : userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(normalizedEmail);
    }

    /** Unknown account and wrong password get the same 401 and text (no account enumeration). */
    private RuntimeException failLogin(String email, String ipAddress, String userAgent, String reason) {
        loginAttemptThrottle.recordFailure(email, ipAddress);
        auditFailedLogin(email, ipAddress, userAgent, reason);
        return new InvalidCredentialsException();
    }

    private void auditFailedLogin(String email, String ipAddress, String userAgent, String reason) {
        try {
            auditService.logFailedLogin(email, ipAddress, userAgent, reason);
        } catch (RuntimeException e) {
            log.warn("Could not write failed-login audit entry: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Create a refresh token. Only its SHA-256 hash is stored; the raw value is returned once to the client.
     */
    private String createRefreshToken(String systemId, String deviceType, String deviceInfo) {
        String raw = generateRandomToken();

        RefreshToken refreshToken = RefreshToken.builder()
                .systemId(systemId)
                .tokenHash(sha256Hex(raw))
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS))
                .isRevoked(false)
                .deviceType(deviceType)
                .deviceInfo(deviceInfo)
                .build();

        refreshTokenRepository.save(refreshToken);
        return raw;
    }

    /**
     * Exchange a refresh token for a new access token AND a new refresh token (rotation).
     * Presenting a token that was already rotated or revoked is treated as theft: every refresh token
     * of that user is revoked. noRollbackFor keeps that revocation when the 401 is thrown.
     */
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        String raw = request == null ? null : request.getRefreshToken();
        if (raw == null || raw.isBlank()) {
            throw new BadCredentialsException("Refresh token is required");
        }

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(raw.trim()))
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (Boolean.TRUE.equals(refreshToken.getIsRevoked())) {
            log.warn("Refresh token reuse detected for {}; revoking all refresh tokens", refreshToken.getSystemId());
            revokeAllForUser(refreshToken.getSystemId());
            throw new BadCredentialsException("Refresh token reuse detected. Please sign in again.");
        }

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            revoke(refreshToken);
            throw new BadCredentialsException("Refresh token has expired");
        }

        Optional<User> found = userRepository.findBySystemId(refreshToken.getSystemId());
        if (found.isEmpty() || !Boolean.TRUE.equals(found.get().getIsActive())) {
            revokeAllForUser(refreshToken.getSystemId());
            throw new BadCredentialsException("User account is inactive");
        }
        User user = found.get();

        // Rotate: the presented token stops working, a new one is issued for the same device
        revoke(refreshToken);
        String newRawRefreshToken = createRefreshToken(user.getSystemId(), refreshToken.getDeviceType(), refreshToken.getDeviceInfo());
        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

        return buildAuthResponse(user, accessToken, newRawRefreshToken);
    }

    /**
     * Logout one session: revoke the presented refresh token. Unknown or already revoked tokens are ignored
     * so logout is always safe to call. When a token row is found, publishes {@link UserLoggedOutEvent}
     * (ends the user's open call participation).
     */
    public void logoutRefreshToken(String rawRefreshToken, String ipAddress) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(sha256Hex(rawRefreshToken.trim()))
                .filter(token -> !Boolean.TRUE.equals(token.getIsRevoked()))
                .ifPresent(token -> {
                    revoke(token);
                    auditService.logLogout(token.getSystemId(), ipAddress);
                    eventPublisher.publishEvent(new UserLoggedOutEvent(token.getSystemId()));
                });
    }

    /**
     * Logout everywhere - revoke every active refresh token of the user. Publishes {@link UserLoggedOutEvent}.
     */
    public void logout(String systemId, String ipAddress) {
        revokeAllForUser(systemId);
        auditService.logLogout(systemId, ipAddress);
        eventPublisher.publishEvent(new UserLoggedOutEvent(systemId));
    }

    private void revokeAllForUser(String systemId) {
        List<RefreshToken> refreshTokens = refreshTokenRepository.findBySystemIdAndIsRevokedFalse(systemId);
        refreshTokens.forEach(this::revoke);
    }

    private void revoke(RefreshToken token) {
        token.setIsRevoked(true);
        token.setRevokedAt(LocalDateTime.now());
        refreshTokenRepository.save(token);
    }

    /**
     * Get current user information by system ID
     */
    public AuthResponse.AuthUser getCurrentUserBySystemId(String systemId) {
        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return toAuthUser(user);
    }

    private AuthResponse buildAuthResponse(User user, String accessToken, String rawRefreshToken) {
        return AuthResponse.builder()
                .tokens(AuthResponse.AuthTokens.builder()
                        .accessToken(accessToken)
                        .refreshToken(rawRefreshToken)
                        .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                        .build())
                .user(toAuthUser(user))
                .build();
    }

    private AuthResponse.AuthUser toAuthUser(User user) {
        return AuthResponse.AuthUser.builder()
                .user_id(user.getUserId())
                .system_id(user.getSystemId())
                .name(user.getFirstName() + " " + user.getLastName())
                .email(user.getEmail())
                .role(normalizeRole(user.getRole()))
                .build();
    }

    /** 256 bits from a CSPRNG, URL-safe. */
    private static String generateRandomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
