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
 * A one-time link that confirms the sign-in email of an account (V27). Only the SHA-256 hex hash of the token is
 * stored. A token is usable while {@code usedAt} is null, {@code expiresAt} is in the future and the account's email
 * is still {@link #email}. Superseded tokens are stored as used.
 */
@Entity
@Table(name = "email_verification_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Integer tokenId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    /** The address the link was sent to (lower case). */
    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamptz")
    private Instant expiresAt;

    /** When the token was used or invalidated; null = still outstanding. */
    @Column(name = "used_at", columnDefinition = "timestamptz")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
