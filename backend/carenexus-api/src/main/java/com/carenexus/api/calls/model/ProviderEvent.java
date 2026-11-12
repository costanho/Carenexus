package com.carenexus.api.calls.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Received provider webhook (V19), stored without payload only for idempotency and ordering.
 */
@Entity
@Table(name = "provider_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderEvent {

    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_IGNORED = "IGNORED";
    public static final String OUTCOME_STALE = "STALE";
    public static final String OUTCOME_REMOVAL_ENQUEUED = "REMOVAL_ENQUEUED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "provider_event_id")
    private Long providerEventId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "room_name", length = 64)
    private String roomName;

    @Column(name = "provider_identity", length = 64)
    private String providerIdentity;

    @Column(name = "event_created_at", columnDefinition = "timestamptz")
    private Instant eventCreatedAt;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Column(name = "processed_at", columnDefinition = "timestamptz")
    private Instant processedAt;

    @Column(name = "outcome", length = 30)
    private String outcome;
}
