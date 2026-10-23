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

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "caregiver_system_id", nullable = false, length = 20)
    private String caregiverSystemId;

    @Column(name = "authorized_by_system_id", length = 20)
    private String authorizedBySystemId;

    @Column(name = "access_level", nullable = false, length = 20)
    @Builder.Default
    private String accessLevel = "VIEW_ONLY";

    @Column(name = "authorized_at", nullable = false)
    private LocalDateTime authorizedAt;

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
