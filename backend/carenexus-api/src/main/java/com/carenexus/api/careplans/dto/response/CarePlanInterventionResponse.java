package com.carenexus.api.careplans.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One intervention of a care plan.
 */
@Schema(description = "An intervention of a care plan")
public record CarePlanInterventionResponse(

        @Schema(description = "Intervention id; NOT stable across edits (interventions are replaced as a whole)",
                example = "55")
        Integer interventionId,

        @Schema(description = "What is done", example = "Home blood pressure monitoring")
        String description,

        @Schema(description = "How often, or null", example = "Daily", nullable = true)
        String frequency,

        @Schema(description = "Who does it, or null", example = "Patient", nullable = true)
        String responsible,

        @Schema(description = "0-based display order, equal to the array index", example = "0")
        Integer position
) {
}
