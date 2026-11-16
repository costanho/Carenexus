package com.carenexus.api.careplans.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An intervention of a care plan. Interventions are replaced as a whole on every edit.
 */
@Schema(description = "An intervention of a care plan (replaced as a whole on every edit)")
public record CarePlanInterventionRequest(

        @Schema(description = "What is done", example = "Home blood pressure monitoring", maxLength = 500,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @Schema(description = "How often, free text", example = "Daily", maxLength = 100)
        @Size(max = 100, message = "must be at most 100 characters")
        String frequency,

        @Schema(description = "Who does it, free text", example = "Patient", maxLength = 100)
        @Size(max = 100, message = "must be at most 100 characters")
        String responsible
) {
}
