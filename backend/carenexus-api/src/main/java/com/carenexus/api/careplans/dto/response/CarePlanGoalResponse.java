package com.carenexus.api.careplans.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One goal of a care plan.
 */
@Schema(description = "A goal of a care plan")
public record CarePlanGoalResponse(

        @Schema(description = "Goal id; stable across edits when the client sends it back", example = "31")
        Integer goalId,

        @Schema(description = "What the patient should achieve", example = "Walk 30 minutes five days a week")
        String description,

        @Schema(description = "Planned date (yyyy-MM-dd) or null", example = "2027-01-31", type = "string",
                format = "date", nullable = true)
        LocalDate targetDate,

        @Schema(description = "OPEN, IN_PROGRESS, ACHIEVED or DROPPED", example = "IN_PROGRESS")
        String status,

        @Schema(description = "Latest progress note or null", nullable = true)
        String progressNote,

        @Schema(description = "0-based display order, equal to the array index", example = "0")
        Integer position,

        @Schema(description = "Clinic wall-clock time of creation, then of the last change to this goal",
                example = "2026-11-18T07:42:10.418223", type = "string", format = "date-time")
        LocalDateTime updatedAt
) {
}
