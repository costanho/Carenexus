package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A conversation about one patient (message_threads). Membership lives in {@link ThreadMember}.
 * {@code threadType} and {@code isArchived} are legacy columns: new threads still fill {@code threadType}
 * (PATIENT_DOCTOR / CAREGIVER_DOCTOR / CARE_TEAM); archive is per member ({@link ThreadMember#getArchivedAt()}).
 */
@Entity
@Table(name = "message_threads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageThread {

    public static final String PRIVATE = "PRIVATE";
    public static final String CARE_TEAM = "CARE_TEAM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "thread_id")
    private Integer threadId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "thread_type", nullable = false, length = 30)
    private String threadType;

    @Column(name = "conversation_type", nullable = false, length = 20)
    @Builder.Default
    private String conversationType = PRIVATE;

    @Column(name = "created_by_system_id", length = 20)
    private String createdBySystemId;

    @Column(name = "client_thread_id", length = 64)
    private String clientThreadId;

    @Column(name = "last_message_id")
    private Integer lastMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "is_archived", nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    public boolean isCareTeam() {
        return CARE_TEAM.equals(conversationType);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
