package com.carenexus.api.health.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body of {@code POST /api/health-metrics}: one measurement session of 1 to 10 readings. Unknown properties (for
 * example {@code source} or {@code deviceId}) are ignored: manual entries are always stored as MANUAL.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A measurement session to record")
public class RecordHealthMetricsRequest {

    /** System id pattern of ValidationPatterns.SYSTEM_ID, limited to the 20 characters of the column. */
    public static final String PATIENT_ID_PATTERN = "^(?=.{1,20}$)(PAT|DOC|CGV|ADM)-\\d{5,}$";

    @Schema(description = "The patient the readings belong to. Optional for a patient (defaults to the caller, and "
            + "if sent must be the caller); required for doctors and caregivers", example = "PAT-00001")
    @Pattern(regexp = PATIENT_ID_PATTERN, message = "must be a valid system id")
    private String patientSystemId;

    @Schema(description = "1 to 10 readings, at most one per type; blood pressure needs both BP_SYSTOLIC and BP_DIASTOLIC")
    @NotNull(message = "must contain 1 to 10 readings")
    @Size(min = 1, max = 10, message = "must contain 1 to 10 readings")
    private List<@NotNull @Valid HealthMetricReadingRequest> readings;
}
