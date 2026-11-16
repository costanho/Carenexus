package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Body of {@code POST /api/admin/billing/invoices/{id}/void}. The reason is trimmed when the body is read. */
@Getter
@NoArgsConstructor
@Schema(description = "Void an invoice that has received no payment")
public class VoidInvoiceRequest {

    @NotBlank
    @Size(max = 500)
    @Schema(description = "Why the invoice is voided, 1 to 500 characters; stored as the invoice's cancelReason",
            example = "Billed in error: the visit was free of charge", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    public void setReason(String reason) {
        this.reason = reason == null ? null : reason.trim();
    }
}
