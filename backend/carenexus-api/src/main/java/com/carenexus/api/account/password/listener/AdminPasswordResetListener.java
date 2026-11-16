package com.carenexus.api.account.password.listener;

import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Turns an administrator's reset request, or a caregiver's invitation of a managed dependent, into an emailed
 * set-password link.
 *
 * A plain {@code @EventListener}: it runs inside the publisher's transaction, and an exception it throws reaches
 * the publisher's caller and rolls the publisher's change back, so the requesting button never appears to work
 * when no email can go out. The publishers (be-admin-users, be-caregiver-core) authorize the request; this listener
 * only refuses targets that cannot receive a link.
 *
 * It never sets a password, never sets {@code must_change_password} and never ends a session: the password changes
 * only when the target redeems the link. A target without a password yet (an invited managed dependent) gets the
 * same token with the invitation template, and the reset endpoint then sets the first password. No rate limit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminPasswordResetListener {

    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_ADMIN_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";

    static final String USER_NOT_FOUND_MESSAGE = "User not found";
    static final String ACCOUNT_CLOSED_MESSAGE = "This account is closed";
    static final String NO_EMAIL_MESSAGE = "This account has no email address";

    private final UserRepository userRepository;
    private final PasswordResetTokenService tokenService;
    private final AuditService auditService;

    /**
     * @throws ResourceNotFoundException 404 when the target does not exist
     * @throws ConflictException         409 when the target is closed or has no email address
     */
    @EventListener
    @Transactional
    public void onAdminPasswordResetRequested(AdminPasswordResetRequestedEvent event) {
        String targetSystemId = event.targetSystemId();
        User target = (targetSystemId == null ? Optional.<User>empty()
                : userRepository.findBySystemId(targetSystemId))
                .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_MESSAGE));
        if (target.getClosedAt() != null) {
            throw new ConflictException(ACCOUNT_CLOSED_MESSAGE);
        }
        if (target.getEmail() == null || target.getEmail().isBlank()) {
            throw new ConflictException(NO_EMAIL_MESSAGE);
        }

        boolean invitation = target.getPasswordHash() == null;
        PasswordResetTokenService.IssuedToken issued = tokenService.issue(target, null, event.adminSystemId());
        tokenService.sendLink(target, issued,
                invitation ? PasswordResetTokenService.TEMPLATE_INVITE : PasswordResetTokenService.TEMPLATE_RESET);
        auditService.logAccountEvent(event.adminSystemId(), ACTION_ADMIN_PASSWORD_RESET, RESOURCE,
                target.getSystemId(), "SUCCESS");
        log.info("{} link issued for {} at the request of {}", invitation ? "Invitation" : "Password reset",
                target.getSystemId(), event.adminSystemId());
    }
}
