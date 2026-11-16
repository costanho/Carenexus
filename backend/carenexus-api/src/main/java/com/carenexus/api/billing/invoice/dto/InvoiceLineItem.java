package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One line of an invoice, as stored in {@code invoices.line_items} and returned by the API. {@code amount} is
 * always computed by the server ({@code unitPrice x quantity}, scale 2).
 */
@Schema(description = "Invoice line")
public record InvoiceLineItem(
        @Schema(description = "1 to 200 characters", example = "Video consultation")
        String description,
        @Schema(description = "1 to 100", example = "1")
        Integer quantity,
        @Schema(description = "Price of one unit", example = "2000.00")
        BigDecimal unitPrice,
        @Schema(description = "unitPrice x quantity", example = "2000.00")
        BigDecimal amount) {
}
