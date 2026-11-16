package com.carenexus.api.careplans.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code PUT /api/care-plans/{id}}: a full replacement of the editable content. A field that is absent or
 * null is cleared. Patient, consultation, status and review fields cannot be changed here.
 */
@Schema(description = "The complete editable content of a care plan (not a partial update)")
public record UpdateCarePlanRequest(

        @Schema(description = "Title", example = "Blood pressure management", maxLength = 255,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String title,

        @Schema(description = "Free-text description; absent, null or blank clears it", maxLength = 4000)
        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,

        @Schema(description = "First day of the plan (yyyy-MM-dd)", example = "2026-11-10", type = "string",
                format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate startDate,

        @Schema(description = "Planned end (yyyy-MM-dd); not before startDate; absent or null clears it",
                example = "2027-05-10", type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate targetDate,

        @Schema(description = "Next review date (yyyy-MM-dd); not before startDate; absent or null clears it",
                example = "2027-02-10", type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate reviewDate,

        @Schema(description = "Every goal of the plan in display order (at most 20; [] removes all). Send goalId "
                + "back for existing goals; a goal left out is removed.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "is required")
        @Size(max = 20, message = "must contain at most 20 items")
        List<@NotNull(message = "must not be null") @Valid CarePlanGoalUpdateRequest> goals,

        @Schema(description = "Every intervention of the plan in display order (at most 20; [] removes all)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "is required")
        @Size(max = 20, message = "must contain at most 20 items")
        List<@NotNull(message = "must not be null") @Valid CarePlanInterventionRequest> interventions
) {
}
