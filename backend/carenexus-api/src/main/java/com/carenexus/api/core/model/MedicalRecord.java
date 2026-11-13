package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A medical record (table {@code medical_records}). Soft-deleted rows ({@code is_deleted}) are never returned by the
 * API. {@code updated_at} is overwritten by the {@code update_medical_records_updated_at} database trigger on every
 * UPDATE; the service writes {@code created_at} / {@code updated_at} on insert with the clinic clock.
 */
@Entity
@Table(name = "medical_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MedicalRecord {

    public static final String TYPE_CONSULT = "CONSULT";
    /** The eight record types of the {@code record_type} CHECK constraint, in display order. */
    public static final List<String> RECORD_TYPES = List.of(
            "CONSULT", "LAB", "IMAGING", "PRESCRIPTION", "REFERRAL", "DISCHARGE", "NOTE", "OPERATION");

    /** Filed by a doctor through the API. */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** Created automatically when a consultation was completed. */
    public static final String SOURCE_CONSULTATION = "CONSULTATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "record_id")
    private Integer recordId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "doctor_system_id", nullable = false, length = 20)
    private String doctorSystemId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "record_type", nullable = false, length = 20)
    private String recordType;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "treatment_plan", columnDefinition = "TEXT")
    private String treatmentPlan;

    @Column(name = "observations", columnDefinition = "TEXT")
    private String observations;

    /** JSON array of {@code {code, description}} objects, or null when the record has no codes. */
    @Column(name = "icd_codes", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String icdCodes;

    /** {@link #SOURCE_MANUAL} or {@link #SOURCE_CONSULTATION}; set on insert, never changed. */
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = SOURCE_MANUAL;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
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
        if (source == null) {
            source = SOURCE_MANUAL;
        }
    }
}
