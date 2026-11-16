package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An invoice as every billing endpoint returns it. Money values have scale 2; date-times are clinic wall-clock
 * ({@code carenexus.scheduling.time-zone}) without an offset. Every field is always present; a nullable field
 * without a value is {@code null}.
 */
@Schema(description = "Invoice")
public record InvoiceResponse(
        @Schema(description = "Primary key", example = "42")
        Integer invoiceId,
        @Schema(description = "INV-<year>-<6 digits>", example = "INV-2026-000042")
        String invoiceNo,
        @Schema(description = "Billed patient", example = "PAT-00001")
        String patientSystemId,
        @Schema(description = "\"First Last\" of the patient; null when the account is gone", nullable = true)
        String patientName,
        @Schema(description = "Issuing doctor (the doctor of the visit); null only on legacy rows", nullable = true,
                example = "DOC-00001")
        String doctorSystemId,
        @Schema(description = "\"First Last\" of the issuing doctor, without a title", nullable = true)
        String doctorName,
        @Schema(description = "Who created it: the doctor, an administrator, or the doctor for an automatic invoice",
                nullable = true)
        String createdBySystemId,
        @Schema(description = "Linked appointment", nullable = true)
        Integer appointmentId,
        @Schema(description = "Linked consultation", nullable = true)
        Integer consultationId,
        @Schema(description = "1 to 50 lines (empty only on legacy rows)")
        List<InvoiceLineItem> lineItems,
        @Schema(description = "Sum of the line amounts", example = "2500.00")
        BigDecimal subtotal,
        @Schema(description = "Tax: (subtotal - discount) x tax rate", example = "400.00")
        BigDecimal tax,
        @Schema(description = "Discount, 0.00 when none", example = "0.00")
        BigDecimal discount,
        @Schema(description = "subtotal - discount + tax", example = "2900.00")
        BigDecimal total,
        @Schema(description = "Sum of the payments applied so far", example = "0.00")
        BigDecimal amountPaid,
        @Schema(description = "What can still be paid now: total - amountPaid for SENT, PARTIALLY_PAID and OVERDUE, "
                + "0.00 otherwise", example = "2900.00")
        BigDecimal outstanding,
        @Schema(description = "ISO-4217", example = "KES")
        String currency,
        @Schema(description = "DRAFT, SENT, PARTIALLY_PAID, PAID, OVERDUE, CANCELLED or REFUNDED", example = "SENT")
        String status,
        @Schema(description = "Clinic calendar date; null only on legacy rows", nullable = true, example = "2026-11-24")
        LocalDate dueDate,
        @Schema(description = "When it was sent; null while DRAFT", nullable = true)
        LocalDateTime issuedAt,
        @Schema(description = "When it became PAID; null otherwise", nullable = true)
        LocalDateTime paidAt,
        @Schema(description = "Reason given at cancel or void", nullable = true)
        String cancelReason,
        @Schema(description = "Free text shown to the patient; never clinical detail", nullable = true)
        String notes,
        @Schema(description = "Clinic wall-clock creation time")
        LocalDateTime createdAt,
        @Schema(description = "Last change", nullable = true)
        LocalDateTime updatedAt) {
}
