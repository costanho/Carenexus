package com.carenexus.api.referrals.model;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A referral of a patient by a doctor to a specialist (table {@code referrals}, V1, re-keyed in V3, workflow
 * columns in V45).
 *
 * The specialist is either a platform doctor ({@code specialistSystemId}), a specialist outside the platform
 * ({@code externalSpecialistName} with an optional organisation and contact), or not yet chosen (both empty).
 * A DB CHECK forbids both at once.
 *
 * Timestamps are clinic wall-clock times ({@code carenexus.scheduling.time-zone}), set by the service.
 * The legacy columns {@code appointment_date} and {@code notes} are not mapped.
 */
@Entity
@Table(name = "referrals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** DB CHECK values, in workflow order. */
    public static final List<String> STATUSES =
            List.of(STATUS_PENDING, STATUS_ACCEPTED, STATUS_DECLINED, STATUS_COMPLETED, STATUS_CANCELLED);

    public static final String PRIORITY_ROUTINE = "ROUTINE";
    public static final String PRIORITY_URGENT = "URGENT";
    public static final String PRIORITY_EMERGENCY = "EMERGENCY";
    /** DB CHECK values. */
    public static final List<String> PRIORITIES = List.of(PRIORITY_ROUTINE, PRIORITY_URGENT, PRIORITY_EMERGENCY);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "referral_id")
    private Integer referralId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "referring_doctor_system_id", nullable = false, length = 20)
    private String referringDoctorSystemId;

    /** Platform specialist; null for an external or unassigned specialist. */
    @Column(name = "specialist_system_id", length = 20)
    private String specialistSystemId;

    @Column(name = "external_specialist_name", length = 150)
    private String externalSpecialistName;

    @Column(name = "external_specialist_organisation", length = 150)
    private String externalSpecialistOrganisation;

    @Column(name = "external_specialist_contact", length = 150)
    private String externalSpecialistContact;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "clinical_summary", columnDefinition = "text")
    private String clinicalSummary;

    /** PENDING, ACCEPTED, DECLINED, COMPLETED, CANCELLED (DB CHECK, V45). */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** ROUTINE, URGENT, EMERGENCY (DB CHECK, V1). */
    @Column(name = "priority", nullable = false, length = 20)
    private String priority;

    @Column(name = "consultation_id")
    private Integer consultationId;

    /** Clinic calendar day the referral was issued. */
    @Column(name = "referral_date", nullable = false)
    private LocalDate referralDate;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    @Column(name = "outcome_note", length = 1000)
    private String outcomeNote;

    /** Who recorded the latest decision on the specialist's behalf; null when the named specialist acted. */
    @Column(name = "outcome_recorded_by_system_id", length = 20)
    private String outcomeRecordedBySystemId;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        if (referralDate == null) {
            referralDate = createdAt.toLocalDate();
        }
    }
}
