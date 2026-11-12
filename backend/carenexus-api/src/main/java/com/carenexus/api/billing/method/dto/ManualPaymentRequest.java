package com.carenexus.api.billing.method.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Body of POST /api/admin/billing/invoices/{id}/payments/manual: money the clinic already received in cash or by
 * bank transfer. Strings are trimmed when read; a blank string counts as absent. The patient, the currency, the
 * payer and the payment time are never taken from the body.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Record a cash or bank-transfer payment against an invoice")
public class ManualPaymentRequest {

    public static final String METHOD_PATTERN = "(?i)^(CASH|BANK_TRANSFER)$";
    public static final String METHOD_MESSAGE = "must be one of CASH, BANK_TRANSFER";

    @NotBlank
    @Pattern(regexp = METHOD_PATTERN, message = METHOD_MESSAGE)
    @Schema(description = "CASH or BANK_TRANSFER (case-insensitive)", example = "BANK_TRANSFER",
            allowableValues = {"CASH", "BANK_TRANSFER"})
    private String method;

    @NotNull
    @DecimalMin(value = "0", inclusive = false, message = "must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Amount received; at most the invoice's outstanding balance", example = "1500.00")
    private BigDecimal amount;

    @Size(max = 100)
    @Schema(description = "Bank transaction reference (required for BANK_TRANSFER) or cash receipt number "
            + "(optional); not unique", nullable = true, example = "FT26314XK9Q2", maxLength = 100)
    private String reference;

    public void setMethod(String method) {
        this.method = clean(method);
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setReference(String reference) {
        this.reference = clean(reference);
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
