package com.carenexus.api.referrals.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/referrals/{id}/decline}. The reason is required for every caller and is stored as the
 * referral's decline reason.
 */
@Schema(description = "Why the referral is declined")
public record DeclineReferralRequest(

        @Schema(description = "Reason for declining", maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters")
        String reason) {

    public DeclineReferralRequest {
        reason = TextInput.trim(reason);
    }
}
