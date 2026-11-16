package com.carenexus.api.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Body of {@code POST /api/admin/billing/payments/{id}/refund}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Refund a completed payment in full or in part")
public class RefundPaymentRequest {

    @DecimalMin(value = "0.01", message = "must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Absent or null = the whole refundable amount", nullable = true, example = "1000.00")
    private BigDecimal amount;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Why the money is returned; trimmed, at most 500 characters",
            example = "Duplicate charge for the same visit")
    private String reason;
}
