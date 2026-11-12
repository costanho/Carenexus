package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Optional body of {@code POST /api/billing/invoices/{id}/cancel}. */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Cancel an invoice that has received no payment")
public class CancelInvoiceRequest {

    @Size(max = 500)
    @Schema(description = "At most 500 characters; blank is stored as null", example = "Issued for the wrong visit",
            nullable = true)
    private String reason;
}
