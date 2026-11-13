package com.carenexus.api.billing.invoice.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A completed consultation of the calling doctor that has no invoice in a status other than CANCELLED. */
@Schema(description = "Completed consultation without an invoice")
public record UninvoicedConsultationResponse(
        @Schema(description = "Pass it to POST /api/billing/invoices", example = "211")
        Integer consultationId,
        @Schema(description = "The consultation's appointment", example = "325")
        Integer appointmentId,
        @Schema(description = "VIDEO, IN_PERSON or PHONE", example = "IN_PERSON")
        String appointmentType,
        @Schema(example = "PAT-00002")
        String patientSystemId,
        @Schema(description = "\"First Last\"", nullable = true)
        String patientName,
        @Schema(description = "Clinic wall-clock completion time", nullable = true)
        LocalDateTime completedAt,
        @Schema(description = "Clinic wall-clock start of the appointment")
        LocalDateTime scheduledAt,
        @Schema(description = "Fee for this doctor and appointment type, 0.00 when none", example = "1500.00")
        BigDecimal suggestedFee,
        @Schema(description = "Current clinic currency", example = "KES")
        String currency) {
}
