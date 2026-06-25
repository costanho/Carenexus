package com.auth.server.model;

import jakarta.persistence.*;
import lombok.*;

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

    @Column(name = "patient_id", nullable = false)
    private Integer patientId;

    @Column(name = "doctor_id")
    private Integer doctorId;

    @Column(name = "caregiver_id")
    private Integer caregiverId;

    @Column(name = "facility_id")
    private Integer facilityId;

    @Column(name = "role", nullable = false, length = 50)
    private String role;  // "PRIMARY_DOCTOR", "SPECIALIST", "CAREGIVER", etc.

    @Column(name = "assigned_at", nullable = false)
    private LocalDateTime assignedAt;

    @Column(name = "unassigned_at")
    private LocalDateTime unassignedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }
}
