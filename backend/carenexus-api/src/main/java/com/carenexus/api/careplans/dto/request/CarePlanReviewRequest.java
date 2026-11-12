package com.carenexus.api.careplans.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code PATCH /api/care-plans/{id}/review} ({@code {}} is valid). Records a review now and sets the next
 * review date.
 */
@Schema(description = "A review of the plan: the next review date and an optional note")
public record CarePlanReviewRequest(

        @Schema(description = "Next review (yyyy-MM-dd): today in the clinic zone or later, and not before the plan's "
                + "startDate; absent or null = no further review scheduled", example = "2027-05-10", type = "string",
                format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate nextReviewDate,

        @Schema(description = "Review note; overwrites the previous one; absent, null or blank stores none",
                maxLength = 1000)
        @Size(max = 1000, message = "must be at most 1000 characters")
        String note
) {
}
