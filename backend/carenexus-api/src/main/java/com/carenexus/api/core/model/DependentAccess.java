package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "dependent_access")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DependentAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "access_id")
    private Integer accessId;

    @Column(name = "dependent_id", nullable = false)
    private Integer dependentId;  // The patient being cared for

    @Column(name = "caregiver_id", nullable = false)
    private Integer caregiverId;  // The caregiver

    @Column(name = "guardian_id")
    private Integer guardianId;  // Parent or legal guardian granting access

    @Column(name = "permission_type", nullable = false, length = 50)
    private String permissionType;  // "FULL", "HEALTH_RECORDS_ONLY", "ALERTS_ONLY", etc.

    @Column(name = "authorized_at", nullable = false)
    private LocalDateTime authorizedAt;

    @Column(name = "authorized_by", nullable = false)
    private Integer authorizedBy;  // User ID who granted access

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "notes", length = 500)
    private String notes;

    @PrePersist
    protected void onCreate() {
        authorizedAt = LocalDateTime.now();
    }
}
