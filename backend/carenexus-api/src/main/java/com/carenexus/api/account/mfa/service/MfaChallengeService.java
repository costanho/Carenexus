package com.carenexus.api.account.mfa.service;

import com.carenexus.api.account.mfa.config.MfaProperties;
import com.carenexus.api.account.mfa.crypto.MfaSecretCipher;
import com.carenexus.api.account.mfa.crypto.MfaTokens;
import com.carenexus.api.account.mfa.dto.request.MfaChallengeRequest;
import com.carenexus.api.account.mfa.dto.request.MfaSetupConfirmRequest;
import com.carenexus.api.account.mfa.dto.request.MfaVerifyRequest;
import com.carenexus.api.account.mfa.dto.response.MfaSetupConfirmResponse;
import com.carenexus.api.account.mfa.dto.response.MfaSetupResponse;
import com.carenexus.api.account.mfa.model.MfaChallenge;
import com.carenexus.api.account.mfa.model.UserMfa;
import com.carenexus.api.account.mfa.repository.MfaChallengeRepository;
import com.carenexus.api.account.mfa.repository.UserMfaRepository;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.common.spi.LoginStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalLong;

/**
 * The MFA step of sign-in. Public endpoints {@code /api/auth/mfa/**} are authenticated by the challenge token that
 * {@link MfaLoginGate} returned from {@code POST /api/auth/login}:
 * <ul>
 *   <li>LOGIN challenge (client type {@code MFA}): {@link #verify} with a TOTP or recovery code issues the session;</li>
 *   <li>SETUP challenge (client type {@code MFA_SETUP}, forced enrolment of a required role):
 *       {@link #startSetup} returns a secret bound to the challenge, {@link #confirmSetup} enables MFA, returns the
 *       recovery codes and issues the session.</li>
 * </ul>
 * A challenge lives {@code carenexus.auth.mfa-challenge-ttl-seconds} (300 s) and dies after
 * {@link MfaChallenge#MAX_ATTEMPTS} wrong codes. The failed-attempt counter and the failure audit row are committed
 * although the request fails ({@code noRollbackFor}). The session is issued by {@link AuthService#completeLogin},
 * which re-applies the account-state refusals; its device description comes from the login request (stored on the
 * challenge), the address and user agent from this request.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class MfaChallengeService {

    private static final int DEVICE_TYPE_MAX = 50;
    private static final int DEVICE_INFO_MAX = 255;

    private final MfaChallengeRepository challengeRepository;
    private final UserMfaRepository userMfaRepository;
    private final RecoveryCodeService recoveryCodeService;
    private final MfaAccountService accountService;
    private final MfaSecretCipher cipher;
    private final TotpService totpService;
    private final MfaProperties properties;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditService auditService;
    private final RateLimitGuard rateLimitGuard;
    private final Clock clock;

    // ==================== LOGIN GATE ====================

    /**
     * Decision of the login gate (api/be-account-mfa.md 2.3), called after the password was verified, inside the login
     * transaction: MFA on -> challenge MFA; role required but not enrolled -> challenge MFA_SETUP; otherwise proceed.
     * A challenge is a new row per login; earlier challenges of the account stay valid until they expire.
     */
    public LoginStep challengeFor(User user, LoginContext ctx) {
        if (user == null || user.getSystemId() == null) {
            return LoginStep.proceed();
        }
        String purpose;
        String type;
        if (accountService.isEnabled(user.getSystemId())) {
            purpose = MfaChallenge.PURPOSE_LOGIN;
            type = LoginStep.MFA;
        } else if (properties.isRequiredFor(user.getRole())) {
            purpose = MfaChallenge.PURPOSE_SETUP;
            type = LoginStep.MFA_SETUP;
        } else {
            return LoginStep.proceed();
        }
        long ttl = properties.challengeTtlSeconds();
        String token = MfaTokens.newChallengeToken();
        LocalDateTime now = now();
        challengeRepository.save(MfaChallenge.builder()
                .challengeHash(MfaTokens.sha256Hex(token))
                .systemId(user.getSystemId())
                .purpose(purpose)
                .deviceType(clip(ctx == null ? null : ctx.deviceType(), DEVICE_TYPE_MAX))
                .deviceInfo(clip(ctx == null ? null : ctx.deviceInfo(), DEVICE_INFO_MAX))
                .attempts(0)
                .expiresAt(now.plusSeconds(ttl))
                .createdAt(now)
                .build());
        return LoginStep.challenge(type, token, ttl);
    }

    // ==================== VERIFY (MFA on) ====================

    /** Second sign-in step with a TOTP code or a recovery code (single use). Returns the session. */
    @Transactional(noRollbackFor = {MfaErrors.CodeInvalidException.class, MfaErrors.ChallengeInvalidException.class})
    public AuthResponse verify(MfaVerifyRequest request, String ip, String userAgent) {
        MfaAccountService.requireCodeOrRecoveryCode(request.getCode(), request.getRecoveryCode());
        MfaChallenge challenge = requireChallenge(request.getChallengeToken(), MfaChallenge.PURPOSE_LOGIN);
        String systemId = challenge.getSystemId();
        requireEligibleUser(systemId);
        UserMfa mfa = userMfaRepository.findForUpdate(systemId)
                .filter(UserMfa::isEnabled)
                .orElseThrow(() -> new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED));
        // Only now that the challenge was found; a refused call does not count as an attempt of the challenge.
        rateLimitGuard.check(MfaAccountService.BUCKET, systemId);

        if (request.getCode() != null) {
            OptionalLong step = totpService.verify(cipher.decrypt(mfa.getSecretEncrypted(), systemId),
                    request.getCode(), mfa.getLastUsedStep());
            if (step.isEmpty()) {
                throw failedAttempt(challenge, MfaErrors.M1_TOTP_INVALID);
            }
            mfa.setLastUsedStep(step.getAsLong());
            userMfaRepository.save(mfa);
        } else if (!recoveryCodeService.consume(systemId, request.getRecoveryCode())) {
            throw failedAttempt(challenge, MfaErrors.M2_RECOVERY_INVALID);
        }

        challengeRepository.delete(challenge);
        rateLimitGuard.reset(MfaAccountService.BUCKET, systemId);
        auditService.logAccountEvent(systemId, MfaAccountService.AUDIT_SUCCESS, MfaAccountService.RESOURCE, systemId,
                "SUCCESS");
        return authService.completeLogin(systemId, loginContext(challenge, ip, userAgent));
    }

    // ==================== FORCED ENROLMENT (role requires MFA) ====================

    /**
     * Secret for forced enrolment, bound to the challenge: generated on the first call, the same on repeated calls.
     * Neither extends the challenge nor counts as an attempt.
     */
    public MfaSetupResponse startSetup(MfaChallengeRequest request) {
        MfaChallenge challenge = requireChallenge(request.getChallengeToken(), MfaChallenge.PURPOSE_SETUP);
        User user = requireEligibleUser(challenge.getSystemId());
        requireNotEnrolled(user.getSystemId());

        byte[] secret;
        if (challenge.getPendingSecretEncrypted() == null) {
            secret = totpService.newSecret();
            challenge.setPendingSecretEncrypted(cipher.encrypt(secret, user.getSystemId()));
            challengeRepository.save(challenge);
        } else {
            secret = cipher.decrypt(challenge.getPendingSecretEncrypted(), user.getSystemId());
        }
        return accountService.setupResponse(user, secret);
    }

    /**
     * Proves the new authenticator works, enables MFA (overwriting a pending self-service setup), returns ten recovery
     * codes and issues the session, all in one transaction.
     */
    @Transactional(noRollbackFor = {MfaErrors.CodeInvalidException.class, MfaErrors.ChallengeInvalidException.class})
    public MfaSetupConfirmResponse confirmSetup(MfaSetupConfirmRequest request, String ip, String userAgent) {
        MfaChallenge challenge = requireChallenge(request.getChallengeToken(), MfaChallenge.PURPOSE_SETUP);
        String systemId = challenge.getSystemId();
        requireEligibleUser(systemId);
        requireNotEnrolled(systemId);
        if (challenge.getPendingSecretEncrypted() == null) {
            throw new ConflictException(MfaErrors.M7_SETUP_NOT_STARTED);
        }
        rateLimitGuard.check(MfaAccountService.BUCKET, systemId);

        OptionalLong step = totpService.verify(cipher.decrypt(challenge.getPendingSecretEncrypted(), systemId),
                request.getCode(), null);
        if (step.isEmpty()) {
            throw failedAttempt(challenge, MfaErrors.M1_TOTP_INVALID);
        }

        LocalDateTime now = now();
        UserMfa mfa = userMfaRepository.findForUpdate(systemId)
                .orElseGet(() -> UserMfa.builder().systemId(systemId).createdAt(now).build());
        // Same account, same key and same associated data: the stored ciphertext can be moved as is.
        mfa.setSecretEncrypted(challenge.getPendingSecretEncrypted());
        mfa.setEnabledAt(now);
        mfa.setLastUsedStep(step.getAsLong());
        userMfaRepository.save(mfa);
        List<String> codes = recoveryCodeService.replace(systemId);

        challengeRepository.delete(challenge);
        rateLimitGuard.reset(MfaAccountService.BUCKET, systemId);
        auditService.logAccountEvent(systemId, MfaAccountService.AUDIT_ENABLED, MfaAccountService.RESOURCE, systemId,
                "SUCCESS");
        AuthResponse session = authService.completeLogin(systemId, loginContext(challenge, ip, userAgent));
        return new MfaSetupConfirmResponse(codes, session);
    }

    // ==================== HELPERS ====================

    /**
     * The live challenge of this token and purpose, locked for the rest of the transaction. 401 M3 when the token is
     * unknown (consumed challenges are deleted), of the other purpose, expired, or already locked.
     */
    private MfaChallenge requireChallenge(String token, String purpose) {
        if (token == null || token.isBlank()) {
            throw new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED);
        }
        MfaChallenge challenge = challengeRepository.findByHashForUpdate(MfaTokens.sha256Hex(token))
                .orElseThrow(() -> new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED));
        if (!purpose.equals(challenge.getPurpose())
                || challenge.isLocked()
                || !challenge.getExpiresAt().isAfter(now())) {
            throw new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED);
        }
        return challenge;
    }

    /** The challenge's account, still able to sign in (active, not closed); otherwise 401 M3. */
    private User requireEligibleUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()) && user.getClosedAt() == null)
                .orElseThrow(() -> new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED));
    }

    /** A SETUP challenge is void once the account enabled MFA by another way. */
    private void requireNotEnrolled(String systemId) {
        if (accountService.isEnabled(systemId)) {
            throw new MfaErrors.ChallengeInvalidException(MfaErrors.M3_CHALLENGE_EXPIRED);
        }
    }

    /**
     * Counts a wrong code on the challenge and audits it. Returns the exception to throw: 400 with {@code message}
     * for attempts 1 to 4, 401 M4 for the attempt that locks the challenge.
     */
    private RuntimeException failedAttempt(MfaChallenge challenge, String message) {
        challenge.setAttempts(challenge.getAttempts() + 1);
        challengeRepository.saveAndFlush(challenge);
        try {
            auditService.logAccountEvent(null, MfaAccountService.AUDIT_FAILURE, MfaAccountService.RESOURCE,
                    challenge.getSystemId(), "FAILURE");
        } catch (RuntimeException e) {
            log.warn("Could not write {} audit entry: {}", MfaAccountService.AUDIT_FAILURE, e.getClass().getSimpleName());
        }
        if (challenge.isLocked()) {
            return new MfaErrors.ChallengeInvalidException(MfaErrors.M4_CHALLENGE_LOCKED);
        }
        return new MfaErrors.CodeInvalidException(message);
    }

    private static LoginContext loginContext(MfaChallenge challenge, String ip, String userAgent) {
        return new LoginContext(ip, userAgent, challenge.getDeviceType(), challenge.getDeviceInfo());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
