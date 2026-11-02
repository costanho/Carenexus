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

    @Column(name = "relationship", nullable = false, length = 80)
    @Builder.Default
    private String relationship = "caregiver";

    @Column(name = "access_level", nullable = false, length = 20)
    @Builder.Default
    private String accessLevel = "VIEW_ONLY";

    @Column(name = "can_view_records", nullable = false)
    @Builder.Default
    private Boolean canViewRecords = false;

    @Column(name = "can_view_appointments", nullable = false)
    @Builder.Default
    private Boolean canViewAppointments = false;

    @Column(name = "can_view_medications", nullable = false)
    @Builder.Default
    private Boolean canViewMedications = false;

    @Column(name = "can_view_lab_results", nullable = false)
    @Builder.Default
    private Boolean canViewLabResults = false;

    @Column(name = "can_view_imaging", nullable = false)
    @Builder.Default
    private Boolean canViewImaging = false;

    @Column(name = "can_message_care_team", nullable = false)
    @Builder.Default
    private Boolean canMessageCareTeam = false;

    @Column(name = "can_view_consultations", nullable = false)
    @Builder.Default
    private Boolean canViewConsultations = false;

    @Column(name = "can_schedule_appointments", nullable = false)
    @Builder.Default
    private Boolean canScheduleAppointments = false;

    @Column(name = "can_manage_care_plan", nullable = false)
    @Builder.Default
    private Boolean canManageCarePlan = false;

    @Column(name = "authorized_date", nullable = false)
    private LocalDateTime authorizedDate;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @PrePersist
    protected void onCreate() {
        authorizedDate = LocalDateTime.now();
    }
}
