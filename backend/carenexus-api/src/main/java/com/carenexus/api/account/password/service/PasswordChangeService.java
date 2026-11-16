package com.carenexus.api.account.password.service;

import com.carenexus.api.account.password.dto.request.ChangePasswordRequest;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.event.PasswordChangedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/users/me/password}: the signed-in user changes their own password. Identity comes from the
 * access token only; nobody can change another person's password here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordChangeService {

    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_PASSWORD_CHANGE = "PASSWORD_CHANGE";

    /** Shared with the other password-confirming forms of the account area (5 per 900 s per account). */
    public static final String BUCKET_PASSWORD_CHANGE = "password-change";

    public static final String CHANGED_MESSAGE = "Your password has been changed.";
    public static final String CODE_CURRENT_PASSWORD_INCORRECT = "CURRENT_PASSWORD_INCORRECT";
    public static final String CURRENT_PASSWORD_INCORRECT_MESSAGE = "Current password is incorrect";
    public static final String CODE_PASSWORD_UNCHANGED = "PASSWORD_UNCHANGED";
    public static final String PASSWORD_UNCHANGED_MESSAGE = "New password must be different from your current password";

    private final AuthorizationService authorizationService;
    private final UserRepository userRepository;
    private final PasswordResetTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitGuard rateLimitGuard;

    /**
     * Changes the caller's password.
     * <ol>
     *   <li>Counts the request in bucket {@value #BUCKET_PASSWORD_CHANGE} (key = the caller's system id).</li>
     *   <li>Verifies the current password: a mismatch is 400 {@value #CODE_CURRENT_PASSWORD_INCORRECT} (never
     *       401) and an audit row {@code PASSWORD_CHANGE} with status FAILURE.</li>
     *   <li>Refuses a new password equal to the current one (400 {@value #CODE_PASSWORD_UNCHANGED}).</li>
     *   <li>Stores the new hash, stamps {@code password_changed_at}, clears {@code must_change_password},
     *       supersedes unused reset links, ends every other session (all of them when {@code keepSessionId} is
     *       absent), resets the bucket, audits {@code PASSWORD_CHANGE}, publishes {@link PasswordChangedEvent}
     *       {@code CHANGE} and queues the {@code account-password-changed} notice.</li>
     * </ol>
     */
    @Transactional
    public String changeOwnPassword(ChangePasswordRequest request) {
        String systemId = authorizationService.currentSystemId();
        rateLimitGuard.check(BUCKET_PASSWORD_CHANGE, systemId);

        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            // Written in its own transaction, so the row survives the rollback of this one.
            auditService.logAccountEvent(systemId, ACTION_PASSWORD_CHANGE, RESOURCE, systemId, "FAILURE");
            throw new BadRequestException(CODE_CURRENT_PASSWORD_INCORRECT, CURRENT_PASSWORD_INCORRECT_MESSAGE);
        }
        // The current password was just verified, so comparing the plain values is the same test as matching the hash.
        if (request.getNewPassword().equals(request.getCurrentPassword())) {
            throw new BadRequestException(CODE_PASSWORD_UNCHANGED, PASSWORD_UNCHANGED_MESSAGE);
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(tokenService.now());
        user.setMustChangePassword(false);
        userRepository.save(user);

        tokenService.invalidateAll(systemId);
        int ended = authService.revokeOtherSessions(systemId, request.getKeepSessionId());
        rateLimitGuard.reset(BUCKET_PASSWORD_CHANGE, systemId);
        auditService.logAccountEvent(systemId, ACTION_PASSWORD_CHANGE, RESOURCE, systemId, "SUCCESS");
        eventPublisher.publishEvent(new PasswordChangedEvent(systemId, PasswordChangedEvent.CHANGE));
        tokenService.sendPasswordChangedNotice(user);
        log.info("Password changed for {} ({} other session(s) ended)", systemId, ended);
        return CHANGED_MESSAGE;
    }
}
