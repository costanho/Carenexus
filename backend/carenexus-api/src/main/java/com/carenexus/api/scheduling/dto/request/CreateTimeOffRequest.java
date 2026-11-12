package com.carenexus.api.scheduling.dto.request;

import com.carenexus.api.scheduling.dto.json.StrictJson;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Body of {@code POST /api/doctors/availability/time-off}. Times are clinic wall-clock without an offset; seconds are
 * dropped. Whole days are sent midnight to midnight ({@code 2026-12-24T00:00:00} to {@code 2026-12-27T00:00:00} for
 * 24 to 26 December inclusive). The range rules (end after start, end in the future, at most 366 days) are checked
 * by DoctorAvailabilityService.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A time-off range of the caller")
public class CreateTimeOffRequest {

    public static final int MAX_REASON_LENGTH = 200;

    @Schema(description = "Inclusive start, yyyy-MM-dd'T'HH:mm[:ss], clinic wall-clock. May lie in the past while "
            + "endsAt is in the future", example = "2026-12-24T00:00:00", type = "string",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "is required")
    @JsonDeserialize(using = StrictJson.ClinicDateTime.class)
    private LocalDateTime startsAt;

    @Schema(description = "Exclusive end, same format; after startsAt, in the future, at most 366 days after startsAt",
            example = "2026-12-27T00:00:00", type = "string", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "is required")
    @JsonDeserialize(using = StrictJson.ClinicDateTime.class)
    private LocalDateTime endsAt;

    @Schema(description = "Private note, never shown to patients or caregivers; trimmed, blank means none",
            example = "Annual leave", maxLength = MAX_REASON_LENGTH, nullable = true)
    @Size(max = MAX_REASON_LENGTH, message = "must be at most 200 characters")
    @JsonDeserialize(using = StrictJson.Text.class)
    private String reason;
}
