package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line of a create or edit request. An {@code amount} sent by the client is ignored (unknown property); the
 * server computes it. Limits keep the invoice total inside {@code NUMERIC(12,2)}: at most 50 lines, quantity at
 * most 100, unit price at most 999999.99.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Invoice line to create or replace")
public class InvoiceLineItemRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Not blank, at most 200 characters (trimmed)", example = "Video consultation")
    private String description;

    @NotNull
    @Min(value = 1, message = "must be between 1 and 100")
    @Max(value = 100, message = "must be between 1 and 100")
    @Schema(description = "1 to 100", example = "1")
    private Integer quantity;

    @NotNull
    @DecimalMin(value = "0.00", message = "must not be negative")
    @Digits(integer = 6, fraction = 2, message = "must have at most 6 digits and 2 decimals")
    @Schema(description = "0.00 to 999999.99", example = "2000.00")
    private BigDecimal unitPrice;
}
