package com.carenexus.api.careplans.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/care-plans/{id}/goals/{goalId}}. At least one field must be non-null.
 */
@Schema(description = "Goal progress: a new status and / or progress note (at least one)")
public record GoalProgressRequest(

        @Schema(description = "OPEN, IN_PROGRESS, ACHIEVED or DROPPED (case-insensitive); absent or null = unchanged. "
                + "Only the author doctor may set DROPPED or change a dropped goal.", example = "IN_PROGRESS")
        @Pattern(regexp = "^\\s*(OPEN|IN_PROGRESS|ACHIEVED|DROPPED)\\s*$", flags = Pattern.Flag.CASE_INSENSITIVE,
                message = "must be one of OPEN, IN_PROGRESS, ACHIEVED, DROPPED")
        String status,

        @Schema(description = "Latest progress note; absent or null = unchanged; empty or blank clears it",
                example = "Managing three days a week so far", maxLength = 1000)
        @Size(max = 1000, message = "must be at most 1000 characters")
        String progressNote
) {
}
