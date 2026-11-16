package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Body of {@code PATCH /api/admin/billing/claims/{claimId}} (administrator adjudication). Strings are trimmed when
 * the body is read; {@code status} is compared case-insensitively.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Move an insurance claim along its state machine")
public class AdjudicateClaimRequest {

    /** Blank passes the pattern so a missing value is reported only once, as "must not be blank". */
    static final String STATUS_PATTERN = "(?i)(PENDING|APPROVED|PARTIAL|REJECTED)?";

    @NotBlank
    @Pattern(regexp = STATUS_PATTERN, message = "must be one of PENDING, APPROVED, PARTIAL, REJECTED")
    @Schema(description = "Target status", allowableValues = {"PENDING", "APPROVED", "PARTIAL", "REJECTED"},
            requiredMode = Schema.RequiredMode.REQUIRED, example = "PARTIAL")
    private String status;

    @Positive
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Cumulative approved amount; required for PARTIAL (below claimAmount and not below the "
            + "amount already approved), ignored for every other status", example = "1500.00", nullable = true)
    private BigDecimal approvedAmount;

    @Size(max = 1000)
    @Schema(description = "Required and not blank for REJECTED; optional reviewer's note for PARTIAL (blank is stored "
            + "as null); ignored for PENDING and APPROVED", nullable = true,
            example = "The policy covers consultations up to KES 1,500")
    private String rejectionReason;

    public void setStatus(String status) {
        this.status = status == null ? null : status.trim();
    }

    public void setApprovedAmount(BigDecimal approvedAmount) {
        this.approvedAmount = approvedAmount;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason == null ? null : rejectionReason.trim();
    }
}
