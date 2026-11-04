package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * In-app notification (notifications). {@code systemId} is the recipient. MESSAGE notifications reference the
 * thread / message they announce; their text is generic and never contains the message body.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String TYPE_SYSTEM = "SYSTEM";

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

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
