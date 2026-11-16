package com.carenexus.api.account.email.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A requested change of an account's sign-in email (V27). Nothing changes on the account until the link sent to the
 * new address is used. States: pending (usedAt and cancelledAt null, not expired), confirmed (usedAt set),
 * cancelled or replaced (cancelledAt set), expired (expiresAt passed). Only the SHA-256 hex hash of the token is
 * stored.
 */
@Entity
@Table(name = "email_change_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer requestId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    /** The requested address, trimmed and lower case. */
    @Column(name = "new_email", nullable = false, length = 150)
    private String newEmail;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private Instant expiresAt;

    /** When the change was confirmed and applied. */
    @Column(name = "used_at", columnDefinition = "timestamptz")
    private Instant usedAt;

    /** When the request was cancelled by its owner or replaced by a newer request. */
    @Column(name = "cancelled_at", columnDefinition = "timestamptz")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Neither confirmed nor cancelled, and not expired at {@code now}. */
    public boolean isPendingAt(Instant now) {
        return usedAt == null && cancelledAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
