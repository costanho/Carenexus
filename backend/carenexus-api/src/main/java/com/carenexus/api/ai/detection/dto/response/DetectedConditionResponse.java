package com.carenexus.api.ai.detection.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * One finding of a screening (api/be-ai-detection.md 1.2).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One finding of an automated rule-based screening; a finding, not a diagnosis")
public class DetectedConditionResponse {

    @Schema(description = "Stable identifier of the finding (display name instead of switching on it)",
            example = "FEVER_RESPIRATORY")
    private String code;

    @Schema(description = "Display text, at most 120 characters", example = "Fever with respiratory symptoms")
    private String name;

    @Schema(description = "Level of this finding", allowableValues = {"LOW", "MEDIUM", "HIGH", "CRITICAL"})
    private String riskLevel;

    @Schema(description = "Confidence from 0 to 1, at most 3 decimals", example = "0.75")
    private BigDecimal confidence;

    @ArraySchema(arraySchema = @Schema(description = "1 to 5 sentences naming the readings or symptoms that matched, "
            + "with their clinic date"), schema = @Schema(example = "Temperature 39.6 C recorded on 2026-11-09"))
    private List<String> evidence;
}
