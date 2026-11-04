package com.carenexus.api.communications.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Membership of a user in a conversation (thread_members).
 * Active = {@code leftAt == null && removedAt == null}. A member sees messages with
 * {@code messageId > historyStartMessageId} (join-time history boundary).
 */
@Entity
@Table(name = "thread_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadMember {

    public static final String REASON_LEFT = "LEFT";
    public static final String REASON_REMOVED_BY_MEMBER = "REMOVED_BY_MEMBER";
    public static final String REASON_ACCESS_REVOKED = "ACCESS_REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "thread_member_id")
    private Long threadMemberId;

    @Column(name = "thread_id", nullable = false)
    private Integer threadId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    /** PATIENT | DOCTOR | CAREGIVER */
    @Column(name = "member_role", nullable = false, length = 20)
    private String memberRole;

    @Column(name = "joined_at", nullable = false, columnDefinition = "timestamptz")
    private Instant joinedAt;

    @Column(name = "history_start_message_id", nullable = false)
    @Builder.Default
    private Integer historyStartMessageId = 0;

    @Column(name = "last_read_message_id", nullable = false)
    @Builder.Default
    private Integer lastReadMessageId = 0;

    @Column(name = "archived_at", columnDefinition = "timestamptz")
    private Instant archivedAt;

    @Column(name = "added_by_system_id", length = 20)
    private String addedBySystemId;

    @Column(name = "left_at", columnDefinition = "timestamptz")
    private Instant leftAt;

    @Column(name = "removed_at", columnDefinition = "timestamptz")
    private Instant removedAt;

    @Column(name = "removed_by_system_id", length = 20)
    private String removedBySystemId;

    /** LEFT | REMOVED_BY_MEMBER | ACCESS_REVOKED */
    @Column(name = "removal_reason", length = 30)
    private String removalReason;

    public boolean isActive() {
        return leftAt == null && removedAt == null;
    }

    public int historyStart() {
        return historyStartMessageId != null ? historyStartMessageId : 0;
    }

    public int lastRead() {
        return lastReadMessageId != null ? lastReadMessageId : 0;
    }
}
