package com.carenexus.api.billing.fee.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * The consultation fee that applies to one appointment type. Fee lists always hold exactly three rows, in the order
 * VIDEO, IN_PERSON, PHONE.
 */
@Schema(description = "Consultation fee of one appointment type")
public record FeeResponse(
        @Schema(description = "VIDEO, IN_PERSON or PHONE", example = "VIDEO")
        String appointmentType,
        @Schema(description = "Fee that applies: the doctor's override, else the clinic default, else 0.00", example = "2000.00")
        BigDecimal amount,
        @Schema(description = "Current clinic currency (ISO-4217); the currency a new invoice uses", example = "KES")
        String currency,
        @Schema(description = "DOCTOR (the doctor's own override), DEFAULT (clinic default) or NONE (no active row)",
                example = "DOCTOR")
        String source,
        @Schema(description = "Clinic default for this type, 0.00 when there is none", example = "1500.00")
        BigDecimal defaultAmount,
        @Schema(description = "Doctor the row was resolved for; null for the clinic defaults", example = "DOC-00001",
                nullable = true)
        String doctorSystemId) {
}
