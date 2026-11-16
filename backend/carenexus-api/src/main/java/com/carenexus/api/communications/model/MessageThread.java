package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A conversation (message_threads). Membership lives in {@link ThreadMember}.
 * PRIVATE and CARE_TEAM conversations are about one patient ({@code patientSystemId}). A SUPPORT conversation (V65)
 * is between its opener ({@code createdBySystemId}) and the administrators who answer it; it has no patient
 * ({@code patientSystemId} is null) and is open until an administrator closes it ({@code closedAt}).
 * {@code threadType} and {@code isArchived} are legacy columns: new threads still fill {@code threadType}
 * (PATIENT_DOCTOR / CAREGIVER_DOCTOR / CARE_TEAM / PATIENT_SUPPORT); archive is per member
 * ({@link ThreadMember#getArchivedAt()}).
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
    public static final String SUPPORT = "SUPPORT";
    /** Legacy thread_type stored for SUPPORT conversations (allowed since V1). */
    public static final String THREAD_TYPE_PATIENT_SUPPORT = "PATIENT_SUPPORT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "thread_id")
    private Integer threadId;

    /** Null only for SUPPORT conversations. */
    @Column(name = "patient_system_id", length = 20)
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

    /** SUPPORT only: when an administrator closed the conversation (clinic wall-clock); null while open. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** SUPPORT only: the administrator who closed the conversation. */
    @Column(name = "closed_by_system_id", length = 20)
    private String closedBySystemId;

    public boolean isCareTeam() {
        return CARE_TEAM.equals(conversationType);
    }

    public boolean isSupport() {
        return SUPPORT.equals(conversationType);
    }

    /** True only for a SUPPORT conversation an administrator closed. */
    public boolean isClosed() {
        return isSupport() && closedAt != null;
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
