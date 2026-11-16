package com.carenexus.api.billing.method.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A saved payment method as every caller sees it, the owner included: masked detail only. The full phone, policy
 * and member numbers and the gateway token are stored for the payment and claim flows and never returned.
 * <p>
 * {@code isDefault} is a {@link Boolean} so Jackson keeps the JSON name {@code isDefault}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Saved payment method (masked)")
public class PaymentMethodResponse {

    @Schema(description = "Primary key; pass it as paymentMethodId when paying", example = "12")
    private Integer methodId;

    @Schema(description = "Owner (the patient's system id)", example = "PAT-00001")
    private String systemId;

    @Schema(description = "CARD, MPESA or INSURANCE", example = "MPESA")
    private String type;

    @Schema(description = "Name shown before the dots: the card brand (null when none was given), M-PESA, or the insurer",
            nullable = true, example = "M-PESA")
    private String provider;

    @Schema(description = "The four trailing characters shown in the label (card digits, phone digits, or policy "
            + "number characters)", example = "5678")
    private String lastFour;

    @Schema(description = "INSURANCE only (same value as provider); null for the other types", nullable = true)
    private String insurerName;

    @Schema(description = "Masked label built by the server: '<provider or Card> •••• <lastFour>'",
            example = "M-PESA •••• 5678")
    private String displayLabel;

    @Schema(description = "True on exactly one method of a non-empty list", example = "true")
    private Boolean isDefault;

    @Schema(description = "When it was saved (clinic wall-clock, no offset)", example = "2026-11-10T09:15:30")
    private LocalDateTime createdAt;
}
