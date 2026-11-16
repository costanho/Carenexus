package com.carenexus.api.scheduling.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** One offered start time. The reason a start is not available is deliberately not disclosed. */
@Schema(name = "DoctorSlot", description = "A start time inside the doctor's working hours")
public record DoctorSlotResponse(
        @Schema(description = "Clinic wall-clock start, yyyy-MM-dd'T'HH:mm:ss; send it unchanged as scheduledAt",
                example = "2026-11-12T09:00:00", type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime start,
        @Schema(description = "true = a patient-side booking of durationMinutes starting here would pass the doctor-side "
                + "checks now; false = past, beyond the booking horizon, inside time off or taken", example = "true")
        boolean available) {
}
