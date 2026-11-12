package com.carenexus.api.delivery;

import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.SmsSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one way to send an email, an SMS or a push notification: every message is written to the transactional
 * outbox ({@code outbound_messages}) and sent later by {@code OutboundMessageWorker}.
 *
 * <ul>
 *   <li>The row is inserted PENDING in the caller's transaction, so a change that rolls back sends nothing.</li>
 *   <li>Templates are rendered when the message is queued: an unknown template fails the caller at once, and the
 *       stored {@code subject} and {@code body} are final from the first moment the row exists.</li>
 *   <li>A repeated {@code dedupeKey} (unique across the whole outbox) stores nothing and returns the id of the
 *       existing row ({@code INSERT ... ON CONFLICT DO NOTHING}; the surrounding transaction is never aborted).</li>
 *   <li>{@code notBefore} delays the first attempt (quiet hours).</li>
 * </ul>
 *
 * Callers are other backend modules (account mail, notification fan-out); there is no caller identity to check
 * here, and the endpoints that read the outbox do their own authorization.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class OutboundMessageService {

    static final int MAX_BODY_LENGTH = 100_000;
    static final int MAX_SYSTEM_ID_LENGTH = 20;

    private static final String INSERT_SQL = "INSERT INTO outbound_messages (channel, recipient_system_id, "
            + "recipient_address, template_id, subject, body, data, notification_id, dedupe_key, status, attempts, "
            + "max_attempts, next_attempt_at, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 'PENDING', 0, ?, ?, ?) "
            + "ON CONFLICT (dedupe_key) DO NOTHING RETURNING message_id";
    private static final int[] INSERT_TYPES = {Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.INTEGER,
            Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE};

    private final JdbcTemplate jdbc;
    private final TemplateRenderer templateRenderer;
    private final DeliveryProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final PushSender pushSender;

    /**
     * Queues an email rendered from {@code messages/<templateId>.txt}.
     *
     * @param toAddress         recipient address; a null or blank address is stored and later marked SKIPPED
     * @param recipientSystemId the recipient's account, or null when the address has no account
     * @param dedupeKey         optional idempotency key, for example {@code "PASSWORD_RESET:17"}
     * @return the outbox row id (the existing row's id when the dedupe key was used before)
     */
    public Integer queueEmail(String toAddress, String recipientSystemId, String templateId, Map<String, String> model,
                              String dedupeKey) {
        return queue(new OutboundRequest(DeliveryChannel.EMAIL, toAddress, recipientSystemId, templateId, model,
                null, null, null, dedupeKey, null, null));
    }

    /** Queues a text message rendered from {@code messages/<templateId>.txt} (its {@code Subject:} line is ignored). */
    public Integer queueSms(String toPhone, String recipientSystemId, String templateId, Map<String, String> model,
                            String dedupeKey) {
        return queue(new OutboundRequest(DeliveryChannel.SMS, toPhone, recipientSystemId, templateId, model,
                null, null, null, dedupeKey, null, null));
    }

    /** Queues one push notification for all devices the recipient has registered when it is sent. */
    public Integer queuePush(String recipientSystemId, String title, String body, Map<String, String> data,
                             String dedupeKey) {
        return queue(new OutboundRequest(DeliveryChannel.PUSH, null, recipientSystemId, null, null,
                title, body, data, dedupeKey, null, null));
    }

    /**
     * The general form; the three methods above delegate to it.
     *
     * @return the outbox row id, or the id of the existing row when {@code dedupeKey} was used before
     * @throws IllegalArgumentException when the request is malformed (unknown channel, missing text, value too long)
     * @throws IllegalStateException    when the template does not exist or is malformed
     */
    public Integer queue(OutboundRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Outbound request is required");
        }
        String channel = DeliveryChannel.normalize(request.channel());
        if (channel == null) {
            throw new IllegalArgumentException("Outbound message channel must be EMAIL, SMS or PUSH");
        }
        String recipient = limited(trimToNull(request.recipientSystemId()), MAX_SYSTEM_ID_LENGTH, "recipient system id");
        String dedupeKey = limited(trimToNull(request.dedupeKey()), OutboundMessage.MAX_DEDUPE_KEY_LENGTH, "dedupe key");

        String address = null;
        String templateId = null;
        String subject;
        String body;
        String data = null;

        if (DeliveryChannel.PUSH.equals(channel)) {
            if (recipient == null) {
                throw new IllegalArgumentException("A push message needs the recipient's system id");
            }
            subject = trimToNull(request.title());
            body = request.body();
            data = toJson(request.data());
        } else {
            address = limited(trimToNull(request.toAddress()), OutboundMessage.MAX_ADDRESS_LENGTH, "recipient address");
            if (address != null && address.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("The recipient address contains control characters");
            }
            templateId = trimToNull(request.templateId());
            if (templateId != null) {
                RenderedTemplate rendered = templateRenderer.render(templateId, request.model());
                subject = rendered.subject();
                body = rendered.body();
            } else {
                subject = trimToNull(request.title());
                body = request.body();
            }
            if (DeliveryChannel.SMS.equals(channel)) {
                subject = null;
            } else if (subject == null) {
                throw new IllegalArgumentException("An email needs a template or a title");
            }
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("An outbound message needs a template or a body");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("The outbound message text is too long");
        }
        if (subject != null) {
            subject = clip(subject.replaceAll("[\\r\\n]+", " "), OutboundMessage.MAX_SUBJECT_LENGTH);
        }

        // PostgreSQL keeps microseconds; store what will be read back.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant due = dueAt(request.notBefore(), now);

        Object[] args = {channel, recipient, address, templateId, subject, body, data, request.notificationId(),
                dedupeKey, properties.safeMaxAttempts(), utc(due), utc(now)};
        List<Integer> inserted = jdbc.queryForList(INSERT_SQL, args, INSERT_TYPES, Integer.class);
        if (!inserted.isEmpty()) {
            Integer id = inserted.get(0);
            log.debug("Outbound message {} queued: channel={} template={} notification={}",
                    id, channel, templateId, request.notificationId());
            return id;
        }
        // The dedupe key exists already (the insert waited for a concurrent writer of the same key, if any).
        List<Integer> existing = jdbc.queryForList(
                "SELECT message_id FROM outbound_messages WHERE dedupe_key = ?", Integer.class, dedupeKey);
        if (existing.isEmpty()) {
            throw new IllegalStateException("Outbound message could not be queued");
        }
        log.debug("Outbound message {} already queued for this dedupe key: channel={}", existing.get(0), channel);
        return existing.get(0);
    }

    /**
     * True when messages on {@code channel} can be sent on this installation: the provider is not
     * {@code disabled} and reports itself configured. The same rule as {@code available} of
     * {@code GET /api/notifications/channels}.
     */
    @Transactional(readOnly = true)
    public boolean isChannelAvailable(String channel) {
        String normalized = DeliveryChannel.normalize(channel);
        if (normalized == null) {
            return false;
        }
        return switch (normalized) {
            case DeliveryChannel.EMAIL -> available(emailSender.providerName(), emailSender.isConfigured());
            case DeliveryChannel.SMS -> available(smsSender.providerName(), smsSender.isConfigured());
            default -> available(pushSender.providerName(), pushSender.isConfigured());
        };
    }

    /** The availability rule shared with the channels endpoint and the worker. */
    public static boolean available(String providerName, boolean configured) {
        return configured && providerName != null && !DeliveryProperties.DISABLED.equalsIgnoreCase(providerName.trim());
    }

    // ==================== HELPERS ====================

    /** {@code notBefore} is clinic wall-clock time (the application clock's zone); never earlier than now. */
    private Instant dueAt(LocalDateTime notBefore, Instant now) {
        if (notBefore == null) {
            return now;
        }
        Instant requested = notBefore.atZone(clock.getZone()).toInstant().truncatedTo(ChronoUnit.MICROS);
        return requested.isAfter(now) ? requested : now;
    }

    private String toJson(Map<String, String> data) {
        if (data == null) {
            return null;
        }
        Map<String, String> clean = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (key != null && value != null) {
                clean.put(key, value);
            }
        });
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The push data payload cannot be written as JSON");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String limited(String value, int max, String what) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException("The " + what + " is longer than " + max + " characters");
        }
        return value;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
