package com.carenexus.api.scheduling.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The calling doctor's weekly template ({@code GET} and {@code PUT /api/doctors/availability}). */
@Schema(description = "A doctor's weekly working-hours template")
public record DoctorAvailabilityResponse(
        @Schema(description = "The doctor", example = "DOC-00002")
        String doctorSystemId,
        @Schema(description = "Clinic time zone; every time is wall-clock in it", example = "Africa/Harare")
        String timeZone,
        @Schema(description = "TEMPLATE when at least one block is stored (active or not); CLINIC_HOURS when none is "
                + "and patients book against clinic hours", allowableValues = {"TEMPLATE", "CLINIC_HOURS"})
        String source,
        @Schema(description = "Slot length shared by all blocks (5 to 240, steps of 5); 30 with CLINIC_HOURS",
                example = "30")
        int slotMinutes,
        @ArraySchema(arraySchema = @Schema(description = "Blocks ordered by dayOfWeek, then startTime (at most 42); "
                + "always empty with CLINIC_HOURS"), maxItems = 42)
        List<AvailabilityBlockResponse> blocks) {
}
