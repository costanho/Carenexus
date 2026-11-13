package com.carenexus.api.ai.detection.dto.request;

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
 * Body of {@code POST /api/disease-detections/{detectionId}/review} (api/be-ai-detection.md 4.3). The constraints are
 * declared so that one message fires per field: the decision pattern also accepts null and blank, which are left to
 * {@code @NotBlank}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A doctor's review of a screening result")
public class ReviewDetectionRequest {

    public static final String CONFIRM = "CONFIRM";
    public static final String REJECT = "REJECT";
    public static final int MAX_NOTES_LENGTH = 2000;

    /** Blank, or CONFIRM / REJECT in any case with surrounding spaces. */
    static final String DECISION_PATTERN = "^\\s*$|^\\s*(?i:CONFIRM|REJECT)\\s*$";

    @Schema(description = "CONFIRM or REJECT (case-insensitive, trimmed)", example = "CONFIRM",
            allowableValues = {CONFIRM, REJECT}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = DECISION_PATTERN, message = "must be CONFIRM or REJECT")
    private String decision;

    @Schema(description = "Optional note, at most 2000 characters; trimmed, blank is stored as null. Never shown to the "
            + "patient or caregivers", example = "Seen in clinic today, chest examination consistent with the screening.")
    @Size(max = MAX_NOTES_LENGTH)
    private String notes;
}
