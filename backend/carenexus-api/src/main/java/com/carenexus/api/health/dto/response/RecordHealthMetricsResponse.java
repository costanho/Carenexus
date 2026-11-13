package com.carenexus.api.health.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Answer of {@code POST /api/health-metrics}: the stored readings of the session. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The readings stored by one request")
public class RecordHealthMetricsResponse {

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "UUID given to every reading of this request", example = "5b0c7c0e-6a1f-4f5e-9d0a-2f1f4f3f8a11")
    private String readingGroupId;

    @Schema(description = "The stored readings, in request order (never a BMI entry)")
    private List<HealthMetricResponse> metrics;
}
