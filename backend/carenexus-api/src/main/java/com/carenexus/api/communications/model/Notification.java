package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * In-app notification (notifications). {@code systemId} is the recipient. MESSAGE notifications reference the
 * thread / message they announce; their text is generic and never contains the message body.
 *
 * Rows are written by NotificationPublisher (communications.notify) and by the two messaging writers in
 * NotificationService; nothing else inserts into this table. A row whose {@code expiresAt} has passed is hidden
 * from lists and counts.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    // Types (DB CHECK since V1)
    public static final String TYPE_APPOINTMENT = "APPOINTMENT";
    public static final String TYPE_MEDICATION = "MEDICATION";
    public static final String TYPE_LAB_RESULT = "LAB_RESULT";
    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String TYPE_AI_ALERT = "AI_ALERT";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_REFERRAL = "REFERRAL";

    // Priorities (DB CHECK since V1)
    public static final String PRIORITY_LOW = "LOW";
    public static final String PRIORITY_NORMAL = "NORMAL";
    public static final String PRIORITY_HIGH = "HIGH";
    public static final String PRIORITY_CRITICAL = "CRITICAL";

    // Categories written by the two messaging writers; the full list is NotificationCategory
    public static final String CATEGORY_MESSAGE = "MESSAGE";
    public static final String CATEGORY_SYSTEM = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Integer notificationId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    /** APPOINTMENT | MEDICATION | LAB_RESULT | MESSAGE | AI_ALERT | SYSTEM | PAYMENT | REFERRAL */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "action_url", length = 500)
    private String actionUrl;

    @Column(name = "icon", length = 50)
    private String icon;

    /** LOW | NORMAL | HIGH | CRITICAL */
    @Column(name = "priority", nullable = false, length = 20)
    @Builder.Default
    private String priority = "NORMAL";

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "thread_id")
    private Integer threadId;

    @Column(name = "message_id")
    private Integer messageId;

    @Column(name = "patient_system_id", length = 20)
    private String patientSystemId;

    @Column(name = "actor_system_id", length = 20)
    private String actorSystemId;

    /** Preference category (NotificationCategory name): which user_settings toggle can switch this off. */
    @Column(name = "category", length = 40)
    private String category;

    /** What the notification opens (NotificationTarget name); null for thread messages and informational rows. */
    @Column(name = "target", length = 40)
    private String target;

    /** Upper snake case kind of the referenced row, e.g. APPOINTMENT, LAB_RESULT, INVOICE. */
    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id")
    private Integer resourceId;

    /** Idempotency key of the publishing event or job; unique per recipient when set. */
    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (category == null) {
            category = defaultCategory(type);
        }
    }

    /** Category of a row that carries none: MESSAGE for thread messages, SYSTEM for everything else. */
    public static String defaultCategory(String type) {
        return TYPE_MESSAGE.equals(type) ? CATEGORY_MESSAGE : CATEGORY_SYSTEM;
    }
}
