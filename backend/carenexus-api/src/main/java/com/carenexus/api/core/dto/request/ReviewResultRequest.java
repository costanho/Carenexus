package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Body of {@code POST /api/lab-results/{id}/review} and {@code POST /api/imaging-results/{id}/review}. The reviewer
 * is always the calling doctor. Strings are trimmed when they are read; blank notes are stored as null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Sign-off of a result by a doctor of the patient")
public class ReviewResultRequest {

    @JsonProperty("status")
    @NotBlank(message = "is required")
    @Pattern(regexp = "(?i)^(COMPLETED|CRITICAL)$", message = "must be COMPLETED or CRITICAL")
    @Schema(description = "Status after the review: COMPLETED or CRITICAL (case-insensitive)", example = "CRITICAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;

    @JsonProperty("reviewNotes")
    @Size(max = 2000, message = "must be at most 2000 characters")
    @Schema(description = "Note of the reviewer; replaces an earlier note", example = "Repeat FBC in 48 hours. Patient called.")
    private String reviewNotes;

    public void setStatus(String status) {
        this.status = CreateLabResultRequest.trim(status);
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = CreateLabResultRequest.trim(reviewNotes);
    }
}
