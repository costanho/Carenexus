package com.carenexus.api.scheduling.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** Start times of one doctor on one clinic day ({@code GET /api/appointments/doctor/{doctorSystemId}/slots}). */
@Schema(name = "DoctorSlots", description = "Start times of one doctor on one clinic day, each flagged bookable or not")
public record DoctorSlotsResponse(
        @Schema(description = "The doctor asked for", example = "DOC-00002")
        String doctorSystemId,
        @Schema(description = "Clinic calendar day, yyyy-MM-dd", example = "2026-11-12", type = "string", format = "date")
        LocalDate date,
        @Schema(description = "Clinic time zone", example = "Africa/Harare")
        String timeZone,
        @Schema(description = "Which working hours the list was built from", allowableValues = {"TEMPLATE", "CLINIC_HOURS"})
        String source,
        @Schema(description = "Distance between consecutive starts of one window: the template value, or 30 with "
                + "CLINIC_HOURS", example = "30")
        int slotMinutes,
        @Schema(description = "Appointment length the list was computed for", example = "30")
        int durationMinutes,
        @ArraySchema(arraySchema = @Schema(description = "Ascending by start (at most 288); empty when the doctor has "
                + "no working hours that day long enough for durationMinutes"), maxItems = 288)
        List<DoctorSlotResponse> slots) {
}
