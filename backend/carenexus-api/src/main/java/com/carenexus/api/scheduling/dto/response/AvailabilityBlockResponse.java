package com.carenexus.api.scheduling.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** One stored block of a weekly template. Times are clinic wall-clock {@code HH:mm}; the end is exclusive. */
@Schema(name = "AvailabilityBlock", description = "One working block of one weekday")
public record AvailabilityBlockResponse(
        @Schema(description = "ISO weekday: 1 = Monday ... 7 = Sunday", example = "1")
        int dayOfWeek,
        @Schema(description = "First bookable minute, HH:mm", example = "08:00")
        String startTime,
        @Schema(description = "Exclusive end, HH:mm: an appointment must end by it", example = "13:00")
        String endTime,
        @Schema(description = "false = kept but produces no slots and allows no patient bookings", example = "true")
        @JsonProperty("isActive")
        boolean isActive) {
}
