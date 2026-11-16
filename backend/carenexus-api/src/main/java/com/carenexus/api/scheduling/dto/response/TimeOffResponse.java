package com.carenexus.api.scheduling.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** One time-off range of the calling doctor. Date-times are clinic wall-clock without an offset. */
@Schema(description = "A time-off range of a doctor")
public record TimeOffResponse(
        @Schema(description = "Row id, used by DELETE", example = "7")
        Integer timeOffId,
        @Schema(description = "The owning doctor (always the caller)", example = "DOC-00002")
        String doctorSystemId,
        @Schema(description = "Inclusive start, yyyy-MM-dd'T'HH:mm:ss", example = "2026-12-24T00:00:00", type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startsAt,
        @Schema(description = "Exclusive end, yyyy-MM-dd'T'HH:mm:ss", example = "2026-12-27T00:00:00", type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime endsAt,
        @Schema(description = "The doctor's private note; never shown to patients or caregivers", example = "Annual leave",
                nullable = true)
        String reason,
        @Schema(description = "How many of the doctor's SCHEDULED or RESCHEDULED appointments overlap the range now. "
                + "Time off does not cancel or move them", example = "2")
        int conflictingAppointments,
        @Schema(description = "When the range was added (clinic wall-clock, may carry fractional seconds)",
                example = "2026-11-10T09:15:27.105331", type = "string")
        LocalDateTime createdAt) {
}
