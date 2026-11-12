package com.carenexus.api.calls.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

/**
 * Durable provider job (V19): bounded retries with capped exponential backoff, at most one open job per
 * (job_type, room_name, provider_identity).
 */
@Entity
@Table(name = "call_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallJob {

    public static final String CREATE_ROOM = "CREATE_ROOM";
    public static final String DELETE_ROOM = "DELETE_ROOM";
    public static final String REMOVE_PARTICIPANT = "REMOVE_PARTICIPANT";

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final Set<String> OPEN_STATUSES = Set.of(PENDING, RUNNING);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_job_id")
    private Long callJobId;

    @Column(name = "job_type", nullable = false, length = 30)
    private String jobType;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = PENDING;

    @Column(name = "call_session_id", nullable = false)
    private Long callSessionId;

    @Column(name = "call_participant_id")
    private Long callParticipantId;

    @Column(name = "room_name", nullable = false, length = 64)
    private String roomName;

    @Column(name = "provider_identity", length = 64)
    private String providerIdentity;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "next_attempt_at", nullable = false, columnDefinition = "timestamptz")
    private Instant nextAttemptAt;

    @Column(name = "locked_at", columnDefinition = "timestamptz")
    private Instant lockedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Column(name = "completed_at", columnDefinition = "timestamptz")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** max_attempts per type: CREATE_ROOM 8, DELETE_ROOM 10, REMOVE_PARTICIPANT 30. */
    public static int maxAttemptsFor(String jobType) {
        return switch (jobType) {
            case CREATE_ROOM -> 8;
            case DELETE_ROOM -> 10;
            case REMOVE_PARTICIPANT -> 30;
            default -> throw new IllegalArgumentException("Unknown call job type");
        };
    }

    /** Backoff after failed attempt n (1-based): min(5 s * 2^(n-1), 300 s). */
    public static long backoffSeconds(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 16));
        return Math.min(5L * (1L << exponent), 300L);
    }
}
