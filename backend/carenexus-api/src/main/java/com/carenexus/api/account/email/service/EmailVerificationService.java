package com.carenexus.api.account.email.service;

import com.carenexus.api.account.email.config.AccountEmailProperties;
import com.carenexus.api.account.email.model.EmailVerificationToken;
import com.carenexus.api.account.email.repository.EmailVerificationTokenRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.delivery.OutboundMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Confirms the sign-in email of an account through a one-time link.
 *
 * <ul>
 *   <li>{@link #issueFor(User)} invalidates the account's outstanding tokens, stores a new one (hash only) and queues
 *       the mail {@value #TEMPLATE_VERIFY} in the caller's transaction.</li>
 *   <li>{@link #verify(String)} (public, the token is the credential) stamps {@code users.email_verified_at}.</li>
 *   <li>{@link #resend(String)} (public) issues a new link without revealing whether the address has an account.</li>
 * </ul>
 * The token is the only credential of the public methods, so there is no caller identity to authorize; the account
 * acted on is always the one the token (or the address) belongs to.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EmailVerificationService {

    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_VERIFIED = "EMAIL_VERIFIED";
    static final String TEMPLATE_VERIFY = "account-email-verify";

    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    public static final String TOKEN_INVALID_MESSAGE =
            "This verification link is invalid or has expired. Request a new one.";
    public static final String VERIFIED_MESSAGE = "Your email address has been verified.";
    public static final String RESEND_MESSAGE =
            "If that address belongs to an account that still needs verification, we have sent a new link.";

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final OutboundMessageService outboundMessageService;
    private final AuditService auditService;
    private final AccountEmailProperties properties;
    private final AccountEmailLinks links;
    private final Clock clock;

    /**
     * Sends a new verification link to the account's current email. Earlier unused links of the account stop working.
     * Does nothing for an account without an email. Must run inside the caller's transaction so a rolled-back change
     * sends nothing.
     *
     * @return the id of the new token, or empty when the account has no email
     */
    public Optional<Integer> issueFor(User user) {
        String email = OneTimeTokens.normalizeEmail(user.getEmail());
        if (email == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        tokenRepository.invalidateOutstanding(user.getSystemId(), now);

        String rawToken = OneTimeTokens.generate();
        int ttlHours = properties.safeEmailVerificationTtlHours();
        EmailVerificationToken token = tokenRepository.save(EmailVerificationToken.builder()
                .systemId(user.getSystemId())
                .email(email)
                .tokenHash(OneTimeTokens.hash(rawToken))
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .createdAt(now)
                .build());

        Map<String, String> model = new LinkedHashMap<>();
        model.put("firstName", OneTimeTokens.greetingName(user.getFirstName()));
        model.put("link", links.verifyEmail(rawToken));
        model.put("validHours", String.valueOf(ttlHours));
        outboundMessageService.queueEmail(email, user.getSystemId(), TEMPLATE_VERIFY, model,
                "EMAIL_VERIFY:" + token.getTokenId());
        log.info("Email verification link issued for {} (token {})", user.getSystemId(), token.getTokenId());
        return Optional.of(token.getTokenId());
    }

    /**
     * Confirms the address the token was issued for. Stamps {@code email_verified_at} (kept when already set), marks
     * the token used, invalidates the account's other outstanding tokens and audits EMAIL_VERIFIED. Works for an
     * inactive account (a clinician awaiting approval), not for a closed one.
     *
     * @throws BadRequestException code TOKEN_INVALID when the token is unknown, used, superseded, expired, issued for
     *                             an address that is no longer the account's email, or the account was closed
     */
    public void verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }
        Instant now = clock.instant();
        EmailVerificationToken token = tokenRepository.findByTokenHashForUpdate(OneTimeTokens.hash(rawToken))
                .orElseThrow(EmailVerificationService::invalidToken);
        if (token.getUsedAt() != null || !token.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }
        User user = userRepository.findBySystemId(token.getSystemId())
                .filter(u -> u.getClosedAt() == null)
                .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(token.getEmail()))
                .orElseThrow(EmailVerificationService::invalidToken);

        token.setUsedAt(now);
        tokenRepository.save(token);
        tokenRepository.invalidateOutstanding(user.getSystemId(), now);

        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(LocalDateTime.now(clock));
            userRepository.save(user);
        }
        auditService.logAccountEvent(user.getSystemId(), ACTION_VERIFIED, RESOURCE, user.getSystemId(), "SUCCESS");
    }

    /**
     * Sends a new verification link when {@code rawEmail} belongs to an account whose email is not verified and that
     * is not closed ({@code is_active} is not checked: a clinician awaiting approval may verify). In every other case
     * nothing is written. The caller always answers with the same generic message.
     */
    public void resend(String rawEmail) {
        String email = OneTimeTokens.normalizeEmail(rawEmail);
        if (email == null || !OneTimeTokens.EMAIL.matcher(email).matches()) {
            return;
        }
        userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(email)
                .filter(user -> user.getEmailVerifiedAt() == null)
                .filter(user -> user.getClosedAt() == null)
                .ifPresent(this::issueFor);
    }

    /**
     * Invalidates every outstanding verification token of the account (its email changed).
     */
    public void invalidateAll(String systemId) {
        tokenRepository.invalidateOutstanding(systemId, clock.instant());
    }

    private static BadRequestException invalidToken() {
        return new BadRequestException(TOKEN_INVALID, TOKEN_INVALID_MESSAGE);
    }
}
