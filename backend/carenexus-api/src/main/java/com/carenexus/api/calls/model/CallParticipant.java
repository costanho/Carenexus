package com.carenexus.api.calls.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Set;

/**
 * One join request / admission of a user to a call session (V18). Every join request gets a NEW opaque
 * provider_identity ("p_" + 32 hex). revoked_at is the revocation cutoff: no token after it, and any provider
 * presence after it is removed.
 */
@Entity
@Table(name = "call_participants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallParticipant {

    public static final String REQUESTED = "REQUESTED";
    public static final String ADMITTED = "ADMITTED";
    public static final String DENIED = "DENIED";
    public static final String LEFT = "LEFT";
    public static final String REMOVED = "REMOVED";

    public static final Set<String> OPEN_STATUSES = Set.of(REQUESTED, ADMITTED);

    public static final String REASON_REMOVED_BY_DOCTOR = "REMOVED_BY_DOCTOR";
    public static final String REASON_ACCESS_REVOKED = "ACCESS_REVOKED";
    public static final String REASON_LOGGED_OUT = "LOGGED_OUT";
    public static final String REASON_CALL_ENDED = "CALL_ENDED";
    public static final String REASON_APPOINTMENT_CANCELLED = "APPOINTMENT_CANCELLED";
    public static final String REASON_RESCHEDULED = "RESCHEDULED";
    public static final String REASON_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "call_participant_id")
    private Long callParticipantId;

    @Column(name = "call_session_id", nullable = false)
    private Long callSessionId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "participant_role", nullable = false, length = 20)
    private String participantRole;

    @Column(name = "acting_for_patient", nullable = false)
    @Builder.Default
    private Boolean actingForPatient = false;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "provider_identity", nullable = false, unique = true, length = 64)
    private String providerIdentity;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "requested_at", nullable = false, columnDefinition = "timestamptz")
    private Instant requestedAt;

    @Column(name = "decided_at", columnDefinition = "timestamptz")
    private Instant decidedAt;

    @Column(name = "decided_by_system_id", length = 20)
    private String decidedBySystemId;

    @Column(name = "admitted_at", columnDefinition = "timestamptz")
    private Instant admittedAt;

    @Column(name = "left_at", columnDefinition = "timestamptz")
    private Instant leftAt;

    @Column(name = "removed_at", columnDefinition = "timestamptz")
    private Instant removedAt;

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    private Instant revokedAt;

    @Column(name = "removal_reason", length = 30)
    private String removalReason;

    @Column(name = "removal_pending", nullable = false)
    @Builder.Default
    private Boolean removalPending = false;

    @Column(name = "last_token_issued_at", columnDefinition = "timestamptz")
    private Instant lastTokenIssuedAt;

    @Column(name = "provider_joined_at", columnDefinition = "timestamptz")
    private Instant providerJoinedAt;

    @Column(name = "provider_left_at", columnDefinition = "timestamptz")
    private Instant providerLeftAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        if (requestedAt == null) {
            requestedAt = now;
        }
    }

    public boolean isOpen() {
        return OPEN_STATUSES.contains(status);
    }

    /** Provider reported the participant connected and not left since. */
    public boolean isConnected() {
        return providerJoinedAt != null && (providerLeftAt == null || providerLeftAt.isBefore(providerJoinedAt));
    }
}
