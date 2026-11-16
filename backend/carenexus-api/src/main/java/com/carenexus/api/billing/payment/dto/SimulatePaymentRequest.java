package com.carenexus.api.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of the dev-only {@code POST /api/billing/payments/{id}/simulate}. No one-argument constructor on purpose:
 * with one, Jackson would accept a bare JSON string as the body.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Settle a pending local payment the way the gateway would")
public class SimulatePaymentRequest {

    public static final String OUTCOME_COMPLETED = "COMPLETED";
    public static final String OUTCOME_FAILED = "FAILED";

    @NotBlank
    @Pattern(regexp = "(?i)^\\s*(COMPLETED|FAILED)?\\s*$", message = "must be one of COMPLETED, FAILED")
    @Schema(description = "COMPLETED or FAILED, case-insensitive", example = "COMPLETED")
    private String outcome;
}
