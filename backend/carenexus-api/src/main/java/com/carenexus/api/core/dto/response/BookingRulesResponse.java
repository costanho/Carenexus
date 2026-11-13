package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Clinic booking rules the server enforces ({@code GET /api/appointments/booking-rules}).
 */
@Schema(description = "Clinic booking rules the server enforces")
public record BookingRulesResponse(
        @Schema(description = "IANA id of the clinic zone all wall-clock values use", example = "Africa/Harare")
        String timeZone,
        @Schema(description = "First bookable hour for patients and caregivers (inclusive)", example = "8")
        int openHour,
        @Schema(description = "Patient-side appointments must end by this hour", example = "18")
        int closeHour,
        @Schema(description = "Patients and caregivers may book Saturday and Sunday", example = "true")
        boolean allowWeekends,
        @Schema(description = "Start minute and duration are multiples of it", example = "5")
        int slotGranularityMinutes,
        @Schema(example = "5")
        int minDurationMinutes,
        @Schema(example = "240")
        int maxDurationMinutes,
        @Schema(description = "Booking horizon in days", example = "365")
        int maxAdvanceDays,
        @Schema(description = "Video calls can be booked (a call provider is configured). When false, VIDEO bookings "
                + "and changes to VIDEO are refused with 409 VIDEO_UNAVAILABLE", example = "true")
        boolean videoCallsAvailable) {
}
