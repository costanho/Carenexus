package com.carenexus.api.account.mfa.service;

import com.carenexus.api.account.mfa.config.MfaProperties;
import com.carenexus.api.account.mfa.crypto.MfaSecretCipher;
import com.carenexus.api.account.mfa.dto.request.MfaDisableRequest;
import com.carenexus.api.account.mfa.dto.response.MfaRecoveryCodesResponse;
import com.carenexus.api.account.mfa.dto.response.MfaSetupResponse;
import com.carenexus.api.account.mfa.dto.response.MfaStatusResponse;
import com.carenexus.api.account.mfa.model.UserMfa;
import com.carenexus.api.account.mfa.repository.MfaChallengeRepository;
import com.carenexus.api.account.mfa.repository.UserMfaRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.OptionalLong;

/**
 * Two-factor authentication of the signed-in account ({@code /api/users/me/mfa/**}) and the administrator's view and
 * reset ({@code /api/admin/users/{systemId}/mfa/**}).
 *
 * Every self-service method acts on the caller's own account only (identity from the JWT); no caregiver permission
 * applies. State machine (api/be-account-mfa.md 2.2): OFF -> PENDING (setup) -> ON (enable) -> OFF (disable, only
 * for roles outside {@code carenexus.auth.mfa-required-roles}); an administrator resets ON or PENDING to OFF.
 *
 * A wrong code or password is 400 (never 401: the web client refreshes and retries on 401). Every call that checks
 * a credential first consumes one slot of the {@code auth-mfa} bucket of the account; a success resets it.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MfaAccountService {

    /** Audit resource type of account and security events. */
    static final String RESOURCE = "ACCOUNT";
    /** Rate-limit bucket shared by every endpoint that checks an MFA code or the password (5 per 300 s). */
    public static final String BUCKET = "auth-mfa";

    public static final String AUDIT_SUCCESS = "MFA_SUCCESS";
    public static final String AUDIT_FAILURE = "MFA_FAILURE";
    public static final String AUDIT_ENABLED = "MFA_ENABLED";
    public static final String AUDIT_DISABLED = "MFA_DISABLED";
    public static final String AUDIT_REGENERATED = "MFA_RECOVERY_CODES_REGENERATED";
    public static final String AUDIT_RESET = "MFA_RESET";

    private final UserMfaRepository userMfaRepository;
    private final MfaChallengeRepository challengeRepository;
    private final RecoveryCodeService recoveryCodeService;
    private final MfaSecretCipher cipher;
    private final TotpService totpService;
    private final MfaProperties properties;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final RateLimitGuard rateLimitGuard;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    // ==================== SELF SERVICE ====================

    /** MFA state of the caller. */
    @Transactional(readOnly = true)
    public MfaStatusResponse myStatus() {
        return statusOf(requireUser(authorizationService.currentSystemId()));
    }

    /**
     * Starts enrolment, or continues a pending one: the first call stores a new encrypted secret, later calls return
     * the same secret until MFA is enabled or reset. 409 when MFA is already on.
     */
    public MfaSetupResponse startSetup() {
        String me = authorizationService.currentSystemId();
        User user = requireUser(me);
        UserMfa row = userMfaRepository.findForUpdate(me).orElse(null);
        if (row == null) {
            // ON CONFLICT DO NOTHING: a concurrent first call cannot fail; both read the winner's secret below.
            userMfaRepository.insertPendingIfAbsent(me, cipher.encrypt(totpService.newSecret(), me), now());
            row = userMfaRepository.findForUpdate(me)
                    .orElseThrow(() -> new IllegalStateException("Pending MFA enrolment was not stored"));
        }
        if (row.isEnabled()) {
            throw new ConflictException(MfaErrors.M5_ALREADY_ENABLED);
        }
        return setupResponse(user, cipher.decrypt(row.getSecretEncrypted(), me));
    }

    /**
     * Confirms the pending setup with a code and turns MFA on. Returns ten new recovery codes (shown once).
     * Existing sessions are not revoked.
     */
    public MfaRecoveryCodesResponse enable(String code) {
        String me = authorizationService.currentSystemId();
        requireUser(me);
        UserMfa row = userMfaRepository.findForUpdate(me)
                .orElseThrow(() -> new ConflictException(MfaErrors.M7_SETUP_NOT_STARTED));
        if (row.isEnabled()) {
            throw new ConflictException(MfaErrors.M5_ALREADY_ENABLED);
        }
        rateLimitGuard.check(BUCKET, me);

        OptionalLong step = totpService.verify(cipher.decrypt(row.getSecretEncrypted(), me), code, row.getLastUsedStep());
        if (step.isEmpty()) {
            auditFailure(me, me);
            throw new MfaErrors.CodeInvalidException(MfaErrors.M1_TOTP_INVALID);
        }
        row.setEnabledAt(now());
        row.setLastUsedStep(step.getAsLong());
        userMfaRepository.save(row);
        MfaRecoveryCodesResponse codes = new MfaRecoveryCodesResponse(recoveryCodeService.replace(me));

        auditService.logAccountEvent(me, AUDIT_ENABLED, RESOURCE, me, "SUCCESS");
        rateLimitGuard.reset(BUCKET, me);
        return codes;
    }

    /**
     * Turns MFA off with the current password plus a TOTP code or a recovery code. Deletes the secret, every
     * recovery code and every live sign-in challenge of the account. Refused (409 MFA_REQUIRED) for a role that must
     * keep MFA. Existing sessions are not revoked.
     */
    public MfaStatusResponse disable(MfaDisableRequest request) {
        requireCodeOrRecoveryCode(request.getCode(), request.getRecoveryCode());
        String me = authorizationService.currentSystemId();
        User user = requireUser(me);
        if (properties.isRequiredFor(user.getRole())) {
            throw new ConflictException(ErrorResponse.MFA_REQUIRED, MfaErrors.M8_REQUIRED_FOR_ROLE);
        }
        UserMfa row = userMfaRepository.findForUpdate(me)
                .filter(UserMfa::isEnabled)
                .orElseThrow(() -> new ConflictException(MfaErrors.M6_NOT_ENABLED));
        rateLimitGuard.check(BUCKET, me);

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditFailure(me, me);
            throw new BadRequestException(MfaErrors.M9_WRONG_PASSWORD);
        }
        if (request.getCode() != null) {
            OptionalLong step = totpService.verify(cipher.decrypt(row.getSecretEncrypted(), me), request.getCode(),
                    row.getLastUsedStep());
            if (step.isEmpty()) {
                auditFailure(me, me);
                throw new MfaErrors.CodeInvalidException(MfaErrors.M1_TOTP_INVALID);
            }
        } else if (!recoveryCodeService.consume(me, request.getRecoveryCode())) {
            auditFailure(me, me);
            throw new MfaErrors.CodeInvalidException(MfaErrors.M2_RECOVERY_INVALID);
        }

        userMfaRepository.delete(row);
        recoveryCodeService.deleteAll(me);
        challengeRepository.deleteBySystemId(me);

        auditService.logAccountEvent(me, AUDIT_DISABLED, RESOURCE, me, "SUCCESS");
        rateLimitGuard.reset(BUCKET, me);
        return MfaStatusResponse.off(false);
    }

    /** Replaces every recovery code with ten new ones; needs a fresh TOTP code (a recovery code is not accepted). */
    public MfaRecoveryCodesResponse regenerateRecoveryCodes(String code) {
        String me = authorizationService.currentSystemId();
        requireUser(me);
        UserMfa row = userMfaRepository.findForUpdate(me)
                .filter(UserMfa::isEnabled)
                .orElseThrow(() -> new ConflictException(MfaErrors.M6_NOT_ENABLED));
        rateLimitGuard.check(BUCKET, me);

        OptionalLong step = totpService.verify(cipher.decrypt(row.getSecretEncrypted(), me), code, row.getLastUsedStep());
        if (step.isEmpty()) {
            auditFailure(me, me);
            throw new MfaErrors.CodeInvalidException(MfaErrors.M1_TOTP_INVALID);
        }
        row.setLastUsedStep(step.getAsLong());
        userMfaRepository.save(row);
        MfaRecoveryCodesResponse codes = new MfaRecoveryCodesResponse(recoveryCodeService.replace(me));

        auditService.logAccountEvent(me, AUDIT_REGENERATED, RESOURCE, me, "SUCCESS");
        rateLimitGuard.reset(BUCKET, me);
        return codes;
    }

    // ==================== ADMINISTRATOR ====================

    /** MFA state of any account (ADMIN only); {@code required} follows the target's role. */
    @Transactional(readOnly = true)
    public MfaStatusResponse statusForAdmin(String systemId) {
        authorizationService.requireAdmin();
        User target = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException(MfaErrors.M11_USER_NOT_FOUND));
        return statusOf(target);
    }

    /**
     * Removes the enrolment of any account (ADMIN only, own account included): secret (enabled or pending), recovery
     * codes and live challenges. Idempotent; audited MFA_RESET on every call. Sessions are not revoked. With a
     * required role, the next sign-in of the target forces enrolment again.
     */
    public MfaStatusResponse resetForAdmin(String systemId) {
        authorizationService.requireAdmin();
        String adminSystemId = authorizationService.currentSystemId();
        User target = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException(MfaErrors.M11_USER_NOT_FOUND));

        userMfaRepository.deleteBySystemId(target.getSystemId());
        recoveryCodeService.deleteAll(target.getSystemId());
        challengeRepository.deleteBySystemId(target.getSystemId());

        auditService.logAccountEvent(adminSystemId, AUDIT_RESET, RESOURCE, target.getSystemId(), "SUCCESS");
        log.info("MFA reset for {} by {}", target.getSystemId(), adminSystemId);
        return MfaStatusResponse.off(properties.isRequiredFor(target.getRole()));
    }

    // ==================== SHARED WITH THE LOGIN FLOW ====================

    /** True when the account has MFA turned on (used by the login gate and the session flag). */
    @Transactional(readOnly = true)
    public boolean isEnabled(String systemId) {
        return systemId != null && userMfaRepository.existsBySystemIdAndEnabledAtIsNotNull(systemId);
    }

    MfaStatusResponse statusOf(User user) {
        boolean required = properties.isRequiredFor(user.getRole());
        UserMfa row = userMfaRepository.findById(user.getSystemId()).orElse(null);
        if (row == null || !row.isEnabled()) {
            return MfaStatusResponse.off(required);
        }
        return new MfaStatusResponse(true, required, recoveryCodeService.remaining(user.getSystemId()),
                toInstant(row.getEnabledAt()));
    }

    MfaSetupResponse setupResponse(User user, byte[] secret) {
        String base32 = totpService.toBase32(secret);
        String label = user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : user.getSystemId();
        return new MfaSetupResponse(base32, totpService.otpauthUri(label, base32));
    }

    /** 400 VALIDATION_FAILED (M10) unless exactly one of the two is present. */
    static void requireCodeOrRecoveryCode(String code, String recoveryCode) {
        if ((code == null) == (recoveryCode == null)) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, MfaErrors.M10_CODE_OR_RECOVERY);
        }
    }

    private void auditFailure(String actorSystemId, String targetSystemId) {
        try {
            auditService.logAccountEvent(actorSystemId, AUDIT_FAILURE, RESOURCE, targetSystemId, "FAILURE");
        } catch (RuntimeException e) {
            log.warn("Could not write {} audit entry: {}", AUDIT_FAILURE, e.getClass().getSimpleName());
        }
    }

    private User requireUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException(MfaErrors.M11_USER_NOT_FOUND));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }
}
