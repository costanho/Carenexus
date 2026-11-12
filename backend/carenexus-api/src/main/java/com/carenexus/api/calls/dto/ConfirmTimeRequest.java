package com.carenexus.api.calls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

/** POST /confirm-time: the clinic wall-clock time the doctor saw (must equal the stored scheduledAt). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmTimeRequest {

    @Schema(description = "Appointment time as shown to the doctor (clinic wall-clock, yyyy-MM-ddTHH:mm[:ss]); must equal "
            + "the stored appointment time to the minute.", type = "string", example = "2026-11-20T10:30:00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "is required")
    private LocalDateTime scheduledAt;
}
