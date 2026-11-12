package com.carenexus.api.billing.method.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the administrator's payment list, and the body of a recorded manual payment. Field names are the
 * {@code Payment} entity names. Money has scale 2; date-times are clinic wall-clock without an offset. Every field is
 * always present; a nullable field without a value is {@code null}. The gateway response and the payer's phone
 * number are never part of it.
 */
@Schema(description = "Payment (administrator view)")
public record AdminPaymentResponse(
        @Schema(description = "Primary key; pass it to the refund endpoint", example = "88")
        Integer paymentId,
        @Schema(description = "Paid invoice; null only on legacy rows", nullable = true, example = "42")
        Integer invoiceId,
        @Schema(description = "INV-YYYY-NNNNNN of that invoice; null when invoiceId is null", nullable = true,
                example = "INV-2026-000042")
        String invoiceNo,
        @Schema(description = "Billed patient", example = "PAT-00001")
        String patientSystemId,
        @Schema(description = "\"First Last\" of the patient; null when the account is gone", nullable = true)
        String patientName,
        @Schema(description = "Amount of the payment, above zero", example = "1500.00")
        BigDecimal amount,
        @Schema(description = "ISO-4217, the invoice's currency", example = "KES")
        String currency,
        @Schema(description = "CARD, INSURANCE, CASH, MPESA or BANK_TRANSFER", example = "BANK_TRANSFER")
        String method,
        @Schema(description = "PENDING, COMPLETED, FAILED, REFUNDED or CANCELLED", example = "COMPLETED")
        String status,
        @Schema(description = "MANUAL for manual payments, otherwise the gateway's name", nullable = true,
                example = "MANUAL")
        String gateway,
        @Schema(description = "Gateway reference; for a manual payment the reference typed by the administrator",
                nullable = true, example = "FT26314XK9Q2")
        String providerReference,
        @Schema(description = "Gateway transaction id; always null for manual payments", nullable = true)
        String transactionId,
        @Schema(description = "Saved method used; always null for manual payments", nullable = true)
        Integer paymentMethodId,
        @Schema(description = "Masked label of that saved method (also for a removed method); null when none was used",
                nullable = true, example = "M-PESA •••• 5678")
        String paymentMethodLabel,
        @Schema(description = "Who started it: the patient, a caregiver, or the administrator who recorded it",
                nullable = true, example = "ADM-00001")
        String initiatedBySystemId,
        @Schema(description = "\"First Last\" of that user", nullable = true)
        String initiatedByName,
        @Schema(description = "Set on FAILED rows", nullable = true)
        String failureReason,
        @Schema(description = "Total refunded so far, 0.00 when nothing was refunded", example = "0.00")
        BigDecimal refundedAmount,
        @Schema(description = "What can still be refunded: amount - refundedAmount for a COMPLETED payment that is not "
                + "INSURANCE and is linked to an invoice; 0.00 otherwise", example = "1500.00")
        BigDecimal refundableAmount,
        @Schema(description = "Reason of the last refund", nullable = true)
        String refundReason,
        @Schema(description = "When it completed; null while PENDING and on FAILED or CANCELLED rows", nullable = true)
        LocalDateTime paidAt,
        @Schema(description = "Clinic wall-clock creation time")
        LocalDateTime createdAt,
        @Schema(description = "Last change", nullable = true)
        LocalDateTime updatedAt) {
}
