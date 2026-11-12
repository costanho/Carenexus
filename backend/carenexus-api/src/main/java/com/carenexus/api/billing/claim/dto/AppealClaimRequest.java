package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Body of {@code POST /api/billing/claims/{claimId}/appeal}. The reason is trimmed when the body is read. */
@Getter
@NoArgsConstructor
@Schema(description = "Appeal a rejected or partly approved insurance claim")
public class AppealClaimRequest {

    @NotBlank
    @Size(max = 1000)
    @Schema(description = "Why the decision should be reviewed again, 1 to 1000 characters",
            example = "The pre-authorisation letter covers the full consultation fee.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    public void setReason(String reason) {
        this.reason = reason == null ? null : reason.trim();
    }
}
