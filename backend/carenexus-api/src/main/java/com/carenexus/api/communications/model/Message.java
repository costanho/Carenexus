package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A saved message (messages). {@code recipientSystemId} is legacy (single recipient) and is NULL for new
 * messages; recipients are the thread's members. {@code clientMessageId} is the sender's retry key, unique per
 * (thread, sender).
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Integer messageId;

    @Column(name = "thread_id", nullable = false)
    private Integer threadId;

    @Column(name = "sender_system_id", nullable = false, length = 20)
    private String senderSystemId;

    @Column(name = "recipient_system_id", length = 20)
    private String recipientSystemId;

    /** NULL only for messages of SUPPORT threads (V65). */
    @Column(name = "patient_system_id", length = 20)
    private String patientSystemId;

    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "is_urgent", nullable = false)
    @Builder.Default
    private Boolean isUrgent = false;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
