package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Body of {@code POST /api/billing/invoices}. The patient and the doctor are never taken from the body: they come
 * from the consultation or appointment. Give a {@code consultationId} or an {@code appointmentId} (or both, when
 * the consultation belongs to that appointment).
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Generate the invoice of a consultation or an appointment")
public class CreateInvoiceRequest {

    @Positive
    @Schema(description = "Completed consultation to invoice", example = "207", nullable = true)
    private Integer consultationId;

    @Positive
    @Schema(description = "Appointment to invoice", example = "318", nullable = true)
    private Integer appointmentId;

    @Size(max = 50)
    @Schema(description = "0 to 50 lines; absent or empty = one line generated from the fee schedule", nullable = true)
    private List<@Valid @NotNull InvoiceLineItemRequest> lineItems;

    @DecimalMin(value = "0.00", message = "must not be negative")
    @Digits(integer = 10, fraction = 2, message = "must have at most 10 digits and 2 decimals")
    @Schema(description = "Default 0.00; not above the subtotal", example = "0.00", nullable = true)
    private BigDecimal discount;

    @Size(max = 2000)
    @Schema(description = "At most 2000 characters; blank is stored as null", nullable = true)
    private String notes;
}
