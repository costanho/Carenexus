package com.carenexus.api.account.email.service;

import com.carenexus.api.account.email.config.AccountEmailProperties;
import com.carenexus.api.account.email.dto.response.EmailChangeConfirmResponse;
import com.carenexus.api.account.email.dto.response.EmailChangeResponse;
import com.carenexus.api.account.email.model.EmailChangeRequest;
import com.carenexus.api.account.email.repository.AccountEmailUserRepository;
import com.carenexus.api.account.email.repository.EmailChangeRequestRepository;
import com.carenexus.api.account.password.service.PasswordResetTokenService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.Redaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Change of the sign-in email, confirmed through a link sent to the new address.
 *
 * <ul>
 *   <li>{@link #requestChange}, {@link #pending}, {@link #cancel}: the signed-in user, for their own account only
 *       (identity from the access token; there is no variant for another account, an administrator included).</li>
 *   <li>{@link #confirm} (public): the token is the credential; the change is applied to the account that asked for
 *       it, whoever is signed in where the link is opened.</li>
 * </ul>
 * A user has at most one pending request: a new request replaces the earlier one.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EmailChangeService {

    static final String RESOURCE = "ACCOUNT";
    static final String ACTION_REQUESTED = "EMAIL_CHANGE_REQUESTED";
    static final String ACTION_CANCELLED = "EMAIL_CHANGE_CANCELLED";
    static final String ACTION_CHANGED = "EMAIL_CHANGE";

    static final String TEMPLATE_CONFIRM = "account-email-change-confirm";
    static final String TEMPLATE_NOTICE = "account-email-change-notice";
    static final String TEMPLATE_CHANGED = "account-email-changed";

    /** Shared with {@code POST /api/users/me/password}: both verify the current password. */
    public static final String BUCKET_PASSWORD_CHANGE = "password-change";

    public static final String INVALID_CURRENT_PASSWORD = "INVALID_CURRENT_PASSWORD";
    public static final String INVALID_CURRENT_PASSWORD_MESSAGE = "Current password is incorrect";
    public static final String EMAIL_UNCHANGED = "EMAIL_UNCHANGED";
    public static final String EMAIL_UNCHANGED_MESSAGE = "New email must be different from your current email";
    public static final String EMAIL_TAKEN_MESSAGE = "Email already registered";
    public static final String TOKEN_INVALID = EmailVerificationService.TOKEN_INVALID;
    public static final String TOKEN_INVALID_MESSAGE =
            "This confirmation link is invalid or has expired. Request the change again from your account settings.";
    public static final String CHANGED_MESSAGE =
            "Your email address has been changed. Sign in with your new email address.";

    private final EmailChangeRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final AccountEmailUserRepository accountEmailUserRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetTokenService passwordResetTokenService;
    private final AuthService authService;
    private final AuthorizationService authorizationService;
    private final RateLimitGuard rateLimitGuard;
    private final PasswordEncoder passwordEncoder;
    private final OutboundMessageService outboundMessageService;
    private final AuditService auditService;
    private final AccountEmailProperties properties;
    private final AccountEmailLinks links;
    private final Clock clock;

    /**
     * Starts a change of the caller's sign-in email. Order: rate limit (bucket {@value #BUCKET_PASSWORD_CHANGE} keyed
     * by the caller, every well-formed call counts), current password, unchanged address, address taken. On success
     * any earlier pending request is replaced, the confirmation link goes to the new address, a notice goes to the
     * current one, and EMAIL_CHANGE_REQUESTED is audited. The account itself is not changed.
     *
     * @throws BadRequestException INVALID_CURRENT_PASSWORD or EMAIL_UNCHANGED
     * @throws ConflictException   another account already uses the address
     */
    public EmailChangeResponse requestChange(String currentPassword, String rawNewEmail) {
        String systemId = authorizationService.currentSystemId();
        rateLimitGuard.check(BUCKET_PASSWORD_CHANGE, systemId);

        User user = userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getPasswordHash() == null || currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadRequestException(INVALID_CURRENT_PASSWORD, INVALID_CURRENT_PASSWORD_MESSAGE);
        }
        String newEmail = OneTimeTokens.normalizeEmail(rawNewEmail);
        if (newEmail == null || !OneTimeTokens.EMAIL.matcher(newEmail).matches() || newEmail.length() > 150) {
            throw new BadRequestException("Please enter a valid email address");
        }
        if (user.getEmail() != null && newEmail.equalsIgnoreCase(user.getEmail().trim())) {
            throw new BadRequestException(EMAIL_UNCHANGED, EMAIL_UNCHANGED_MESSAGE);
        }
        if (accountEmailUserRepository.emailUsedByAnotherAccount(newEmail, systemId)) {
            throw new ConflictException(EMAIL_TAKEN_MESSAGE);
        }

        Instant now = clock.instant();
        // Replaces the earlier request (expired ones included): its link stops working.
        requestRepository.cancelOpen(systemId, now);

        String rawToken = OneTimeTokens.generate();
        int ttlHours = properties.safeEmailChangeTtlHours();
        EmailChangeRequest request = requestRepository.save(EmailChangeRequest.builder()
                .systemId(systemId)
                .newEmail(newEmail)
                .tokenHash(OneTimeTokens.hash(rawToken))
                .expiresAt(now.plus(ttlHours, ChronoUnit.HOURS))
                .createdAt(now)
                .build());

        String firstName = OneTimeTokens.greetingName(user.getFirstName());
        Map<String, String> confirmModel = new LinkedHashMap<>();
        confirmModel.put("firstName", firstName);
        confirmModel.put("link", links.confirmEmailChange(rawToken));
        confirmModel.put("validHours", String.valueOf(ttlHours));
        outboundMessageService.queueEmail(newEmail, systemId, TEMPLATE_CONFIRM, confirmModel,
                "EMAIL_CHANGE_CONFIRM:" + request.getRequestId());

        String currentEmail = OneTimeTokens.normalizeEmail(user.getEmail());
        if (currentEmail != null) {
            Map<String, String> noticeModel = new LinkedHashMap<>();
            noticeModel.put("firstName", firstName);
            noticeModel.put("maskedNewEmail", Redaction.maskEmail(newEmail));
            outboundMessageService.queueEmail(currentEmail, systemId, TEMPLATE_NOTICE, noticeModel,
                    "EMAIL_CHANGE_NOTICE:" + request.getRequestId());
        }

        auditService.logAccountEvent(systemId, ACTION_REQUESTED, RESOURCE, systemId, "SUCCESS");
        log.info("Email change requested for {} (request {})", systemId, request.getRequestId());
        return toResponse(request);
    }

    /** The caller's pending request (not confirmed, cancelled or replaced, and not expired), if any. Not audited. */
    @Transactional(readOnly = true)
    public Optional<EmailChangeResponse> pending() {
        String systemId = authorizationService.currentSystemId();
        Instant now = clock.instant();
        return requestRepository.findFirstBySystemIdAndUsedAtIsNullAndCancelledAtIsNullOrderByRequestIdDesc(systemId)
                .filter(request -> request.isPendingAt(now))
                .map(EmailChangeService::toResponse);
    }

    /**
     * Cancels the caller's pending request; its link stops working at once. Idempotent. Audits
     * EMAIL_CHANGE_CANCELLED only when a pending request was cancelled. No mail is sent.
     *
     * @return true when a pending request was cancelled
     */
    public boolean cancel() {
        String systemId = authorizationService.currentSystemId();
        Instant now = clock.instant();
        int pending = requestRepository.cancelPending(systemId, now);
        // Expired leftovers are closed as well, so they never block anything.
        requestRepository.cancelOpen(systemId, now);
        if (pending > 0) {
            auditService.logAccountEvent(systemId, ACTION_CANCELLED, RESOURCE, systemId, "SUCCESS");
            return true;
        }
        return false;
    }

    /**
     * Applies a pending change. On success, in one transaction: the account's email becomes the new address and is
     * stamped verified, the request is marked used, outstanding verification tokens and password-reset links (both
     * mailed to the old address) are invalidated, every session of the account is revoked, EMAIL_CHANGE is audited
     * and a notice is queued to the old address.
     *
     * @throws BadRequestException code TOKEN_INVALID when the token is unknown, used, cancelled, replaced or expired,
     *                             or the account is inactive or closed
     * @throws ConflictException   another account took the address after the request was made (nothing changes; the
     *                             request stays pending)
     */
    public EmailChangeConfirmResponse confirm(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }
        Instant now = clock.instant();
        EmailChangeRequest request = requestRepository.findByTokenHashForUpdate(OneTimeTokens.hash(rawToken))
                .filter(r -> r.isPendingAt(now))
                .orElseThrow(EmailChangeService::invalidToken);
        User user = userRepository.findBySystemId(request.getSystemId())
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> u.getClosedAt() == null)
                .orElseThrow(EmailChangeService::invalidToken);

        String newEmail = request.getNewEmail();
        String systemId = user.getSystemId();
        if (accountEmailUserRepository.emailUsedByAnotherAccount(newEmail, systemId)) {
            throw new ConflictException(EMAIL_TAKEN_MESSAGE);
        }

        String oldEmail = OneTimeTokens.normalizeEmail(user.getEmail());
        user.setEmail(newEmail);
        user.setEmailVerifiedAt(LocalDateTime.now(clock));
        // Flushed now so a race on the unique email index fails here (409) before anything else is written.
        userRepository.saveAndFlush(user);

        request.setUsedAt(now);
        requestRepository.save(request);
        emailVerificationService.invalidateAll(systemId);
        // A reset link already mailed to the old address must not outlive the move to the new one.
        passwordResetTokenService.invalidateAll(systemId);
        authService.revokeAllSessions(systemId);
        auditService.logAccountEvent(systemId, ACTION_CHANGED, RESOURCE, systemId, "SUCCESS");

        if (oldEmail != null && !oldEmail.equals(newEmail)) {
            Map<String, String> model = new LinkedHashMap<>();
            model.put("firstName", OneTimeTokens.greetingName(user.getFirstName()));
            outboundMessageService.queueEmail(oldEmail, systemId, TEMPLATE_CHANGED, model,
                    "EMAIL_CHANGED:" + request.getRequestId());
        }
        log.info("Email changed for {} (request {})", systemId, request.getRequestId());
        return new EmailChangeConfirmResponse(CHANGED_MESSAGE, newEmail);
    }

    private static EmailChangeResponse toResponse(EmailChangeRequest request) {
        return new EmailChangeResponse(request.getNewEmail(),
                request.getCreatedAt().truncatedTo(ChronoUnit.SECONDS),
                request.getExpiresAt().truncatedTo(ChronoUnit.SECONDS));
    }

    private static BadRequestException invalidToken() {
        return new BadRequestException(TOKEN_INVALID, TOKEN_INVALID_MESSAGE);
    }
}
