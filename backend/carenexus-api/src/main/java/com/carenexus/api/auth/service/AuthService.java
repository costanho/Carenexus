package com.carenexus.api.auth.service;

import com.carenexus.api.auth.config.JwtUtil;
import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.request.TokenRefreshRequest;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.dto.response.CurrentUserResponse;
import com.carenexus.api.auth.dto.validation.PasswordPolicy;
import com.carenexus.api.auth.model.RefreshToken;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.provider.HumanVerification;
import com.carenexus.api.auth.repository.AuthDoctorRepository;
import com.carenexus.api.auth.repository.RefreshTokenRepository;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.common.event.UserLoggedInEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.common.spi.LoginGate;
import com.carenexus.api.common.spi.LoginStep;
import com.carenexus.api.common.spi.SessionFlagContributor;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accounts and sessions: registration, sign-in, refresh-token rotation, sign-out and session revocation.
 *
 * A session is the chain of refresh tokens that share one {@code refresh_tokens.session_id}; at any time it has at
 * most one token that is not revoked. Access tokens (JWT) carry no session claim and are never revoked: they stop
 * working when they expire or when the account becomes inactive.
 *
 * Other modules extend the sign-in through the SPIs in {@code common.spi}: {@link LoginGate} beans run after the
 * password was verified (they may refuse or ask for a challenge), {@link SessionFlagContributor} beans fill
 * {@code flags}. The methods other modules call are {@link #completeLogin}, {@link #revokeAllSessions},
 * {@link #revokeSession}, {@link #revokeOtherSessions} and {@link #createActiveUserAccount}.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AuthService {

    /** Audit resource type of account and security events. */
    static final String RESOURCE = "ACCOUNT";

    /** Recorded as the creator of an account that was created while nobody was signed in. */
    public static final String SYSTEM_CREATOR = "SYSTEM";

    static final String INVALID_ROLE_MESSAGE = "Invalid role. Allowed roles are patient, doctor and proxy.";

    // Kept under 120 chars: the clients fall back to a generic error for longer messages. The words
    // "pending verification" and "Account is inactive" are matched by the web's parseApiError.
    static final String ACCOUNT_CLOSED_MESSAGE = "This account has been closed. Please contact support.";
    static final String ACCOUNT_PENDING_MESSAGE =
            "Your clinician account is pending verification. You can sign in once an administrator approves it.";
    static final String ACCOUNT_REJECTED_MESSAGE =
            "Your clinician registration was not approved. Please contact support.";
    static final String ACCOUNT_INACTIVE_MESSAGE = "Account is inactive";

    static final String REGISTERED_MESSAGE = "Account created successfully. Please log in.";
    static final String REGISTERED_DOCTOR_MESSAGE =
            "Account created. Your clinician account is pending verification; you will be able to sign in once an "
                    + "administrator has approved it.";

    private static final int REFRESH_TOKEN_DAYS = 7;
    private static final int DEVICE_TYPE_MAX = 50;
    private static final int DEVICE_INFO_MAX = 255;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN = Pattern.compile(RegisterRequest.EMAIL_REGEX);
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

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
    private final AuthDoctorRepository authDoctorRepository;
    private final HumanVerification humanVerification;
    private final UserContext userContext;
    private final ObjectProvider<LoginGate> loginGates;
    private final ObjectProvider<SessionFlagContributor> sessionFlagContributors;

    /** Valid hash of a random value, compared against for unknown emails so timing does not reveal registered emails. */
    private String dummyPasswordHash;

    @PostConstruct
    void initDummyHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ==================== REGISTRATION ====================

    /**
     * Register a new account without request context (no client address or user agent in the event).
     * See {@link #register(RegisterRequest, String, String)}.
     */
    public AuthResponse register(RegisterRequest request) {
        return register(request, null, null);
    }

    /**
     * Self-registration of a patient, doctor or caregiver, with its role row, in one transaction: if any step fails
     * (the caregiver link, a listener of {@link UserRegisteredEvent}), nothing is created.
     *
     * A doctor is created inactive with verification status PENDING and cannot sign in until an administrator
     * approves it. The email of a self-registered account is not verified yet. No tokens are returned.
     *
     * Order: human verification, role and caregiver-link checks, duplicate checks, rows, event, audit.
     *
     * @param ipAddress client address, carried into the event (may be null)
     * @param userAgent User-Agent header, carried into the event (may be null)
     */
    public AuthResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        if (request == null) {
            throw new BadRequestException("Registration details are required");
        }
        // The controller validated the body already; these checks also cover callers that build the request in code.
        String firstName = requireName(request.getFirstName(), "First name");
        String lastName = requireName(request.getLastName(), "Last name");
        String email = requireEmail(request.getEmail());
        requirePassword(request.getPassword());
        if (isBlank(request.getRole())) {
            throw new BadRequestException("Role is required");
        }
        String phone = optionalPhone(request.getPhone());

        // Before any row is written.
        humanVerification.verify(HumanVerification.ACTION_REGISTER, request.getHumanVerificationToken(), ipAddress);

        String role = resolveAccountRole(request.getRole(), false);
        boolean isDoctor = "DOCTOR".equals(role);

        String caregiverSystemId = trimToNull(request.getCaregiverSystemId());
        if (caregiverSystemId != null && !"PATIENT".equals(role)) {
            throw new BadRequestException("A caregiver link can only be supplied when registering a patient");
        }
        String relationship = trimToNull(request.getRelationship());
        if (relationship != null && relationship.length() > 80) {
            throw new BadRequestException("Relationship must be at most 80 characters");
        }
        if (caregiverSystemId != null) {
            boolean validCaregiver = userRepository.findBySystemId(caregiverSystemId)
                    .filter(u -> "CAREGIVER".equals(u.getRole()) && Boolean.TRUE.equals(u.getIsActive()))
                    .isPresent()
                    && caregiverRepository.findBySystemId(caregiverSystemId).isPresent();
            if (!validCaregiver) {
                throw new BadRequestException("Invalid caregiver: no active proxy/caregiver account exists with that ID");
            }
        }

        // Clinician details are stored for doctors only; other roles may send them and they are ignored.
        String licenseNo = isDoctor ? optionalText(request.getLicenseNo(), 50, "Licence number") : null;
        String specialization = isDoctor ? optionalText(request.getSpecialization(), 150, "Specialization") : null;
        String licensingBody = isDoctor ? optionalText(request.getLicensingBody(), 150, "Licensing body") : null;
        String licenseCountry = isDoctor ? optionalCountry(request.getLicenseCountry()) : null;

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email already registered");
        }
        if (phone != null && userRepository.findByPhone(phone).isPresent()) {
            throw new ConflictException("Phone number already registered");
        }
        if (licenseNo != null && authDoctorRepository.existsByLicenseNoIgnoreCase(licenseNo)) {
            throw new ConflictException("Licence number already registered");
        }

        String systemId = systemIdGenerator.generateSystemId(role);
        // email_verified_at stays null: a self-registered address is not confirmed yet.
        User savedUser = userRepository.save(User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .systemId(systemId)
                // Self-registered doctors stay inactive until an administrator approves them
                .isActive(!isDoctor)
                .build());

        switch (role) {
            case "PATIENT" -> {
                patientRepository.save(Patient.builder()
                        .systemId(systemId)
                        .healthStatus("STABLE")
                        .build());

                if (caregiverSystemId != null) {
                    DependentAccess access = dependentAccessRepository.save(DependentAccess.builder()
                            .patientSystemId(systemId)
                            .caregiverSystemId(caregiverSystemId)
                            // The patient registering is the one granting access
                            .authorizedBySystemId(systemId)
                            .relationship(relationship == null ? "caregiver" : relationship)
                            .accessLevel("VIEW_ONLY")
                            .isActive(true)
                            .build());
                    // Recorded in the consent ledger like every other grant.
                    eventPublisher.publishEvent(new CaregiverGrantRecordedEvent(access.getAccessId(), systemId,
                            caregiverSystemId, systemId, CaregiverGrantRecordedEvent.GRANTED, false));
                }
            }
            case "DOCTOR" -> doctorRepository.save(Doctor.builder()
                    .systemId(systemId)
                    .licenseNo(licenseNo)
                    .specialization(specialization)
                    .licensingBody(licensingBody)
                    .licenseCountry(licenseCountry)
                    .isActive(false)
                    .verificationStatus(Doctor.VERIFICATION_PENDING)
                    .build());
            case "CAREGIVER" -> caregiverRepository.save(Caregiver.builder()
                    .systemId(systemId)
                    .build());
            default -> throw new BadRequestException(INVALID_ROLE_MESSAGE);
        }

        // Listeners run inside this transaction (legal acceptance, verification mail, administrator notice) and may
        // throw to abort the registration. The accepted versions are passed on exactly as sent.
        eventPublisher.publishEvent(new UserRegisteredEvent(systemId, role, email,
                request.getAcceptedTermsVersion(), request.getAcceptedPrivacyVersion(), ipAddress, userAgent, null));

        auditService.logAccountEvent(systemId, "REGISTER", RESOURCE, systemId, "SUCCESS");

        // The user only: no tokens until sign-in.
        return AuthResponse.builder()
                .message(isDoctor ? REGISTERED_DOCTOR_MESSAGE : REGISTERED_MESSAGE)
                .user(toAuthUser(savedUser))
                .build();
    }

    /**
     * Create an ACTIVE users row that can sign in: valid names, email and password are required, exactly as for
     * self-registration. Same as the eight-argument method with no temporary password and no ADMIN role.
     * Used by CaregiverService.createDependent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User createActiveUserAccount(String firstName, String lastName, String rawEmail, String password,
                                        String rawPhone, String role) {
        return createAccount(firstName, lastName, rawEmail, password, rawPhone, role, false, false, true);
    }

    /**
     * Create an ACTIVE users row on behalf of somebody else (a caregiver adding a dependent, an administrator
     * creating a colleague). It only creates the users row: the caller creates the role profile
     * (patients / doctors / caregivers row) and any links, and must already be in a transaction
     * (Propagation.MANDATORY) so that everything rolls back together.
     *
     * <ul>
     *   <li>{@code rawEmail} and {@code password} may both be null: a managed account that cannot sign in until it
     *       is given credentials. Exactly one of them missing is a 400.</li>
     *   <li>{@code temporaryPassword}: the user must change the password at the first sign-in.</li>
     *   <li>{@code allowAdminRole}: role ADMIN is accepted only when true.</li>
     *   <li>The phone is optional and must be unique only when given.</li>
     *   <li>The email counts as verified (the creator vouches for it).</li>
     * </ul>
     * Validation failures are BadRequestException (400); a duplicate email or phone is ConflictException (409).
     * Publishes {@link UserRegisteredEvent} with {@code createdBySystemId} = the signed-in caller, or
     * {@link #SYSTEM_CREATOR} when nobody is signed in, and audits REGISTER with the creator as actor.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User createActiveUserAccount(String firstName, String lastName, String rawEmail, String password,
                                        String rawPhone, String role, boolean temporaryPassword, boolean allowAdminRole) {
        return createAccount(firstName, lastName, rawEmail, password, rawPhone, role, temporaryPassword,
                allowAdminRole, false);
    }

    private User createAccount(String firstName, String lastName, String rawEmail, String password, String rawPhone,
                               String role, boolean temporaryPassword, boolean allowAdminRole,
                               boolean credentialsRequired) {
        String validFirstName = requireName(firstName, "First name");
        String validLastName = requireName(lastName, "Last name");

        boolean hasEmail = !isBlank(rawEmail);
        boolean hasPassword = password != null && !password.isEmpty();
        // No email and no password: an account somebody else manages; nobody can sign in to it.
        boolean managed = !credentialsRequired && !hasEmail && !hasPassword;
        String email = null;
        if (!managed) {
            email = requireEmail(rawEmail);
            requirePassword(password);
        }

        String resolvedRole = resolveAccountRole(role, allowAdminRole);
        String phone = optionalPhone(rawPhone);

        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("Email already registered");
        }
        if (phone != null && userRepository.findByPhone(phone).isPresent()) {
            throw new ConflictException("Phone number already registered");
        }

        User user = userRepository.save(User.builder()
                .firstName(validFirstName)
                .lastName(validLastName)
                .email(email)
                .phone(phone)
                .passwordHash(managed ? null : passwordEncoder.encode(password))
                .role(resolvedRole)
                .systemId(systemIdGenerator.generateSystemId(resolvedRole))
                .isActive(true)
                .emailVerifiedAt(LocalDateTime.now())
                .mustChangePassword(temporaryPassword && !managed)
                .build());

        String creator = userContext.getCurrentUserSystemId();
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getSystemId(), resolvedRole, email,
                null, null, null, null, creator != null ? creator : SYSTEM_CREATOR));
        auditService.logAccountEvent(creator, "REGISTER", RESOURCE, user.getSystemId(), "SUCCESS");
        return user;
    }

    // ==================== SIGN-IN ====================

    /**
     * Verify email and password and either issue a session or answer with a challenge.
     *
     * Order: rate limits (every attempt is counted), account lookup and password check (unknown email, account
     * without a password and wrong password give the same 401 with the same timing), account-state refusals,
     * {@link LoginGate} beans in order (the first challenge ends the login without tokens), then
     * {@link #completeLogin}.
     *
     * Failure paths only write audit rows; noRollbackFor keeps them from being rolled back with the thrown
     * exception even if AuditService joins this transaction.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String email = normalizeEmail(request == null ? null : request.getEmail());
        String password = request == null ? null : request.getPassword();
        if (email == null || email.isEmpty() || password == null || password.isEmpty()) {
            throw new BadRequestException("Email and password are required");
        }

        try {
            loginAttemptThrottle.checkAllowed(email, ipAddress);
        } catch (TooManyLoginAttemptsException e) {
            if (e.isAccountLocked()) {
                auditAccountLocked(email);
            }
            throw e;
        }

        Optional<User> found = findUserByEmail(email);
        if (found.isEmpty() || found.get().getPasswordHash() == null) {
            // Spend the same bcrypt time as a real check so response timing does not reveal registered emails
            passwordEncoder.matches(password, dummyPasswordHash);
            throw failLogin(email, ipAddress, userAgent, found.isEmpty() ? "Unknown account" : "Account has no password");
        }
        User user = found.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw failLogin(email, ipAddress, userAgent, "Invalid password");
        }

        // The password is right: forget the attempts counted for this email.
        loginAttemptThrottle.recordSuccess(email, ipAddress);

        LoginContext context = new LoginContext(ipAddress, userAgent, request.getDeviceType(), request.getDeviceInfo());
        // Only now, after the password was verified, so the answer never reveals whether an email is registered.
        requireUsableAccount(user, context);

        for (LoginGate gate : loginGates.orderedStream().toList()) {
            LoginStep step = gate.afterPasswordVerified(user, context);
            if (step != null && step.isChallenge()) {
                // No session yet: the endpoint that verifies the challenge finishes with completeLogin.
                return AuthResponse.builder()
                        .challenge(AuthResponse.Challenge.builder()
                                .type(step.challengeType())
                                .token(step.challengeToken())
                                .expiresInSeconds(step.expiresInSeconds())
                                .build())
                        .build();
            }
        }

        return issueSession(user, context);
    }

    /**
     * Issue a session for an account whose identity was already established: the last step of {@link #login}, and
     * what the endpoint that verifies a login challenge calls. Runs no {@link LoginGate}. Applies the account-state
     * refusals again (closed, clinician pending or rejected, inactive), then creates a new session (new session id
     * and refresh token), audits LOGIN, computes the flags and publishes {@link UserLoggedInEvent}.
     *
     * @param systemId the account to sign in
     * @param ctx      client address, user agent and device description of the sign-in (may be null)
     * @return tokens (with {@code sessionId}), user and flags
     */
    public AuthResponse completeLogin(String systemId, LoginContext ctx) {
        LoginContext context = ctx != null ? ctx : new LoginContext(null, null, null, null);
        User user = (systemId == null ? Optional.<User>empty() : userRepository.findBySystemId(systemId))
                .orElseThrow(InvalidCredentialsException::new);
        requireUsableAccount(user, context);
        return issueSession(user, context);
    }

    private AuthResponse issueSession(User user, LoginContext context) {
        UUID sessionId = UUID.randomUUID();
        String deviceType = clip(trimToNull(context.deviceType()), DEVICE_TYPE_MAX);
        String deviceInfo = clip(trimToNull(context.deviceInfo()), DEVICE_INFO_MAX);
        String rawRefreshToken = createRefreshToken(user.getSystemId(), sessionId, deviceType, deviceInfo);
        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

        auditService.logLogin(user.getSystemId(), context.ip(), context.userAgent());

        AuthResponse response = buildSessionResponse(user, accessToken, rawRefreshToken, sessionId);
        eventPublisher.publishEvent(new UserLoggedInEvent(user.getSystemId(), user.getRole(), context.ip(),
                deviceType, deviceInfo));
        return response;
    }

    /**
     * Account-state refusals, first match wins: closed, clinician pending, clinician rejected, inactive.
     * Each refusal is audited as a failed login (reason only, never the email).
     */
    private void requireUsableAccount(User user, LoginContext context) {
        if (user.getClosedAt() != null) {
            auditFailedLogin(user.getEmail(), context.ip(), context.userAgent(), "Account closed");
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.ACCOUNT_CLOSED, ACCOUNT_CLOSED_MESSAGE);
        }
        if ("DOCTOR".equals(user.getRole())) {
            // A doctor without a doctors row was never verified either.
            String status = doctorRepository.findBySystemId(user.getSystemId())
                    .map(Doctor::getVerificationStatus)
                    .orElse(Doctor.VERIFICATION_PENDING);
            if (status == null || Doctor.VERIFICATION_PENDING.equals(status)) {
                auditFailedLogin(user.getEmail(), context.ip(), context.userAgent(), "Clinician pending verification");
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.ACCOUNT_PENDING_VERIFICATION,
                        ACCOUNT_PENDING_MESSAGE);
            }
            if (Doctor.VERIFICATION_REJECTED.equals(status)) {
                auditFailedLogin(user.getEmail(), context.ip(), context.userAgent(), "Clinician registration rejected");
                throw new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.ACCOUNT_REJECTED, ACCOUNT_REJECTED_MESSAGE);
            }
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            // Deactivated by an administrator, including a suspended clinician.
            auditFailedLogin(user.getEmail(), context.ip(), context.userAgent(), "Account inactive");
            throw new BadRequestException(ACCOUNT_INACTIVE_MESSAGE);
        }
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

    /** The per-account limit refused a sign-in: recorded only when the account exists. */
    private void auditAccountLocked(String email) {
        try {
            findUserByEmail(email).ifPresent(user ->
                    auditService.logAccountEvent(null, "ACCOUNT_LOCKED", RESOURCE, user.getSystemId(), "FAILURE"));
        } catch (RuntimeException e) {
            log.warn("Could not write account-locked audit entry: {}", e.getClass().getSimpleName());
        }
    }

    /** A failed security event about an account; nobody is signed in, so there is no actor. */
    private void auditSecurityFailure(String action, String targetSystemId) {
        try {
            auditService.logAccountEvent(null, action, RESOURCE, targetSystemId, "FAILURE");
        } catch (RuntimeException e) {
            log.warn("Could not write {} audit entry: {}", action, e.getClass().getSimpleName());
        }
    }

    // ==================== REFRESH ====================

    /**
     * Create a refresh token of a session. Only its SHA-256 hash is stored; the raw value is returned once to the
     * client.
     */
    private String createRefreshToken(String systemId, UUID sessionId, String deviceType, String deviceInfo) {
        String raw = generateRandomToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .systemId(systemId)
                .sessionId(sessionId)
                .tokenHash(sha256Hex(raw))
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_TOKEN_DAYS))
                .isRevoked(false)
                .deviceType(deviceType)
                .deviceInfo(deviceInfo)
                .build());
        return raw;
    }

    /**
     * Exchange a refresh token for a new access token AND a new refresh token (rotation). The new token belongs to
     * the same session (same session id, same device description).
     *
     * A revoked token is refused. When a newer token exists in its session it was rotated, so presenting it again
     * is treated as theft: every refresh token of that user is revoked (all sessions). A revoked token that is
     * still the newest of its session belongs to a session that was ended on purpose (sign-out, a revoked session)
     * and is simply refused, so ending one session never ends the others.
     *
     * Failures are audited (TOKEN_REFRESH, or TOKEN_REUSE); a successful refresh is not. noRollbackFor keeps the
     * revocations when the 401 is thrown.
     */
    @Transactional(noRollbackFor = AuthenticationException.class)
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        String raw = request == null ? null : request.getRefreshToken();
        if (raw == null || raw.isBlank()) {
            throw new BadCredentialsException("Refresh token is required");
        }

        Optional<RefreshToken> stored = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(raw.trim()));
        if (stored.isEmpty()) {
            auditSecurityFailure("TOKEN_REFRESH", null);
            throw new BadCredentialsException("Invalid refresh token");
        }
        RefreshToken refreshToken = stored.get();
        String systemId = refreshToken.getSystemId();

        if (Boolean.TRUE.equals(refreshToken.getIsRevoked())) {
            boolean rotated = refreshTokenRepository.existsBySessionIdAndTokenIdGreaterThan(
                    refreshToken.getSessionId(), refreshToken.getTokenId());
            if (rotated) {
                log.warn("Refresh token reuse detected for {}; revoking all refresh tokens", systemId);
                revokeAllForUser(systemId);
                auditSecurityFailure("TOKEN_REUSE", systemId);
                throw new BadCredentialsException("Refresh token reuse detected. Please sign in again.");
            }
            auditSecurityFailure("TOKEN_REFRESH", systemId);
            throw new BadCredentialsException("Invalid refresh token");
        }

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            revoke(refreshToken);
            auditSecurityFailure("TOKEN_REFRESH", systemId);
            throw new BadCredentialsException("Refresh token has expired");
        }

        Optional<User> found = userRepository.findBySystemId(systemId);
        if (found.isEmpty() || !Boolean.TRUE.equals(found.get().getIsActive()) || found.get().getClosedAt() != null) {
            revokeAllForUser(systemId);
            auditSecurityFailure("TOKEN_REFRESH", systemId);
            throw new BadCredentialsException("User account is inactive");
        }
        User user = found.get();

        // Rotate: the presented token stops working, a new one is issued for the same session and device
        revoke(refreshToken);
        String newRawRefreshToken = createRefreshToken(systemId, refreshToken.getSessionId(),
                refreshToken.getDeviceType(), refreshToken.getDeviceInfo());
        String accessToken = jwtUtil.generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

        return buildSessionResponse(user, accessToken, newRawRefreshToken, refreshToken.getSessionId());
    }

    // ==================== SIGN-OUT AND SESSION REVOCATION ====================

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
     * Logout everywhere - revoke every active refresh token of the user. Audits LOGOUT and publishes
     * {@link UserLoggedOutEvent}.
     */
    public void logout(String systemId, String ipAddress) {
        revokeAllForUser(systemId);
        auditService.logLogout(systemId, ipAddress);
        eventPublisher.publishEvent(new UserLoggedOutEvent(systemId));
    }

    /**
     * End every session of the user (password reset, email change, account closure, administrator action) and
     * publish {@link UserLoggedOutEvent}. Writes no audit row: the caller audits its own action.
     */
    public void revokeAllSessions(String systemId) {
        revokeAllForUser(systemId);
        eventPublisher.publishEvent(new UserLoggedOutEvent(systemId));
    }

    /**
     * End one session of the user. Publishes no event and writes no audit row: the user is still signed in on the
     * session that asked for it.
     *
     * @return false when nothing was revoked: unknown id, another user's session, a session that already ended, or
     *         a value that is not a UUID
     */
    public boolean revokeSession(String systemId, String sessionId) {
        Optional<UUID> id = parseSessionId(sessionId);
        if (systemId == null || id.isEmpty()) {
            return false;
        }
        List<RefreshToken> tokens = refreshTokenRepository.findBySystemIdAndSessionIdAndIsRevokedFalse(systemId, id.get());
        tokens.forEach(this::revoke);
        return !tokens.isEmpty();
    }

    /**
     * End every session of the user except {@code keepSessionId} (the {@code tokens.sessionId} of the caller's own
     * session). A UUID that matches no active session ends them all.
     *
     * With a keep id no event is published and no audit row is written. A null keep id is
     * {@link #revokeAllSessions} (which publishes {@link UserLoggedOutEvent}).
     *
     * @return the number of sessions ended
     * @throws BadRequestException "Invalid session id" when {@code keepSessionId} is not null and not a UUID
     */
    public int revokeOtherSessions(String systemId, String keepSessionId) {
        if (keepSessionId == null) {
            int sessions = (int) refreshTokenRepository.findBySystemIdAndIsRevokedFalse(systemId).stream()
                    .map(RefreshToken::getSessionId)
                    .distinct()
                    .count();
            revokeAllSessions(systemId);
            return sessions;
        }
        UUID keep = parseSessionId(keepSessionId)
                .orElseThrow(() -> new BadRequestException("Invalid session id"));
        List<RefreshToken> others = refreshTokenRepository.findBySystemIdAndIsRevokedFalse(systemId).stream()
                .filter(token -> !keep.equals(token.getSessionId()))
                .toList();
        others.forEach(this::revoke);
        return (int) others.stream().map(RefreshToken::getSessionId).distinct().count();
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

    private static Optional<UUID> parseSessionId(String value) {
        if (value == null || !UUID_PATTERN.matcher(value.trim()).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(value.trim()));
    }

    // ==================== CURRENT USER ====================

    /**
     * Get current user information by system ID
     */
    @Transactional(readOnly = true)
    public AuthResponse.AuthUser getCurrentUserBySystemId(String systemId) {
        return toAuthUser(requireUser(systemId));
    }

    /**
     * The signed-in user with the current session flags (same content as at sign-in), for {@code GET /api/users/me}.
     */
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(String systemId) {
        User user = requireUser(systemId);
        AuthResponse.AuthUser view = toAuthUser(user);
        return CurrentUserResponse.builder()
                .user_id(view.getUser_id())
                .system_id(view.getSystem_id())
                .name(view.getName())
                .email(view.getEmail())
                .role(view.getRole())
                .flags(sessionFlags(user))
                .build();
    }

    private User requireUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * Flags of a session, filled by the {@link SessionFlagContributor} beans. Always a map, empty when no
     * contributor is installed. A contributor that fails is skipped and its keys are left out.
     */
    private Map<String, Object> sessionFlags(User user) {
        Map<String, Object> flags = new LinkedHashMap<>();
        sessionFlagContributors.orderedStream().forEach(contributor -> {
            try {
                Map<String, Object> own = new LinkedHashMap<>();
                contributor.contribute(user, own);
                flags.putAll(own);
            } catch (RuntimeException e) {
                log.warn("Session flag contributor {} failed: {}", contributor.getClass().getSimpleName(),
                        e.getClass().getSimpleName());
            }
        });
        return flags;
    }

    private AuthResponse buildSessionResponse(User user, String accessToken, String rawRefreshToken, UUID sessionId) {
        return AuthResponse.builder()
                .tokens(AuthResponse.AuthTokens.builder()
                        .accessToken(accessToken)
                        .refreshToken(rawRefreshToken)
                        .expiresIn(jwtUtil.getExpirationTimeInSeconds())
                        .sessionId(sessionId.toString())
                        .build())
                .user(toAuthUser(user))
                .flags(sessionFlags(user))
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

    // ==================== VALIDATION AND TEXT HELPERS ====================

    /** Role as the clients know it: lower-case, CAREGIVER reported as proxy. */
    private String normalizeRole(String role) {
        if (role == null) return null;
        String lower = role.toLowerCase(Locale.ROOT);
        if (lower.equals("caregiver")) {
            return "proxy";
        }
        return lower;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Stored role of a new account. PATIENT, DOCTOR and CAREGIVER (clients say PROXY) are always accepted, in any
     * letter case. ADMIN is accepted only when {@code allowAdminRole}: administrators never self-register.
     */
    private static String resolveAccountRole(String role, boolean allowAdminRole) {
        String upper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PATIENT" -> "PATIENT";
            case "DOCTOR" -> "DOCTOR";
            case "CAREGIVER", "PROXY" -> "CAREGIVER";
            case "ADMIN" -> {
                if (!allowAdminRole) {
                    throw new BadRequestException(INVALID_ROLE_MESSAGE);
                }
                yield "ADMIN";
            }
            default -> throw new BadRequestException(INVALID_ROLE_MESSAGE);
        };
    }

    private static String requireName(String value, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(label + " is required");
        }
        if (trimmed.length() > 100) {
            throw new BadRequestException(label + " must be at most 100 characters");
        }
        return trimmed;
    }

    /** Normalised (trimmed, lower-case) email, or 400 when it is missing or malformed. */
    private static String requireEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (email == null || email.isEmpty()) {
            throw new BadRequestException("Email is required");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new BadRequestException("Please enter a valid email address");
        }
        return email;
    }

    private static void requirePassword(String password) {
        String problem = PasswordPolicy.violation(password);
        if (problem != null) {
            throw new BadRequestException(problem);
        }
    }

    private static String optionalPhone(String rawPhone) {
        String phone = trimToNull(rawPhone);
        if (phone != null && phone.length() > 20) {
            throw new BadRequestException("Phone number must be at most 20 characters");
        }
        return phone;
    }

    private static String optionalText(String value, int max, String label) {
        String text = trimToNull(value);
        if (text != null && text.length() > max) {
            throw new BadRequestException(label + " must be at most " + max + " characters");
        }
        return text;
    }

    private static String optionalCountry(String value) {
        String country = trimToNull(value);
        if (country == null) {
            return null;
        }
        country = country.toUpperCase(Locale.ROOT);
        if (!COUNTRY_PATTERN.matcher(country).matches()) {
            throw new BadRequestException("Licence country must be a two-letter country code");
        }
        return country;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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
            // Every Java runtime ships SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
