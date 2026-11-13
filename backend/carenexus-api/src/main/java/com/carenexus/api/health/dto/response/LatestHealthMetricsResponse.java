package com.carenexus.api.health.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Answer of {@code GET /api/health-metrics/patient/{id}/latest}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The latest reading of each type plus the derived BMI")
public class LatestHealthMetricsResponse {

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "At most one reading per type, in the canonical type order; types never recorded are absent")
    private List<HealthMetricResponse> metrics;

    @Schema(description = "BMI derived from the latest WEIGHT and HEIGHT; null unless both exist", nullable = true)
    private Bmi bmi;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Body-mass index derived at read time (never stored)")
    public static class Bmi {

        @Schema(description = "weightKg / (heightCm / 100)^2, one decimal", example = "24.2")
        private BigDecimal value;

        @Schema(example = "kg/m2")
        private String unit;

        @Schema(description = "NORMAL, ABNORMAL or CRITICAL against the configured BMI range", example = "NORMAL")
        private String severity;

        @Schema(description = "True when severity is not NORMAL")
        private Boolean isAbnormal;

        @Schema(description = "The WEIGHT reading used", example = "398")
        private Integer weightMetricId;

        @Schema(description = "The HEIGHT reading used", example = "371")
        private Integer heightMetricId;

        @Schema(description = "The later recordedAt of the two readings (clinic wall-clock)", example = "2026-11-08T07:05:00")
        private LocalDateTime recordedAt;
    }
}
