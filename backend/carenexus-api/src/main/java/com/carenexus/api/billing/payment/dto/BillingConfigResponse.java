package com.carenexus.api.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the Pay dialog needs to know about the active payment gateway (GET /api/billing/config). */
@Schema(description = "Billing configuration")
public record BillingConfigResponse(
        @Schema(description = "Name of the active gateway", example = "local")
        String provider,
        @Schema(description = "False when the active gateway is the local implementation (no money moves)")
        boolean liveGateway,
        @Schema(description = "Every method the active gateway accepts on initiation, in the fixed order CARD, "
                + "INSURANCE, CASH, MPESA, BANK_TRANSFER")
        List<String> supportedMethods,
        @Schema(description = "The subset a patient or caregiver may pay with (CARD, MPESA); empty = paying online "
                + "is not available")
        List<String> onlineMethods,
        @Schema(description = "Currency of new invoices (administrator setting billing.default-currency)", example = "KES")
        String currency,
        @Schema(description = "True only when the dev-only simulation endpoint is available (local gateway and a "
                + "developer profile)")
        boolean simulationEnabled) {
}
