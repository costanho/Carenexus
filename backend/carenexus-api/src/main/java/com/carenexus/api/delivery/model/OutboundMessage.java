package com.carenexus.api.delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One row of the transactional outbox (V62): an email, an SMS or a push notification waiting to be sent, or the
 * record of what happened to it. Rows are created by {@code OutboundMessageService} and moved only by
 * {@code OutboundMessageWorker}.
 *
 * States: PENDING (waiting, also between retries) to SENT, FAILED or SKIPPED, which are terminal.
 */
@Entity
@Table(name = "outbound_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundMessage {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    public static final int MAX_ADDRESS_LENGTH = 320;
    public static final int MAX_TEMPLATE_ID_LENGTH = 80;
    public static final int MAX_SUBJECT_LENGTH = 300;
    public static final int MAX_DEDUPE_KEY_LENGTH = 200;
    public static final int MAX_ERROR_LENGTH = 300;
    public static final int MAX_PROVIDER_LENGTH = 40;
    public static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Integer messageId;

    /** EMAIL, SMS or PUSH. */
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @Column(name = "recipient_system_id", length = 20)
    private String recipientSystemId;

    /** Email address or phone number; null for PUSH. */
    @Column(name = "recipient_address", length = MAX_ADDRESS_LENGTH)
    private String recipientAddress;

    @Column(name = "template_id", length = MAX_TEMPLATE_ID_LENGTH)
    private String templateId;

    /** EMAIL subject or PUSH title; null for SMS. */
    @Column(name = "subject", length = MAX_SUBJECT_LENGTH)
    private String subject;

    /** Rendered when the message is queued. */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** PUSH data payload as a JSON object of strings; null for EMAIL and SMS. */
    @Column(name = "data", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String data;

    @Column(name = "notification_id")
    private Integer notificationId;

    @Column(name = "dedupe_key", length = MAX_DEDUPE_KEY_LENGTH)
    private String dedupeKey;

    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private String status = PENDING;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    @Builder.Default
    private Integer maxAttempts = 5;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "timestamptz")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "provider", length = MAX_PROVIDER_LENGTH)
    private String provider;

    @Column(name = "provider_message_id", length = MAX_PROVIDER_MESSAGE_ID_LENGTH)
    private String providerMessageId;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    /** Set when the row reaches SENT or SKIPPED. */
    @Column(name = "sent_at", columnDefinition = "timestamptz")
    private Instant sentAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = createdAt;
        }
    }

    /** Wait after failed attempt n (1-based): min(30 s * 2^(n-1), 1 h), so 30 s, 1 min, 2 min, 4 min, ... */
    public static long backoffSeconds(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 16));
        return Math.min(30L * (1L << exponent), 3600L);
    }
}
