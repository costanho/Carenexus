package com.carenexus.api.auth.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Integer tokenId;

    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "token_hash", nullable = false, length = 255, unique = true)
    private String tokenHash;

    /**
     * The session this token belongs to. Created at login and copied to the new row on every rotation, so every
     * token of one session shares the value and at most one of them is not revoked.
     */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_revoked", nullable = false)
    @Builder.Default
    private Boolean isRevoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "device_type", length = 50)
    private String deviceType;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (sessionId == null) {
            // A token saved without a session starts its own.
            sessionId = UUID.randomUUID();
        }
    }
}
