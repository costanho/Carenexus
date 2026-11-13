package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "care_team")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "care_team_id")
    private Integer careTeamId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** CareNexus account of the provider (a doctor); null for an external provider entered by name (V42). */
    @Column(name = "provider_system_id", length = 20)
    private String providerSystemId;

    /** External provider (no account): name, required when {@code providerSystemId} is null (V42 CHECK). */
    @Column(name = "provider_name", length = 150)
    private String providerName;

    /** External provider phone; null for an account provider. */
    @Column(name = "provider_phone", length = 20)
    private String providerPhone;

    /** External provider organisation; null for an account provider. */
    @Column(name = "provider_organisation", length = 200)
    private String providerOrganisation;

    @Column(name = "facility_id")
    private Integer facilityId;

    /**
     * DB CHECK (V10): PRIMARY_DOCTOR, DOCTOR, SPECIALIST, NURSE, LAB, PHARMACY, THERAPIST, CAREGIVER, ASSIGNED_CAREGIVER.
     * The care-team API writes only DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST and THERAPIST; a legacy PRIMARY_DOCTOR
     * row reads as DOCTOR + primary. At most one active row per patient has {@code isPrimary} (V42 index).
     */
    @Column(name = "role", nullable = false, length = 50)
    private String role;

    @Column(name = "joined_date", nullable = false)
    private LocalDate joinedDate;

    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private Boolean isPrimary = false;

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "unassigned_at")
    private LocalDateTime unassignedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (assignedAt == null) {
            assignedAt = now;
        }
        if (joinedDate == null) {
            joinedDate = assignedAt.toLocalDate();
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
