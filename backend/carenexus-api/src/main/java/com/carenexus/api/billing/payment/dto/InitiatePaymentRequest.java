package com.carenexus.api.billing.payment.dto;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/billing/invoices/{invoiceId}/payments}. Card details are never sent: with CARD the body
 * carries at most a saved {@code paymentMethodId}. The payer and the patient come from the token and the invoice,
 * never from the body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Start a payment for an invoice")
public class InitiatePaymentRequest {

    /** The empty alternative lets a blank value report only "must not be blank". */
    static final String METHOD_PATTERN = "(?i)^\\s*(CARD|MPESA|BANK_TRANSFER|CASH|INSURANCE)?\\s*$";

    @NotBlank
    @Pattern(regexp = METHOD_PATTERN, message = "must be one of CARD, MPESA, BANK_TRANSFER, CASH, INSURANCE")
    @Schema(description = "CARD or MPESA for patients and caregivers; CASH, BANK_TRANSFER and INSURANCE for "
            + "administrators only. Case-insensitive.", example = "MPESA")
    private String method;

    @Positive
    @Schema(description = "A saved payment method (methodId) of the invoice's patient whose type equals method",
            nullable = true, example = "7")
    private Integer paymentMethodId;

    @DecimalMin(value = "0.01", message = "must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Absent or null = the whole outstanding balance", nullable = true, example = "2900.00")
    private BigDecimal amount;

    @Size(max = 20)
    @Pattern(regexp = ValidationPatterns.PHONE, message = "must be a valid phone number")
    @Schema(description = "MPESA: phone to prompt; required unless the saved M-PESA method supplies one. Ignored for "
            + "every other method.", nullable = true, example = "+254 712 344 567")
    private String payerPhone;
}
