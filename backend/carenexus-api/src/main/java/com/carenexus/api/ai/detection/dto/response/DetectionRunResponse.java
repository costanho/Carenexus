package com.carenexus.api.ai.detection.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Result of a screening a doctor ran by hand (api/be-ai-detection.md 1.3).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of a manual screening run. Not a diagnosis.")
public class DetectionRunResponse {

    @Schema(description = "What the run produced",
            allowableValues = {"DETECTION_CREATED", "DETECTION_EXISTS", "NO_FINDING", "NO_DATA"})
    private String outcome;

    @Schema(description = "Fixed sentence for the outcome, shown as received",
            example = "Screening found nothing that needs review.")
    private String message;

    @Schema(description = "The new detection (DETECTION_CREATED) or the one already stored today for the same primary "
            + "condition (DETECTION_EXISTS); null for NO_FINDING and NO_DATA", nullable = true)
    private DiseaseDetectionResponse detection;

    @Schema(description = "Version of the provider that ran", example = "rules-v1")
    private String modelVersion;

    @Schema(description = "When this run finished (ISO instant)")
    private Instant assessedAt;

    @Schema(description = "Fixed text: automated rule-based screening, not a diagnosis",
            example = DiseaseDetectionResponse.DISCLAIMER)
    private String disclaimer;
}
