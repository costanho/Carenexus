package com.carenexus.api.caregiveraccess.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A caregiver's request for access to an existing patient (table {@code caregiver_access_requests}, V44).
 *
 * {@code patientSystemId} is null when the email the caregiver typed matched no patient: the row is stored anyway so
 * the API never reveals whether an account exists, nobody can approve it, and it expires after
 * {@link #PENDING_DAYS} days. The level and the twelve flags are what the caregiver REQUESTED (normalised to the
 * level); they never change after creation. Every status other than PENDING is terminal. Times are clinic wall-clock.
 */
@Entity
@Table(name = "caregiver_access_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaregiverAccessRequest {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** A pending request may be marked EXPIRED by the daily job once it is this many days old. */
    public static final int PENDING_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer requestId;

    @Column(name = "caregiver_system_id", nullable = false, length = 20)
    private String caregiverSystemId;

    /** The matching patient, or null when the email matched no patient account. */
    @Column(name = "patient_system_id", length = 20)
    private String patientSystemId;

    /** The email the caregiver typed, trimmed and lower-cased. */
    @Column(name = "patient_email", nullable = false, length = 150)
    private String patientEmail;

    @Column(name = "relationship", nullable = false, length = 80)
    private String relationship;

    @Column(name = "access_level", nullable = false, length = 20)
    private String accessLevel;

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

    @Column(name = "can_view_consultations", nullable = false)
    @Builder.Default
    private Boolean canViewConsultations = false;

    @Column(name = "can_view_billing", nullable = false)
    @Builder.Default
    private Boolean canViewBilling = false;

    @Column(name = "can_schedule_appointments", nullable = false)
    @Builder.Default
    private Boolean canScheduleAppointments = false;

    @Column(name = "can_manage_care_plan", nullable = false)
    @Builder.Default
    private Boolean canManageCarePlan = false;

    @Column(name = "can_manage_billing", nullable = false)
    @Builder.Default
    private Boolean canManageBilling = false;

    @Column(name = "can_message_care_team", nullable = false)
    @Builder.Default
    private Boolean canMessageCareTeam = false;

    @Column(name = "can_join_calls", nullable = false)
    @Builder.Default
    private Boolean canJoinCalls = false;

    /** The caregiver's note to the patient (max 500), or null. */
    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    /** The patient's reason when DECLINED (max 500), or null. */
    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    /** The grant created or reused by the approval; set only when APPROVED. */
    @Column(name = "access_id")
    private Integer accessId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the request left PENDING; null while pending and after a status-only clean-up cancellation. */
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** createdAt plus {@link #PENDING_DAYS} days: from then on the daily job may mark a pending request EXPIRED. */
    public LocalDateTime requestExpiresAt() {
        return createdAt == null ? null : createdAt.plusDays(PENDING_DAYS);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
