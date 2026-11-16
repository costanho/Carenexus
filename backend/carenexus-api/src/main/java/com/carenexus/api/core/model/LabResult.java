package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "lab_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabResult {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REQUIRES_REVIEW = "REQUIRES_REVIEW";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CRITICAL = "CRITICAL";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Every stored status (the lab_results CHECK). */
    public static final java.util.List<String> STATUSES = java.util.List.of(
            STATUS_PENDING, STATUS_REQUIRES_REVIEW, STATUS_COMPLETED, STATUS_CRITICAL, STATUS_CANCELLED);
    /** Statuses a client may set through create, PUT or PATCH (CANCELLED only through DELETE). */
    public static final java.util.List<String> WRITABLE_STATUSES = java.util.List.of(
            STATUS_PENDING, STATUS_REQUIRES_REVIEW, STATUS_COMPLETED, STATUS_CRITICAL);
    /** The lab_results.test_type CHECK. */
    public static final java.util.List<String> TEST_TYPES = java.util.List.of(
            "BLOOD", "URINE", "STOOL", "CULTURE", "BIOPSY", "GENETIC", "SWAB", "OTHER");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Integer resultId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "doctor_system_id", nullable = false, length = 20)
    private String doctorSystemId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "test_name", nullable = false, length = 200)
    private String testName;

    @Column(name = "test_type", nullable = false, length = 20)
    private String testType;

    @Column(name = "result_value", length = 500)
    private String resultValue;

    @Column(name = "unit", length = 50)
    private String unit;

    @Column(name = "reference_range", length = 100)
    private String referenceRange;

    /**
     * JSONB column. The API only stores the JSON text of an object; Hibernate binds it as json.
     */
    @Column(name = "result_data", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String resultData;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "is_critical", nullable = false)
    @Builder.Default
    private Boolean isCritical = false;

    @Column(name = "test_date", nullable = false)
    private LocalDate testDate;

    @Column(name = "reviewed_by_system_id", length = 20)
    private String reviewedBySystemId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    /** Attached document (documents.document_id) of the same patient; null when none. */
    @Column(name = "document_id")
    private Integer documentId;

    /** Legacy free-text URL: no longer written by the API and never returned (attachments use documentId). */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (testDate == null) {
            testDate = LocalDate.now();
        }
    }
}
