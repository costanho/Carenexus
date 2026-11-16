package com.carenexus.api.health.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One symptom log (table {@code symptom_logs}, V1 + V47). Logs are never edited; the author can soft-delete one
 * within 24 hours ({@code is_deleted}), and a deleted log is hidden from every read. {@code symptoms} is a JSON array
 * of strings. {@code loggedAt} is clinic wall-clock time set by the server. The legacy integer {@code patient_id}
 * column is not mapped.
 */
@Entity
@Table(name = "symptom_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SymptomLog {

    public static final String SEVERITY_MILD = "MILD";
    public static final String SEVERITY_MODERATE = "MODERATE";
    public static final String SEVERITY_SEVERE = "SEVERE";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    public static final String SOURCE_PATIENT_REPORTED = "PATIENT_REPORTED";
    public static final String SOURCE_DOCTOR_ENTERED = "DOCTOR_ENTERED";
    public static final String SOURCE_WEARABLE = "WEARABLE";
    public static final String SOURCE_INTEGRATION = "INTEGRATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "symptom_id")
    private Integer symptomId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "logged_by_system_id", length = 20)
    private String loggedBySystemId;

    /** JSON array of strings, for example {@code ["Headache","Blurred vision"]}. */
    @Column(name = "symptoms", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String symptoms;

    @Column(name = "severity", nullable = false, length = 20)
    @Builder.Default
    private String severity = SEVERITY_MILD;

    @Column(name = "duration_days")
    private Integer durationDays;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "source", nullable = false, length = 30)
    @Builder.Default
    private String source = SOURCE_PATIENT_REPORTED;

    @Column(name = "logged_at", nullable = false)
    private LocalDateTime loggedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
