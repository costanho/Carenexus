package com.carenexus.api.careplans.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code PATCH /api/care-plans/{id}/status}.
 */
@Schema(description = "The new plan status")
public record CarePlanStatusRequest(

        @Schema(description = "ACTIVE, ON_HOLD, COMPLETED or DISCONTINUED (case-insensitive). COMPLETED and "
                + "DISCONTINUED are terminal.", example = "ON_HOLD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        // A blank value is reported once, by @NotBlank.
        @Pattern(regexp = "^\\s*$|^\\s*(ACTIVE|ON_HOLD|COMPLETED|DISCONTINUED)\\s*$",
                flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be one of ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED")
        String status
) {
}
