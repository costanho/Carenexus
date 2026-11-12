package com.carenexus.api.careplans.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A goal in the full replacement of a care plan (PUT). With {@code goalId} it updates that goal of the plan and keeps
 * its status and progress note; without it, it is a new {@code OPEN} goal.
 */
@Schema(description = "A goal of the edited plan: send goalId back to keep an existing goal and its progress")
public record CarePlanGoalUpdateRequest(

        @Schema(description = "Id of an existing goal of this plan; absent = new goal. Each id at most once.",
                example = "31")
        Integer goalId,

        @Schema(description = "What the patient should achieve", example = "Walk 30 minutes five days a week",
                maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @Schema(description = "Planned date (yyyy-MM-dd); not before the plan's startDate", example = "2027-01-31",
                type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate targetDate
) {
}
