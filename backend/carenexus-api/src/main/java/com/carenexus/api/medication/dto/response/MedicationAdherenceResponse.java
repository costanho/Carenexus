package com.carenexus.api.medication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Taken, missed and pending dose counts of a patient over the last {@code days} clinic days, today included.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "MedicationAdherenceResponse", description = "Dose counts over a window of clinic days")
public record MedicationAdherenceResponse(
        @Schema(description = "The patient", example = "PAT-00001")
        String patientSystemId,

        @Schema(description = "Window length that was applied (1 to 90)", example = "30")
        int days,

        @Schema(description = "First day of the window: clinic today minus (days - 1)", example = "2026-10-12",
                type = "string", format = "date")
        LocalDate from,

        @Schema(description = "Last day of the window: the clinic's today (tomorrow's doses are never counted)",
                example = "2026-11-10", type = "string", format = "date")
        LocalDate to,

        @Schema(description = "Doses in the window with status TAKEN", example = "52")
        long taken,

        @Schema(description = "Doses in the window with status MISSED", example = "5")
        long missed,

        @Schema(description = "Doses in the window with status PENDING", example = "2")
        long pending,

        @Schema(description = "taken + missed + pending", example = "59")
        long total,

        @Schema(description = "round(100 * taken / (taken + missed)), half up; null when taken + missed = 0",
                example = "91", nullable = true)
        Integer adherenceRate) {
}
