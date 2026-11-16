package com.carenexus.api.corrections.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/**
 * A request by a patient (or a caregiver with MANAGE_CARE_PLAN) to correct one clinical record (V50,
 * {@code record_correction_requests}). All times are UTC instants.
 *
 * State machine: {@code OPEN} -> {@code ACCEPTED} | {@code REJECTED}; both are terminal. While OPEN the request
 * may be (re)assigned by an administrator. At most one OPEN request exists per (resourceType, resourceId).
 */
@Entity
@Table(name = "record_correction_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorrectionRequest {

    // ---- status ----
    public static final String OPEN = "OPEN";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final List<String> STATUSES = List.of(OPEN, ACCEPTED, REJECTED);

    // ---- resource types ----
    public static final String MEDICAL_RECORD = "MEDICAL_RECORD";
    public static final String LAB_RESULT = "LAB_RESULT";
    public static final String IMAGING_RESULT = "IMAGING_RESULT";
    public static final String PRESCRIPTION = "PRESCRIPTION";
    public static final String CONSULTATION = "CONSULTATION";
    public static final List<String> RESOURCE_TYPES =
            List.of(MEDICAL_RECORD, LAB_RESULT, IMAGING_RESULT, PRESCRIPTION, CONSULTATION);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Integer requestId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "requested_by_system_id", nullable = false, length = 20)
    private String requestedBySystemId;

    @Column(name = "resource_type", nullable = false, length = 30)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Integer resourceId;

    @Column(name = "requested_text", nullable = false, length = 2000)
    private String requestedText;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "assigned_doctor_system_id", length = 20)
    private String assignedDoctorSystemId;

    @Column(name = "resolver_system_id", length = 20)
    private String resolverSystemId;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
    private Instant updatedAt;

    @Column(name = "resolved_at", columnDefinition = "timestamptz")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
        if (status == null) {
            status = OPEN;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    public boolean isOpen() {
        return OPEN.equals(status);
    }
}
