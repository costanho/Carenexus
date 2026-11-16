package com.carenexus.api.admin.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/users/{systemId}/deactivate} and {@code POST /api/admin/doctors/{systemId}/reject}.
 * Stored trimmed.
 */
@Schema(description = "Reason for a deactivation or a clinician rejection")
public record ReasonRequest(
        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        @Schema(description = "Deactivation: shown to administrators only. Rejection: included in the email to the "
                + "clinician, so write it for them", example = "Left the practice on 30 November", maxLength = 500)
        String reason) {

    public ReasonRequest {
        reason = AdminText.clean(reason);
    }
}
