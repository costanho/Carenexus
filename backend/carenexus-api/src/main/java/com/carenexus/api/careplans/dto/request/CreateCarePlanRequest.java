package com.carenexus.api.careplans.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code POST /api/care-plans}. The author is always the calling doctor; the plan starts {@code ACTIVE}.
 * Array order is the display order. Date rules that compare fields (not before startDate) are checked by the service.
 */
@Schema(description = "A new care plan, written by the calling doctor")
public record CreateCarePlanRequest(

        @Schema(description = "The patient", example = "PAT-00001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
        String patientSystemId,

        @Schema(description = "Consultation of this patient the plan was written from (immutable)", example = "42")
        @Positive(message = "must be a positive number")
        Integer consultationId,

        @Schema(description = "Title", example = "Blood pressure management", maxLength = 255,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 255, message = "must be at most 255 characters")
        String title,

        @Schema(description = "Free-text description", maxLength = 4000)
        @Size(max = 4000, message = "must be at most 4000 characters")
        String description,

        @Schema(description = "First day of the plan (yyyy-MM-dd); past or future", example = "2026-11-10",
                type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate startDate,

        @Schema(description = "Planned end (yyyy-MM-dd); not before startDate", example = "2027-05-10",
                type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate targetDate,

        @Schema(description = "Next review date (yyyy-MM-dd); not before startDate", example = "2027-02-10",
                type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate reviewDate,

        @Schema(description = "Goals in display order (at most 20); absent or null = none")
        @Size(max = 20, message = "must contain at most 20 items")
        List<@NotNull(message = "must not be null") @Valid CarePlanGoalRequest> goals,

        @Schema(description = "Interventions in display order (at most 20); absent or null = none")
        @Size(max = 20, message = "must contain at most 20 items")
        List<@NotNull(message = "must not be null") @Valid CarePlanInterventionRequest> interventions
) {
}
