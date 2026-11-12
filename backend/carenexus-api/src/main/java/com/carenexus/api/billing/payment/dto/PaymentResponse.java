package com.carenexus.api.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A payment as every payment endpoint returns it (api/be-billing-payments.md 4.1). Money values have scale 2;
 * date-times are clinic wall-clock ({@code carenexus.scheduling.time-zone}) without an offset. Every field is always
 * present; a nullable field without a value is {@code null}. No card number, token or raw gateway response is ever
 * part of it, and the payer's phone is masked.
 */
@Schema(description = "Payment")
public record PaymentResponse(
        @Schema(description = "Primary key", example = "88")
        Integer paymentId,
        @Schema(description = "Invoice the payment belongs to (null only on legacy rows)", nullable = true, example = "42")
        Integer invoiceId,
        @Schema(description = "INV-<year>-<6 digits> of that invoice", nullable = true, example = "INV-2026-000042")
        String invoiceNo,
        @Schema(description = "Patient the invoice is billed to", example = "PAT-00001")
        String patientSystemId,
        @Schema(description = "\"First Last\" of the patient; null when the account is gone", nullable = true)
        String patientName,
        @Schema(description = "Who started or recorded the payment (patient, caregiver or administrator); null for "
                + "system-created rows", nullable = true, example = "CGV-00001")
        String initiatedBySystemId,
        @Schema(description = "\"First Last\" of that user", nullable = true)
        String initiatedByName,
        @Schema(description = "Amount of the payment, above zero", example = "2900.00")
        BigDecimal amount,
        @Schema(description = "ISO-4217, the invoice's currency", example = "KES")
        String currency,
        @Schema(description = "CARD, INSURANCE, CASH, MPESA or BANK_TRANSFER", example = "MPESA")
        String method,
        @Schema(description = "Saved payment method used (methodId), null when none", nullable = true, example = "7")
        Integer paymentMethodId,
        @Schema(description = "Masked label of that saved method; null when none was used or the row is gone",
                nullable = true, example = "M-PESA •••• 4567")
        String paymentMethodLabel,
        @Schema(description = "PENDING, COMPLETED, FAILED, REFUNDED or CANCELLED", example = "PENDING")
        String status,
        @Schema(description = "What the payer must do: NONE, STK_PUSH, AWAIT_CONFIRMATION or REDIRECT; NONE unless "
                + "the payment is PENDING", example = "STK_PUSH")
        String nextAction,
        @Schema(description = "Gateway page, only with nextAction REDIRECT", nullable = true)
        String redirectUrl,
        @Schema(description = "Gateway or channel that handled the payment (opaque label)", nullable = true,
                example = "local")
        String gateway,
        @Schema(description = "The gateway's reference of the payment (support lookups)", nullable = true)
        String providerReference,
        @Schema(description = "Confirmation or receipt code reported when the payment settled", nullable = true,
                example = "LOC-8K2M4QX7P1ZD")
        String transactionId,
        @Schema(description = "Masked phone the prompt was sent to (last four digits only)", nullable = true,
                example = "**** 4567")
        String payerPhone,
        @Schema(description = "Short displayable sentence when the status is FAILED", nullable = true)
        String failureReason,
        @Schema(description = "Total refunded so far", example = "0.00")
        BigDecimal refundedAmount,
        @Schema(description = "What an administrator can still refund: amount - refundedAmount for a COMPLETED, "
                + "non-insurance payment linked to an invoice; 0.00 otherwise", example = "0.00")
        BigDecimal refundableAmount,
        @Schema(description = "Reason of the most recent refund", nullable = true)
        String refundReason,
        @Schema(description = "When it became COMPLETED (clinic wall-clock); kept after a refund", nullable = true)
        LocalDateTime paidAt,
        @Schema(description = "Clinic wall-clock creation time")
        LocalDateTime createdAt,
        @Schema(description = "Last change", nullable = true)
        LocalDateTime updatedAt) {
}
