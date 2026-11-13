package com.carenexus.api.account.mfa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * TOTP enrolment of one account (table {@code user_mfa}, V28).
 *
 * State: no row = OFF; {@code enabledAt == null} = PENDING (setup started, not confirmed); {@code enabledAt != null}
 * = ON. The secret is stored encrypted ({@code MfaSecretCipher}); {@code lastUsedStep} is the TOTP step of the last
 * accepted code, so a code is never accepted twice.
 */
@Entity
@Table(name = "user_mfa")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserMfa {

    @Id
    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "secret_encrypted", nullable = false, length = 255)
    private String secretEncrypted;

    /** When MFA was turned on; null while setup is pending. */
    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    /** 30-second step of the last accepted TOTP code; null before the first one. */
    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public boolean isEnabled() {
        return enabledAt != null;
    }

    /** The secret is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "UserMfa[systemId=" + systemId + ", enabled=" + isEnabled() + "]";
    }
}
