package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Appointment counts of the calling doctor ({@code GET /api/doctors/appointments/stats}). Every value comes from
 * COUNT queries; no appointment rows are loaded.
 *
 * @param totalAppointments all appointments of the doctor, any status
 * @param scheduledCount    SCHEDULED or RESCHEDULED appointments that start after clinic now (the upcoming count)
 * @param completedCount    status COMPLETED
 * @param cancelledCount    status CANCELLED
 * @param noShowCount       status NO_SHOW
 * @param rescheduledCount  status RESCHEDULED, past or future
 * @param completionRate    completed * 100 / (completed + cancelled + no-show); 0 when that sum is 0
 * @param noShowRate        no-show * 100 / total; 0 when there are no appointments
 * @param needsOutcomeCount active appointments whose end passed more than the configured minutes ago
 */
@Schema(description = "Appointment counts of the calling doctor")
public record AppointmentStatsResponse(
        @Schema(description = "All appointments of the doctor, any status", example = "143")
        long totalAppointments,
        @Schema(description = "SCHEDULED or RESCHEDULED appointments that start after clinic now", example = "27")
        long scheduledCount,
        @Schema(description = "Status COMPLETED", example = "96")
        long completedCount,
        @Schema(description = "Status CANCELLED", example = "11")
        long cancelledCount,
        @Schema(description = "Status NO_SHOW", example = "6")
        long noShowCount,
        @Schema(description = "Status RESCHEDULED, past or future", example = "9")
        long rescheduledCount,
        @Schema(description = "completed * 100 / (completed + cancelled + no-show), not rounded; 0 when the divisor is 0",
                example = "84.95575221238938")
        double completionRate,
        @Schema(description = "no-show * 100 / total, not rounded; 0 when there are no appointments",
                example = "4.195804195804196")
        double noShowRate,
        @Schema(description = "Active appointments that ended more than appointments.needs-outcome-after-minutes ago",
                example = "3")
        long needsOutcomeCount) {

    /** All zeros (a caller without appointments, for example an administrator). */
    public static AppointmentStatsResponse empty() {
        return new AppointmentStatsResponse(0, 0, 0, 0, 0, 0, 0.0, 0.0, 0);
    }
}
