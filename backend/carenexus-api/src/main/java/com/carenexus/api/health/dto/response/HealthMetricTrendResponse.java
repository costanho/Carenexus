package com.carenexus.api.health.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Answer of {@code GET /api/health-metrics/patient/{id}/trend}: one point per clinic day with readings. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Daily aggregation of one metric type over the last N clinic days")
public class HealthMetricTrendResponse {

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(example = "BP_SYSTOLIC")
    private String type;

    @Schema(example = "mmHg")
    private String unit;

    @Schema(description = "Window length in days (1 to 366)", example = "7")
    private Integer days;

    @Schema(description = "First clinic day of the window", example = "2026-11-04")
    private LocalDate from;

    @Schema(description = "Last clinic day of the window (today)", example = "2026-11-10")
    private LocalDate to;

    @Schema(description = "Ascending by date; days without readings are omitted")
    private List<Point> points;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "One clinic day with at least one reading")
    public static class Point {

        @Schema(example = "2026-11-08")
        private LocalDate date;

        @Schema(description = "Mean of the day's readings, two decimals", example = "136.5")
        private BigDecimal avg;

        @Schema(example = "134")
        private BigDecimal min;

        @Schema(example = "139")
        private BigDecimal max;

        @Schema(description = "Number of readings that day", example = "2")
        private Integer count;

        @Schema(description = "True when at least one reading of the day is abnormal")
        private Boolean abnormal;
    }
}
