package com.carenexus.api.account.datarights.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/admin/account-requests/{id}/reject}. No one-argument constructor on purpose: Jackson would
 * then accept a bare JSON string as the body.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Why an administrator declines a deletion request")
public class RejectAccountRequest {

    @NotBlank
    @Size(max = 500, message = "must be at most 500 characters")
    @Schema(description = "Reason shown to the user as decisionReason (trimmed)",
            requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500)
    private String reason;
}
