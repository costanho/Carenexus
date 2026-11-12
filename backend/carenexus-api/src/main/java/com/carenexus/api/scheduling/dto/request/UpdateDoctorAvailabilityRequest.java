package com.carenexus.api.scheduling.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.scheduling.dto.json.StrictJson;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body of {@code PUT /api/doctors/availability}: the caller's whole weekly template, which replaces every stored
 * block. An empty {@code blocks} array removes the template (patients book against clinic hours again).
 * Rules that need more than one field (5-minute boundaries, end after start, clinic hours, overlaps) are checked by
 * DoctorAvailabilityService in the order the API contract lists them.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The caller's whole weekly working-hours template")
public class UpdateDoctorAvailabilityRequest {

    public static final int MAX_BLOCKS = 42;

    @Schema(description = "Slot length in minutes for every block: 5 to 240 in steps of 5. Validated but unused "
            + "when blocks is empty", example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "is required")
    @SlotLength
    @JsonDeserialize(using = StrictJson.WholeNumber.class)
    private Integer slotMinutes;

    @ArraySchema(arraySchema = @Schema(description = "Working blocks, at most 42 and at most 6 per weekday; may be "
            + "empty", requiredMode = Schema.RequiredMode.REQUIRED), maxItems = MAX_BLOCKS)
    @NotNull(message = "is required")
    @Size(max = MAX_BLOCKS, message = "must contain at most 42 blocks")
    private List<@NotNull(message = "is required") @Valid Block> blocks;

    /** One working block of one weekday. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "AvailabilityBlockRequest", description = "One working block of one weekday")
    public static class Block {

        @Schema(description = "ISO weekday: 1 = Monday ... 7 = Sunday", example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7")
        @NotNull(message = "is required")
        @Min(value = 1, message = "must be between 1 (Monday) and 7 (Sunday)")
        @Max(value = 7, message = "must be between 1 (Monday) and 7 (Sunday)")
        @JsonDeserialize(using = StrictJson.WholeNumber.class)
        private Integer dayOfWeek;

        @Schema(description = "First bookable minute, HH:mm (24-hour, minute a multiple of 5)", example = "08:00",
                requiredMode = Schema.RequiredMode.REQUIRED, pattern = ValidationPatterns.TIME_HH_MM)
        @NotBlank(message = "is required")
        @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "must be a time in HH:mm format")
        @JsonDeserialize(using = StrictJson.Text.class)
        private String startTime;

        @Schema(description = "Exclusive end, HH:mm; an appointment must end by it. After startTime, at least one "
                + "slot long, same day (latest 23:55)", example = "13:00",
                requiredMode = Schema.RequiredMode.REQUIRED, pattern = ValidationPatterns.TIME_HH_MM)
        @NotBlank(message = "is required")
        @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "must be a time in HH:mm format")
        @JsonDeserialize(using = StrictJson.Text.class)
        private String endTime;

        @Schema(description = "false keeps the block without offering it to patients (default true)", example = "true")
        @JsonDeserialize(using = StrictJson.Flag.class)
        private Boolean isActive;
    }
}
