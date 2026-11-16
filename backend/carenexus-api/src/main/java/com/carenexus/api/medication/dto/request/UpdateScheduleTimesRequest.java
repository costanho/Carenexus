package com.carenexus.api.medication.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * New daily dose times of a prescription. Duplicates are removed and the times sorted by the server; an empty list
 * removes the schedule.
 */
@Schema(name = "UpdateScheduleTimesRequest", description = "Daily dose times of a prescription")
public record UpdateScheduleTimesRequest(
        @NotNull(message = "is required")
        @Size(max = 8, message = "must contain at most 8 items")
        @ArraySchema(arraySchema = @Schema(description = "0 to 8 clinic wall-clock times, 24-hour HH:mm; [] removes "
                + "the schedule", example = "[\"07:30\", \"13:00\", \"21:00\"]", requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(type = "string", pattern = ValidationPatterns.TIME_HH_MM), maxItems = 8)
        @JsonDeserialize(contentUsing = StrictStringDeserializer.class)
        List<@NotNull(message = "must be a time in HH:mm format")
             @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "must be a time in HH:mm format") String> times) {
}
