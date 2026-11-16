package com.carenexus.api.medication.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The daily dose times of a prescription. A prescription without a stored plan has {@code times = []} and null
 * author and time.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "MedicationSchedulePlanResponse", description = "Daily dose times of a prescription")
public record MedicationSchedulePlanResponse(
        @Schema(description = "The prescription (also the plan's key)", example = "57")
        Integer prescriptionId,

        @Schema(description = "The prescription's patient", example = "PAT-00001")
        String patientSystemId,

        @ArraySchema(arraySchema = @Schema(description = "Daily dose times, clinic wall-clock HH:mm, distinct and "
                + "ascending; empty when the prescription has no scheduled doses", example = "[\"08:00\", \"20:00\"]"),
                schema = @Schema(type = "string", pattern = "^([01]\\d|2[0-3]):[0-5]\\d$"))
        List<String> times,

        @Schema(description = "Doctor who last set the times; null when no plan is stored", example = "DOC-00002",
                nullable = true)
        String updatedBySystemId,

        @Schema(description = "Clinic wall-clock date-time the times were last set; null when no plan is stored",
                example = "2026-11-10T09:15:27", nullable = true, type = "string", format = "date-time")
        LocalDateTime updatedAt) {
}
