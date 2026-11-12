package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Received payment gateway webhook event (table {@code payment_events}, V49), stored without payload only for
 * idempotency and ordering. {@code (provider, eventId)} is unique; rows are created with
 * {@code PaymentEventRepository.insertIfAbsent}.
 */
@Entity
@Table(name = "payment_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEvent {

    public static final String OUTCOME_APPLIED = "APPLIED";
    public static final String OUTCOME_IGNORED = "IGNORED";
    public static final String OUTCOME_STALE = "STALE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_event_id")
    private Long paymentEventId;

    /** Name of the gateway that sent the event. */
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /** Payment the event was about, once it was resolved. */
    @Column(name = "payment_id")
    private Integer paymentId;

    /** Null until the event was processed. */
    @Column(name = "outcome", length = 30)
    private String outcome;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Column(name = "processed_at", columnDefinition = "timestamptz")
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }
}
