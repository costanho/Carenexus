package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code PUT /api/billing/invoices/{id}}: full replacement of the editable content of a DRAFT invoice.
 * Patient, doctor, links, currency, invoice number and due date are not editable.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Replace the lines, discount and notes of a draft invoice")
public class UpdateInvoiceRequest {

    @NotEmpty
    @Size(max = 50, message = "size must be between 1 and 50")
    @Schema(description = "1 to 50 lines")
    private List<@Valid @NotNull InvoiceLineItemRequest> lineItems;

    @DecimalMin(value = "0.00", message = "must not be negative")
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Absent or null = 0.00; not above the subtotal", example = "300.00", nullable = true)
    private BigDecimal discount;

    @Size(max = 2000)
    @Schema(description = "At most 2000 characters; absent, null or blank clears the note", nullable = true)
    private String notes;
}
