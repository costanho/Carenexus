package com.carenexus.api.account.email.listener;

import com.carenexus.api.account.email.service.EmailVerificationService;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sends the verification link to every self-registered account with an email. A plain listener: it runs inside the
 * registration transaction, so a failure to queue the mail rolls the registration back. Accounts created by somebody
 * else ({@code createdBySystemId} set) are already verified and get nothing. Independent of
 * {@code carenexus.auth.require-verified-email}, which only controls the login gate.
 */
@Component
@RequiredArgsConstructor
public class AccountEmailRegistrationListener {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        if (event.createdBySystemId() != null || event.email() == null || event.email().isBlank()) {
            return;
        }
        userRepository.findBySystemId(event.systemId())
                .filter(user -> user.getEmailVerifiedAt() == null)
                .ifPresent(emailVerificationService::issueFor);
    }
}
