package com.carenexus.api.admin.user;

import com.carenexus.api.admin.audit.AuditQueryService;
import com.carenexus.api.admin.audit.dto.AuditLogResponse;
import com.carenexus.api.admin.clinician.AdminDoctorMapper;
import com.carenexus.api.admin.clinician.repository.ClinicianRepository;
import com.carenexus.api.admin.user.dto.request.CreateAdminRequest;
import com.carenexus.api.admin.user.dto.response.AdminUserActionResponse;
import com.carenexus.api.admin.user.dto.response.AdminUserDetailResponse;
import com.carenexus.api.admin.user.dto.response.AdminUserResponse;
import com.carenexus.api.admin.user.repository.AdminUserRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.event.DoctorVerificationDecidedEvent;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.event.UserReactivatedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.model.Doctor;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administrator account controls: search, detail, creation of administrators, activation and deactivation, forced
 * sign-out and password-reset links. Every method is ADMIN only ({@link AuthorizationService#requireAdmin()}) and
 * runs in one transaction, so a state change, its session revocation, its audit row and its events commit together.
 *
 * Account activity rules:
 * <ul>
 *   <li>deactivate: never the caller, never the last active administrator; a doctor that was APPROVED becomes
 *       SUSPENDED; every session ends; a repeat on an inactive account is a no-op;</li>
 *   <li>activate: re-activates a deactivated account or re-opens one its owner closed; never an anonymised account;
 *       never a doctor that is PENDING or REJECTED (that is clinician verification); a SUSPENDED doctor becomes
 *       APPROVED again; a repeat on an active account is a no-op.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserAdminService {

    public static final String RESOURCE = "ACCOUNT";
    public static final String CODE_ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final String CODE_DOCTOR_NOT_VERIFIED = "DOCTOR_NOT_VERIFIED";

    static final String MSG_ACTIVATED =
            "Account reactivated. Cancelled appointments and revoked caregiver access were not restored.";
    static final String MSG_ALREADY_ACTIVE = "Account is already active.";
    static final String MSG_DEACTIVATED = "Account deactivated. The user has been signed out everywhere.";
    static final String MSG_ALREADY_INACTIVE = "Account is already inactive.";
    static final String MSG_SESSIONS_REVOKED = "The user has been signed out everywhere.";
    static final String MSG_RESET_SENT = "A link to set a new password was sent to the account's email address.";

    private static final Set<String> ROLES = Set.of("PATIENT", "DOCTOR", "CAREGIVER", "ADMIN");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt")
            .and(Sort.by(Sort.Direction.DESC, "userId"));

    private final AdminUserRepository userRepository;
    private final ClinicianRepository clinicianRepository;
    private final AdminDoctorMapper doctorMapper;
    private final AuthService authService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final AuditQueryService auditQueryService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== READS ====================

    /**
     * Paged account list, newest first. {@code q} is a case-insensitive "contains" match on the full name, the email
     * and the system id ({@code %} and {@code _} are matched literally); filters combine with AND.
     *
     * @param role   PATIENT, DOCTOR, CAREGIVER or ADMIN in any case; PROXY means CAREGIVER; blank = every role
     * @param active true / false / null (both)
     * @throws BadRequestException for an unknown role
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(String q, String role, Boolean active, Integer page, Integer size) {
        authorizationService.requireAdmin();
        String normalizedRole = normalizeRole(role);
        String search = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);

        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (normalizedRole != null) {
                predicates.add(cb.equal(root.get("role"), normalizedRole));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("isActive"), active));
            }
            if (search != null) {
                String pattern = "%" + escapeLike(search) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(cb.concat(cb.concat(root.get("firstName"), " "), root.get("lastName"))),
                                pattern, '\\'),
                        cb.like(cb.lower(root.get("email")), pattern, '\\'),
                        cb.like(cb.lower(root.get("systemId")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        Pageable pageable = PageParams.of(page, size, NEWEST_FIRST);
        Page<User> users = userRepository.findAll(spec, pageable);

        // Verification status of the doctors on this page, in one query.
        List<String> doctorIds = users.getContent().stream()
                .filter(u -> "DOCTOR".equals(u.getRole()))
                .map(User::getSystemId)
                .toList();
        Map<String, String> statuses = doctorIds.isEmpty() ? Map.of()
                : clinicianRepository.findBySystemIdIn(doctorIds).stream()
                        .collect(Collectors.toMap(Doctor::getSystemId, Doctor::getVerificationStatus, (a, b) -> a));

        Function<User, AdminUserResponse> map = u -> summary(u, statuses.get(u.getSystemId()));
        return PageResponse.of(users, map);
    }

    /** One account with its status details. */
    @Transactional(readOnly = true)
    public AdminUserDetailResponse getUser(String systemId) {
        authorizationService.requireAdmin();
        return detail(findUser(systemId));
    }

    /** Audit rows concerning one account (actor, target or patient), newest first. The read is audited. */
    public PageResponse<AuditLogResponse> userAuditLogs(String systemId, Integer page, Integer size) {
        authorizationService.requireAdmin();
        findUser(systemId);
        return auditQueryService.forAccount(systemId, page, size);
    }

    // ==================== CREATE ====================

    /**
     * Create another administrator with the temporary password chosen by the caller: active, email counted as
     * verified, password change forced at first sign-in. No email is sent. Audited USER_CREATE.
     *
     * @throws ConflictException "Email already registered" / "Phone number already registered"
     */
    public AdminUserDetailResponse createAdmin(CreateAdminRequest request) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        if (request == null) {
            throw new BadRequestException("Account details are required");
        }
        User created = authService.createActiveUserAccount(request.firstName(), request.lastName(), request.email(),
                request.password(), request.phone(), "ADMIN", true, true);
        auditService.logAccountEvent(adminSystemId, "USER_CREATE", RESOURCE, created.getSystemId(), "SUCCESS");
        return detail(created);
    }

    // ==================== ACTIVATION ====================

    /**
     * Re-activate a deactivated account or re-open one its owner closed (clears closedAt, closureReason and the
     * deactivation reason). A SUSPENDED doctor becomes APPROVED again. Audited USER_ACTIVATE; publishes
     * {@link UserReactivatedEvent}. Nothing the deactivation caused is restored.
     *
     * @throws ResourceNotFoundException "User not found"
     * @throws ConflictException         ACCOUNT_CLOSED for an anonymised account, DOCTOR_NOT_VERIFIED for a doctor
     *                                   that is PENDING, REJECTED or has no clinician row
     */
    public AdminUserActionResponse activate(String systemId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        User user = findUser(systemId);

        if (isAnonymised(user)) {
            throw new ConflictException(CODE_ACCOUNT_CLOSED, "This account was deleted on request and cannot be reactivated");
        }
        Optional<Doctor> doctor = "DOCTOR".equals(user.getRole())
                ? clinicianRepository.findBySystemId(systemId) : Optional.empty();
        if ("DOCTOR".equals(user.getRole())) {
            String status = doctor.map(Doctor::getVerificationStatus).orElse(null);
            if (!Doctor.VERIFICATION_APPROVED.equals(status) && !Doctor.VERIFICATION_SUSPENDED.equals(status)) {
                throw new ConflictException(CODE_DOCTOR_NOT_VERIFIED,
                        "This clinician is not verified yet. Approve the account from the clinician verification queue.");
            }
        }
        if (Boolean.TRUE.equals(user.getIsActive()) && user.getClosedAt() == null) {
            return AdminUserActionResponse.of(detail(user), MSG_ALREADY_ACTIVE);
        }

        user.setIsActive(true);
        user.setClosedAt(null);
        user.setClosureReason(null);
        userRepository.save(user);
        userRepository.updateDeactivationReason(systemId, null);

        if (doctor.isPresent()) {
            Doctor d = doctor.get();
            boolean wasSuspended = Doctor.VERIFICATION_SUSPENDED.equals(d.getVerificationStatus());
            d.setIsActive(true);
            if (wasSuspended) {
                d.setVerificationStatus(Doctor.VERIFICATION_APPROVED);
                d.setRejectionReason(null);
                d.setVerifiedBySystemId(adminSystemId);
                d.setVerifiedAt(now());
            }
            clinicianRepository.save(d);
            if (wasSuspended) {
                eventPublisher.publishEvent(new DoctorVerificationDecidedEvent(systemId, adminSystemId,
                        Doctor.VERIFICATION_APPROVED, null));
            }
        }

        auditService.logAccountEvent(adminSystemId, "USER_ACTIVATE", RESOURCE, systemId, "SUCCESS");
        eventPublisher.publishEvent(new UserReactivatedEvent(systemId, user.getRole(), adminSystemId));
        return AdminUserActionResponse.of(detail(user), MSG_ACTIVATED);
    }

    /**
     * Deactivate an account and end every session. An APPROVED doctor becomes SUSPENDED with the reason. Audited
     * USER_DEACTIVATE; publishes {@link UserDeactivatedEvent} (cause ADMIN). The response counts the account's
     * future active appointments at this moment (the downstream clean-up runs after the commit).
     *
     * @throws ResourceNotFoundException "User not found"
     * @throws ConflictException         for the caller's own account and for the last active administrator
     */
    public AdminUserActionResponse deactivate(String systemId, String reason) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        String cleanReason = reason == null ? null : reason.trim();
        if (cleanReason == null || cleanReason.isEmpty()) {
            throw new BadRequestException("Reason is required");
        }
        User user = findUser(systemId);

        if (systemId.equals(adminSystemId)) {
            throw new ConflictException("You cannot deactivate your own account");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            return AdminUserActionResponse.of(detail(user), MSG_ALREADY_INACTIVE);
        }
        if ("ADMIN".equals(user.getRole())) {
            List<User> activeAdmins = userRepository.lockActiveAdmins();
            boolean targetCounted = activeAdmins.stream().anyMatch(a -> systemId.equals(a.getSystemId()));
            if (!targetCounted) {
                // Deactivated concurrently by another administrator while we waited for the lock.
                return AdminUserActionResponse.of(detail(user), MSG_ALREADY_INACTIVE);
            }
            if (activeAdmins.size() <= 1) {
                throw new ConflictException("Cannot deactivate the last active administrator");
            }
        }

        user.setIsActive(false);
        userRepository.save(user);
        userRepository.updateDeactivationReason(systemId, cleanReason);

        if ("DOCTOR".equals(user.getRole())) {
            clinicianRepository.findBySystemId(systemId).ifPresent(d -> {
                d.setIsActive(false);
                boolean suspend = Doctor.VERIFICATION_APPROVED.equals(d.getVerificationStatus());
                if (suspend) {
                    d.setVerificationStatus(Doctor.VERIFICATION_SUSPENDED);
                    d.setRejectionReason(cleanReason);
                    d.setVerifiedBySystemId(adminSystemId);
                    d.setVerifiedAt(now());
                }
                clinicianRepository.save(d);
                if (suspend) {
                    eventPublisher.publishEvent(new DoctorVerificationDecidedEvent(systemId, adminSystemId,
                            Doctor.VERIFICATION_SUSPENDED, cleanReason));
                }
            });
        }

        authService.revokeAllSessions(systemId);
        auditService.logAccountEvent(adminSystemId, "USER_DEACTIVATE", RESOURCE, systemId, "SUCCESS");
        eventPublisher.publishEvent(new UserDeactivatedEvent(systemId, user.getRole(), adminSystemId,
                UserDeactivatedEvent.ADMIN));
        return AdminUserActionResponse.of(detail(user), MSG_DEACTIVATED);
    }

    // ==================== SESSIONS AND PASSWORD ====================

    /**
     * Force sign-out: revoke every session of the account (it stays active). Safe to repeat; allowed for inactive
     * and closed accounts and for the caller's own account. Audited SESSIONS_REVOKE on every call.
     */
    public AdminUserActionResponse revokeSessions(String systemId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        User user = findUser(systemId);
        authService.revokeAllSessions(systemId);
        auditService.logAccountEvent(adminSystemId, "SESSIONS_REVOKE", RESOURCE, systemId, "SUCCESS");
        return AdminUserActionResponse.of(detail(user), MSG_SESSIONS_REVOKED);
    }

    /**
     * Send the account a link to set a new password by publishing {@link AdminPasswordResetRequestedEvent}; the
     * account module issues the link, sends the email and writes the audit row in this transaction. Never sets or
     * returns a password, never ends sessions.
     *
     * @throws ConflictException "This account is closed" / "This account has no email address"
     */
    public AdminUserActionResponse requestPasswordReset(String systemId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        User user = findUser(systemId);
        if (user.getClosedAt() != null) {
            throw new ConflictException("This account is closed");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new ConflictException("This account has no email address");
        }
        eventPublisher.publishEvent(new AdminPasswordResetRequestedEvent(systemId, adminSystemId));
        return AdminUserActionResponse.of(detail(user), MSG_RESET_SENT);
    }

    // ==================== MAPPING ====================

    private User findUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private AdminUserDetailResponse detail(User user) {
        String systemId = user.getSystemId();
        LocalDateTime now = LocalDateTime.now(clock);
        Doctor doctor = "DOCTOR".equals(user.getRole())
                ? clinicianRepository.findBySystemId(systemId).orElse(null) : null;
        long futureAppointments = switch (user.getRole()) {
            case "DOCTOR" -> userRepository.countFutureActiveAppointmentsAsDoctor(systemId, now);
            case "PATIENT" -> userRepository.countFutureActiveAppointmentsAsPatient(systemId, now);
            default -> 0L;
        };
        AdminUserResponse s = summary(user, doctor == null ? null : doctor.getVerificationStatus());
        return new AdminUserDetailResponse(s.userId(), s.systemId(), s.firstName(), s.lastName(), s.email(), s.phone(),
                s.role(), s.isActive(), s.createdAt(), s.updatedAt(), s.emailVerified(), s.mustChangePassword(),
                s.closedAt(), s.anonymised(), s.verificationStatus(),
                user.getEmailVerifiedAt(),
                user.getPasswordChangedAt(),
                user.getPasswordHash() != null,
                user.getClosureReason(),
                userRepository.findDeactivationReason(systemId),
                userRepository.countActiveSessions(systemId, now),
                futureAppointments,
                doctor == null ? null : doctorMapper.toResponse(doctor, user));
    }

    private static AdminUserResponse summary(User u, String verificationStatus) {
        return new AdminUserResponse(u.getUserId(), u.getSystemId(), u.getFirstName(), u.getLastName(), u.getEmail(),
                u.getPhone(), u.getRole(), u.getIsActive(), u.getCreatedAt(), u.getUpdatedAt(),
                u.getEmailVerifiedAt() != null, Boolean.TRUE.equals(u.getMustChangePassword()), u.getClosedAt(),
                isAnonymised(u), "DOCTOR".equals(u.getRole()) ? verificationStatus : null);
    }

    /** Personal data removed after an approved deletion request: closed, no email, no password. */
    private static boolean isAnonymised(User u) {
        return u.getClosedAt() != null && u.getEmail() == null && u.getPasswordHash() == null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        if ("PROXY".equals(upper)) {
            upper = "CAREGIVER";
        }
        if (!ROLES.contains(upper)) {
            throw new BadRequestException("Invalid role. Must be PATIENT, DOCTOR, CAREGIVER or ADMIN");
        }
        return upper;
    }

    /** Escapes LIKE wildcards so {@code %} and {@code _} in the search text match literally. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
