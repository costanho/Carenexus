package com.carenexus.api.account.datarights.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A request a user made about their own account (V30, table {@code account_requests}): a data export, a closure
 * record or a deletion request. Timestamps are clinic wall-clock {@link LocalDateTime} values; the API converts them
 * to instants. {@code resultRef} (the storage key of an export) is never serialized.
 */
@Entity
@Table(name = "account_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountRequest {

    public static final String TYPE_EXPORT = "EXPORT";
    public static final String TYPE_CLOSURE = "CLOSURE";
    public static final String TYPE_DELETION = "DELETION";
    public static final List<String> TYPES = List.of(TYPE_EXPORT, TYPE_CLOSURE, TYPE_DELETION);

    public static final String STATUS_REQUESTED = "REQUESTED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final List<String> STATUSES = List.of(STATUS_REQUESTED, STATUS_PROCESSING, STATUS_READY,
            STATUS_COMPLETED, STATUS_REJECTED, STATUS_CANCELLED);
    /** Export statuses in which the bundle is still being prepared (at most one per user, partial unique index). */
    public static final List<String> EXPORT_IN_PROGRESS = List.of(STATUS_REQUESTED, STATUS_PROCESSING);

    public static final int MAX_REASON_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer requestId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "result_ref", length = 255)
    private String resultRef;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "processed_by_system_id", length = 20)
    private String processedBySystemId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        // Services set the times from the application clock; these are only a safety net.
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = requestedAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    public boolean isType(String value) {
        return value != null && value.equals(type);
    }

    public boolean hasStatus(String value) {
        return value != null && value.equals(status);
    }
}
