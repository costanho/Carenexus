package com.carenexus.api.ai.detection.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One screening result (api/be-ai-detection.md 1.1). Patient-facing copies (caller PATIENT or CAREGIVER) carry no
 * recommendations, no review notes and {@code canReview = false}. The de-identified input, the dedupe key and the
 * legacy confirmation columns are never exposed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An automated rule-based screening result about a patient. Not a diagnosis.")
public class DiseaseDetectionResponse {

    /** Fixed text carried by every detection DTO, also after a doctor confirmed the result. */
    public static final String DISCLAIMER =
            "This result comes from automated rule-based screening. It is not a diagnosis.";

    @Schema(description = "Detection id", example = "31")
    private Integer detectionId;

    @Schema(description = "The patient the detection is about", example = "PAT-00004")
    private String patientSystemId;

    @Schema(description = "First and last name of the patient", example = "Rudo Chikwanha")
    private String patientName;

    @Schema(description = "What triggered the screening; null when a doctor ran it by hand", nullable = true,
            allowableValues = {"SYMPTOM_LOG", "HEALTH_METRIC"})
    private String sourceType;

    @Schema(description = "symptom_logs.symptom_id or health_metrics.metric_id; null exactly when sourceType is null",
            nullable = true, example = "205")
    private Integer sourceId;

    @Schema(description = "Doctor who ran the screening by hand; null for automatic screenings", nullable = true,
            example = "DOC-00002")
    private String requestedBySystemId;

    @Schema(description = "Version of the provider that produced the result", example = "rules-v1")
    private String modelVersion;

    @Schema(description = "Overall level: the highest level among the conditions",
            allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
    private String riskLevel;

    @Schema(description = "Confidence of the primary (first) condition, 0 to 1", nullable = true, example = "0.75")
    private BigDecimal confidenceScore;

    @ArraySchema(arraySchema = @Schema(description = "1 to 10 findings, highest level first, then highest "
            + "confidence, then code; the first is the primary condition"))
    private List<DetectedConditionResponse> detectedConditions;

    @ArraySchema(arraySchema = @Schema(description = "Clinician-directed suggestions; always empty for patients and "
            + "caregivers"))
    private List<String> recommendations;

    @Schema(description = "Review status", allowableValues = {"PENDING_REVIEW", "CONFIRMED", "REJECTED"})
    private String reviewStatus;

    @Schema(description = "The reviewing doctor's note; always null for patients and caregivers", nullable = true)
    private String reviewNotes;

    @Schema(description = "Doctor who reviewed; null while PENDING_REVIEW", nullable = true, example = "DOC-00002")
    private String reviewedBySystemId;

    @Schema(description = "When it was reviewed (ISO instant); null while PENDING_REVIEW", nullable = true)
    private Instant reviewedAt;

    @Schema(description = "When the screening produced the detection (ISO instant)")
    private Instant createdAt;

    @Schema(description = "True only for a doctor related to the patient while the detection is PENDING_REVIEW")
    private boolean canReview;

    @Schema(description = "Fixed text: automated rule-based screening, not a diagnosis", example = DISCLAIMER)
    private String disclaimer;
}
