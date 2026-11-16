package com.carenexus.api.careplans.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A goal of a new care plan. New goals start {@code OPEN} with no progress note.
 */
@Schema(description = "A goal of a new care plan; it starts OPEN with no progress note")
public record CarePlanGoalRequest(

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
