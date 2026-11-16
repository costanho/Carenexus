package com.carenexus.api.account.password.service;

import com.carenexus.api.account.password.config.PasswordResetProperties;
import com.carenexus.api.account.password.model.PasswordResetToken;
import com.carenexus.api.account.password.repository.PasswordResetTokenRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.delivery.OutboundMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Storage and mailing of set-password links, shared by forgot-password, the administrator / caregiver listener and
 * the two password-changing flows. Every method joins the caller's transaction (MANDATORY): a token and its email
 * are written together or not at all.
 *
 * Tokens are 256 random bits, URL-safe base64 without padding (43 characters); only their SHA-256 hex is stored.
 * At most one token per user is usable: issuing one supersedes the others. All timestamps come from the injected
 * {@link Clock} (clinic wall-clock), so expiry is tested with a fixed clock.
 *
 * This class performs no authorization: its callers decide who may cause a link to be issued or redeemed (anyone
 * for forgot-password, the holder of the token for a reset, the signed-in owner for a change, the publishing
 * module for the listener).
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PasswordResetTokenService {

    public static final String TEMPLATE_RESET = "account-password-reset";
    public static final String TEMPLATE_INVITE = "account-password-invite";
    public static final String TEMPLATE_CHANGED = "account-password-changed";

    static final int REQUESTED_IP_MAX = 45;
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository tokenRepository;
    private final OutboundMessageService outboundMessageService;
    private final PasswordResetProperties properties;
    private final Clock clock;

    /** A newly stored token and the raw value that goes into the link (never stored, never logged). */
    public record IssuedToken(PasswordResetToken token, String rawToken) {
    }

    /** Clinic wall-clock now, truncated to what a PostgreSQL TIMESTAMP keeps. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Supersedes every unused token of the user and stores a new one.
     *
     * @param requestedIp         client address of a self-service request (null otherwise)
     * @param requestedBySystemId administrator or caregiver who asked for the link (null for self-service)
     */
    public IssuedToken issue(User user, String requestedIp, String requestedBySystemId) {
        LocalDateTime now = now();
        tokenRepository.invalidateUnused(user.getSystemId(), now);
        String raw = randomToken();
        PasswordResetToken token = tokenRepository.save(PasswordResetToken.builder()
                .systemId(user.getSystemId())
                .tokenHash(sha256Hex(raw))
                .createdAt(now)
                .expiresAt(now.plusMinutes(properties.getTtlMinutes()))
                .requestedIp(clip(requestedIp, REQUESTED_IP_MAX))
                .requestedBySystemId(requestedBySystemId)
                .build());
        return new IssuedToken(token, raw);
    }

    /**
     * Queues the email carrying the link of {@code issued} ({@link #TEMPLATE_RESET} or {@link #TEMPLATE_INVITE}).
     * The dedupe key is per token, so a repeated call for one token queues one message.
     */
    public void sendLink(User user, IssuedToken issued, String templateId) {
        String dedupePrefix = TEMPLATE_INVITE.equals(templateId) ? "PASSWORD_INVITE:" : "PASSWORD_RESET:";
        Map<String, String> model = new LinkedHashMap<>();
        model.put("name", user.getFirstName());
        model.put("link", properties.resetLink(issued.rawToken()));
        model.put("expiresMinutes", String.valueOf(properties.getTtlMinutes()));
        outboundMessageService.queueEmail(user.getEmail(), user.getSystemId(), templateId, model,
                dedupePrefix + issued.token().getTokenId());
    }

    /**
     * The token for this raw value, locked for the rest of the transaction, when it can still be used: known, not
     * used or superseded, not expired. Empty otherwise (the caller gives one answer for every case).
     */
    public Optional<PasswordResetToken> findUsableForUpdate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = now();
        return tokenRepository.findByTokenHashForUpdate(sha256Hex(rawToken.trim()))
                .filter(token -> token.getUsedAt() == null)
                .filter(token -> token.getExpiresAt().isAfter(now));
    }

    /** Marks {@code token} redeemed and supersedes every other unused token of the same user. */
    public void redeem(PasswordResetToken token) {
        LocalDateTime now = now();
        token.setUsedAt(now);
        tokenRepository.save(token);
        tokenRepository.invalidateUnused(token.getSystemId(), now);
    }

    /** Supersedes every unused token of the user (after a password change). */
    public int invalidateAll(String systemId) {
        return tokenRepository.invalidateUnused(systemId, now());
    }

    /**
     * Queues the "your password was changed" notice (no link). Skipped for an account without an email address.
     * The dedupe key includes the change time, so each change sends one notice.
     */
    public void sendPasswordChangedNotice(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        LocalDateTime changedAt = user.getPasswordChangedAt() == null ? now() : user.getPasswordChangedAt();
        outboundMessageService.queueEmail(user.getEmail(), user.getSystemId(), TEMPLATE_CHANGED,
                Map.of("name", user.getFirstName() == null ? "" : user.getFirstName()),
                "PASSWORD_CHANGED:" + user.getSystemId() + ":" + changedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
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

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
