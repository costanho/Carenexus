package com.carenexus.api.corrections.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/correction-requests/{id}/resolve}. The note is required for both decisions.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Decision on an open correction request")
public class ResolveCorrectionRequest {

    public static final int MAX_NOTE_LENGTH = 1000;

    @Schema(description = "The decision", requiredMode = Schema.RequiredMode.REQUIRED, example = "REJECTED",
            allowableValues = {"ACCEPTED", "REJECTED"})
    @NotBlank(message = "is required")
    @Pattern(regexp = "^(ACCEPTED|REJECTED)$", message = "must be ACCEPTED or REJECTED")
    private String decision;

    @Schema(description = "How the request was resolved; shown to the requester (trimmed, 1 to 1000 characters)",
            requiredMode = Schema.RequiredMode.REQUIRED, maxLength = MAX_NOTE_LENGTH,
            example = "The laboratory report confirms the date recorded; no change was made.")
    @NotBlank(message = "is required")
    @Size(max = MAX_NOTE_LENGTH, message = "must be at most 1000 characters")
    private String note;
}
