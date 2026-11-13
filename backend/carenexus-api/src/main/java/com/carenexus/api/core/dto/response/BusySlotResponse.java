package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** One busy period of a doctor (an active appointment), without any patient detail. */
@Schema(description = "A busy period of a doctor's calendar")
public record BusySlotResponse(
        @Schema(description = "Start, clinic wall-clock time", example = "2026-11-20T10:30:00")
        LocalDateTime start,
        @Schema(description = "start + durationMinutes (may fall on the next day)", example = "2026-11-20T11:00:00")
        LocalDateTime end,
        @Schema(example = "30")
        int durationMinutes) {
}
