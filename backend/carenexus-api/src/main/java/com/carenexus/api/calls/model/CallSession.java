package com.carenexus.api.calls.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

/**
 * One managed video call per VIDEO appointment (V18). All times are UTC instants.
 * room_name is an opaque random value ("cn_" + 32 hex) and never contains ids.
 */
@Entity
@Table(name = "call_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallSession {

    public static final String PREPARING = "PREPARING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";
    public static final String LIVE = "LIVE";
    public static final String ENDED = "ENDED";
    public static final String CANCELLED = "CANCELLED";
    public static final String NEEDS_TIME_CONFIRMATION = "NEEDS_TIME_CONFIRMATION";

    /** Sessions that still have a schedule and may become (or are) joinable. */
    public static final Set<String> SCHEDULED_STATUSES = Set.of(PREPARING, READY, FAILED, LIVE);
    public static final Set<String> FINAL_STATUSES = Set.of(ENDED, CANCELLED);

    public static final String END_DOCTOR_ENDED = "DOCTOR_ENDED";
    public static final String END_APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";
    public static final String END_TYPE_CHANGED = "TYPE_CHANGED";
    public static final String END_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_session_id")
    private Long callSessionId;

    @Column(name = "appointment_id", nullable = false, unique = true)
    private Integer appointmentId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "room_name", nullable = false, unique = true, length = 64)
    private String roomName;

    @Column(name = "scheduled_start_utc", columnDefinition = "timestamptz")
    private Instant scheduledStartUtc;

    @Column(name = "scheduled_end_utc", columnDefinition = "timestamptz")
    private Instant scheduledEndUtc;

    @Column(name = "join_opens_at", columnDefinition = "timestamptz")
    private Instant joinOpensAt;

    @Column(name = "join_closes_at", columnDefinition = "timestamptz")
    private Instant joinClosesAt;

    @Column(name = "time_confirmed_at", columnDefinition = "timestamptz")
    private Instant timeConfirmedAt;

    @Column(name = "time_confirmed_by_system_id", length = 20)
    private String timeConfirmedBySystemId;

    @Column(name = "room_created_at", columnDefinition = "timestamptz")
    private Instant roomCreatedAt;

    @Column(name = "started_at", columnDefinition = "timestamptz")
    private Instant startedAt;

    @Column(name = "started_by_system_id", length = 20)
    private String startedBySystemId;

    @Column(name = "ended_at", columnDefinition = "timestamptz")
    private Instant endedAt;

    @Column(name = "ended_by_system_id", length = 20)
    private String endedBySystemId;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** The provider room may exist (created, or auto-created by LiveKit when someone joined a LIVE call). */
    public boolean roomMayExist() {
        return roomCreatedAt != null || startedAt != null || LIVE.equals(status);
    }
}
