package com.carenexus.api.account.password.service;

import com.carenexus.api.account.password.dto.request.ForgotPasswordRequest;
import com.carenexus.api.account.password.dto.request.ResetPasswordRequest;
import com.carenexus.api.account.password.model.PasswordResetToken;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.provider.HumanVerification;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.event.PasswordChangedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Self-service account recovery: {@code POST /api/auth/password/forgot} and {@code POST /api/auth/password/reset}.
 *
 * Both endpoints are public. Forgot-password never reveals whether an account exists: every well-formed request
 * that is not rate limited gets the same answer, and only an eligible account (active, not closed, with a password)
 * gets a link. The reset is authorized by the token alone; one answer covers every unusable token.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordRecoveryService {

    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_PASSWORD_RESET = "PASSWORD_RESET";

    public static final String BUCKET_FORGOT_IP = "auth-forgot-ip";
    public static final String BUCKET_FORGOT_EMAIL = "auth-forgot-email";

    public static final String FORGOT_MESSAGE = "If an account exists for that email, a password reset link has been sent.";
    public static final String RESET_MESSAGE = "Your password has been reset. You can now sign in.";
    public static final String CODE_TOKEN_INVALID = "PASSWORD_RESET_TOKEN_INVALID";
    public static final String TOKEN_INVALID_MESSAGE = "This link is invalid or has expired. Please request a new one.";

    private final UserRepository userRepository;
    private final PasswordResetTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final HumanVerification humanVerification;
    private final RateLimitGuard rateLimitGuard;

    /**
     * Checks and counts a forgot-password request before any database work: human verification first, then the
     * per-address and per-email buckets. Every well-formed request counts, whether or not the account exists, so a
     * 429 says nothing about the account. Not transactional: a flood never holds database connections.
     *
     * @param ip client address from {@code request.getRemoteAddr()}
     */
    public void guardForgotRequest(ForgotPasswordRequest request, String ip) {
        humanVerification.verify(HumanVerification.ACTION_PASSWORD_FORGOT, request.getHumanVerificationToken(), ip);
        rateLimitGuard.check(BUCKET_FORGOT_IP, ip == null ? "unknown" : ip);
        rateLimitGuard.check(BUCKET_FORGOT_EMAIL, normalizeEmail(request.getEmail()));
    }

    /**
     * Issues a reset link for an eligible account and queues the email {@code account-password-reset}. Unknown,
     * inactive, closed and managed (no password) accounts get nothing: no token, no outbox row, no audit row.
     * Callers must run {@link #guardForgotRequest} first.
     *
     * @return always {@link #FORGOT_MESSAGE}
     */
    @Transactional
    public String requestReset(ForgotPasswordRequest request, String ip) {
        String email = normalizeEmail(request.getEmail());
        findByEmail(email)
                .filter(PasswordRecoveryService::eligibleForSelfServiceReset)
                .ifPresent(user -> {
                    PasswordResetTokenService.IssuedToken issued = tokenService.issue(user, ip, null);
                    tokenService.sendLink(user, issued, PasswordResetTokenService.TEMPLATE_RESET);
                });
        return FORGOT_MESSAGE;
    }

    /**
     * Sets a new password with an emailed token (also the first password of an invited account). In one
     * transaction: password, {@code password_changed_at}, cleared {@code must_change_password}, token redeemed and
     * the user's other tokens superseded, every session ended, audit {@code PASSWORD_RESET},
     * {@link PasswordChangedEvent} ({@code RESET}, or {@code ADMIN_RESET} for a link someone else requested) and the
     * {@code account-password-changed} notice.
     *
     * @throws BadRequestException {@value #CODE_TOKEN_INVALID} for an unknown, used, superseded or expired token, or
     *         one whose account no longer exists or is closed
     */
    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        PasswordResetToken token = tokenService.findUsableForUpdate(request.getToken())
                .orElseThrow(PasswordRecoveryService::invalidToken);
        User user = userRepository.findBySystemId(token.getSystemId())
                .filter(account -> account.getClosedAt() == null)
                .orElseThrow(PasswordRecoveryService::invalidToken);

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(tokenService.now());
        user.setMustChangePassword(false);
        userRepository.save(user);

        tokenService.redeem(token);
        authService.revokeAllSessions(user.getSystemId());
        auditService.logAccountEvent(user.getSystemId(), ACTION_PASSWORD_RESET, RESOURCE, user.getSystemId(), "SUCCESS");
        eventPublisher.publishEvent(new PasswordChangedEvent(user.getSystemId(),
                token.isRequestedByAnotherUser() ? PasswordChangedEvent.ADMIN_RESET : PasswordChangedEvent.RESET));
        tokenService.sendPasswordChangedNotice(user);
        log.info("Password reset completed for {}", user.getSystemId());
        return RESET_MESSAGE;
    }

    /** Active, not closed, and has a password (a managed dependent without sign-in is invited, not reset). */
    static boolean eligibleForSelfServiceReset(User user) {
        return Boolean.TRUE.equals(user.getIsActive())
                && user.getClosedAt() == null
                && user.getPasswordHash() != null
                && user.getEmail() != null;
    }

    private Optional<User> findByEmail(String email) {
        if (email == null || email.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> exact = userRepository.findByEmail(email);
        return exact.isPresent() ? exact : userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(email);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static BadRequestException invalidToken() {
        return new BadRequestException(CODE_TOKEN_INVALID, TOKEN_INVALID_MESSAGE);
    }
}
