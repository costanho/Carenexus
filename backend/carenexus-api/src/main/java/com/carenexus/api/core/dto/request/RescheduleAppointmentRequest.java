package com.carenexus.api.core.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

/** Body of {@code PATCH /api/appointments/{id}/reschedule}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "New time (and optionally a new duration) of an active appointment")
public class RescheduleAppointmentRequest {

    @Schema(description = "New start in clinic wall-clock time, yyyy-MM-ddTHH:mm:00 without offset",
            example = "2026-11-21T09:00:00", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "is required")
    private LocalDateTime scheduledAt;

    @Schema(description = "New length in minutes, 5 to 240 in steps of 5; keeps the current duration when omitted",
            example = "30", nullable = true)
    @Min(value = 5, message = "must be between 5 and 240")
    @Max(value = 240, message = "must be between 5 and 240")
    private Integer durationMinutes;
}
