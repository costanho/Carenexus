package com.carenexus.api.ai.detection.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One screening result (table {@code disease_detection}: V1, re-keyed by V3, review columns V48, source and dedupe
 * key V66). Rows are inserted only by the screening pipeline ({@code DiseaseDetectionRepository.insertIfAbsent}) and
 * changed only by a doctor's review; there is no delete. Times are clinic wall-clock.
 * <p>
 * {@code detectedConditions} and {@code recommendations} are JSON arrays; {@code inputData} is the de-identified
 * snapshot the provider received and is never returned by the API. {@code isConfirmed} and {@code confirmedAt} are
 * legacy columns kept in sync with a CONFIRMED review.
 */
@Entity
@Table(name = "disease_detection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiseaseDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "detection_id")
    private Integer detectionId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** The symptom log that triggered the screening (set together with source SYMPTOM_LOG). */
    @Column(name = "symptom_id")
    private Integer symptomId;

    /** The doctor who ran the screening by hand; null for automatic screenings. */
    @Column(name = "doctor_system_id", length = 20)
    private String doctorSystemId;

    @Column(name = "model_version", nullable = false, length = 50)
    private String modelVersion;

    @Column(name = "detected_conditions", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String detectedConditions;

    @Column(name = "confidence_score", precision = 4, scale = 3)
    private BigDecimal confidenceScore;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "input_data", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String inputData;

    @Column(name = "recommendations", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String recommendations;

    @Column(name = "is_confirmed", nullable = false)
    private Boolean isConfirmed;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    @Column(name = "reviewed_by_system_id", length = 20)
    private String reviewedBySystemId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** SYMPTOM_LOG or HEALTH_METRIC; null for a manual run. */
    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;
}
