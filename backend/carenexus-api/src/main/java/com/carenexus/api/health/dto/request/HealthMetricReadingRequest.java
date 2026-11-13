package com.carenexus.api.health.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One reading of a manual measurement session. Type, unit, plausible range, blood-pressure pairing and the
 * "not in the future" rule need the type catalogue or the clock and are checked by the service.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One reading of a measurement session")
public class HealthMetricReadingRequest {

    @Schema(description = "BP_SYSTOLIC, BP_DIASTOLIC, HEART_RATE, GLUCOSE, WEIGHT, HEIGHT, SPO2, TEMPERATURE, "
            + "RESPIRATORY_RATE or STEPS (case-insensitive; BMI is derived and cannot be entered)", example = "HEART_RATE")
    @NotBlank
    private String type;

    @Schema(description = "The measurement in the unit of the type; at most 7 integer digits and 3 decimals", example = "76")
    @NotNull
    @Digits(integer = 7, fraction = 3, message = "must be a number with at most 3 decimals")
    private BigDecimal value;

    @Schema(description = "Optional; when sent it must equal the unit of the type (mmHg, bpm, mmol/L, kg, cm, %, C, "
            + "breaths/min, steps)", example = "bpm")
    @Size(max = 30)
    private String unit;

    @Schema(description = "Clinic wall-clock time of the measurement (YYYY-MM-DDTHH:mm[:ss], no offset); omitted = now. "
            + "Not more than 5 minutes ahead of the server clock", example = "2026-11-10T08:30:00")
    private LocalDateTime recordedAt;

    @Schema(description = "Optional free text; blank is stored as null", example = "After the morning walk")
    @Size(max = 500)
    private String notes;
}
