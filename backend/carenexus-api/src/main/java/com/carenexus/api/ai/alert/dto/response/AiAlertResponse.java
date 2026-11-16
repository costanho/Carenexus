package com.carenexus.api.ai.alert.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One AI alert as returned by the inbox and the per-patient list (api/be-ai-core.md 1.1).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An automated clinical alert about a patient")
public class AiAlertResponse {

    /** Fixed text carried by every alert DTO. */
    public static final String DISCLAIMER =
            "This alert comes from automated rule-based screening. It is not a diagnosis.";

    @Schema(description = "Alert id", example = "412")
    private Integer alertId;

    @Schema(description = "The patient the alert is about", example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "First and last name of the patient", example = "Tendai Moyo")
    private String patientName;

    @Schema(description = "Doctor the alert is addressed to; null = every doctor related to the patient",
            example = "DOC-00002", nullable = true)
    private String doctorSystemId;

    @Schema(description = "Screening result the alert came from (always set for DISEASE_RISK)", nullable = true)
    private Integer detectionId;

    @Schema(description = "Review status of that screening result; null when detectionId is null", nullable = true,
            allowableValues = {"PENDING_REVIEW", "CONFIRMED", "REJECTED"})
    private String detectionReviewStatus;

    @Schema(description = "Alert type", allowableValues = {"CRITICAL_VITALS", "DISEASE_RISK",
            "MEDICATION_INTERACTION", "MISSED_MEDICATION", "FOLLOW_UP_DUE", "ANOMALY_DETECTED", "CRITICAL_RESULT"})
    private String alertType;

    @Schema(description = "Alert priority", allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
    private String priority;

    @Schema(description = "Short heading (at most 160 characters); the default title of the type when none was stored",
            example = "Critical lab result")
    private String title;

    @Schema(description = "Alert text")
    private String message;

    @Schema(description = "The raiser marked the alert as needing clinician action")
    private boolean actionRequired;

    @Schema(description = "Clinician-directed suggestion; always null for patients and caregivers", nullable = true)
    private String suggestedAction;

    @Schema(description = "Record that triggered the alert", nullable = true, allowableValues = {"LAB_RESULT",
            "IMAGING_RESULT", "HEALTH_METRIC", "SYMPTOM_LOG", "PRESCRIPTION", "MEDICATION_SCHEDULE", "CONSULTATION"})
    private String sourceType;

    @Schema(description = "Id of the source record (meaning depends on sourceType)", nullable = true)
    private Integer sourceId;

    @Schema(description = "Whether a doctor acknowledged the alert")
    private boolean acknowledged;

    @Schema(description = "Doctor who acknowledged the alert; null while unacknowledged", nullable = true)
    private String acknowledgedBySystemId;

    @Schema(description = "When the alert was acknowledged (ISO-8601 instant); null while unacknowledged", nullable = true)
    private Instant acknowledgedAt;

    @Schema(description = "When the alert was raised (ISO-8601 instant)")
    private Instant createdAt;

    @Schema(description = "True only for a doctor who may acknowledge this still unacknowledged alert")
    private boolean canAcknowledge;

    @Schema(description = "Automated-screening notice", example = DISCLAIMER)
    @Builder.Default
    private String disclaimer = DISCLAIMER;
}
