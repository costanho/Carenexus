package com.carenexus.api.account.password.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A one-time link that sets a new password (table {@code password_reset_tokens}, V26). Only the SHA-256 hex of the
 * token is stored. There is no status column: a token is usable while {@code usedAt} is null and {@code expiresAt}
 * lies in the future. Timestamps are clinic wall-clock values taken from the application {@code Clock}.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Integer tokenId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    /** SHA-256 hex of the raw token. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** When the token was redeemed or superseded; null while it may still be used. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Client address of a self-service request; null for links requested by someone else. */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    /** Administrator or caregiver who asked for the link; null for a self-service request. */
    @Column(name = "requested_by_system_id", length = 20)
    private String requestedBySystemId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** True when the link was requested by someone other than the account holder. */
    public boolean isRequestedByAnotherUser() {
        return requestedBySystemId != null;
    }
}
