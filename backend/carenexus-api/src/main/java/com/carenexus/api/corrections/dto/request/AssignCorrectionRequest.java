package com.carenexus.api.corrections.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PATCH /api/admin/correction-requests/{id}/assign}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The doctor who must review a correction request")
public class AssignCorrectionRequest {

    @Schema(description = "An active doctor with a care relationship with the patient (see assignable-doctors)",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "DOC-00007")
    @NotBlank(message = "is required")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    private String doctorSystemId;
}
