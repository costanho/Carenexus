package com.carenexus.api.health.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One stored health reading. Null fields are serialised as null. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A stored health reading")
public class HealthMetricResponse {

    @Schema(description = "Row id", example = "412")
    private Integer metricId;

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "Metric type", example = "BP_SYSTOLIC")
    private String type;

    @Schema(description = "The measurement; do not rely on the number of decimals", example = "148")
    private BigDecimal value;

    @Schema(description = "The one unit of the type", example = "mmHg")
    private String unit;

    @Schema(description = "MANUAL, WEARABLE, DEVICE or INTEGRATION", example = "MANUAL")
    private String source;

    @Schema(description = "Device identifier; always null for MANUAL", nullable = true)
    private String deviceId;

    @Schema(description = "UUID shared by the readings stored by one request (pairs systolic with diastolic); "
            + "null on legacy rows", nullable = true, example = "5b0c7c0e-6a1f-4f5e-9d0a-2f1f4f3f8a11")
    private String readingGroupId;

    @Schema(description = "True when severity is not NORMAL")
    private Boolean isAbnormal;

    @Schema(description = "NORMAL, ABNORMAL or CRITICAL, computed by the server when the reading was stored", example = "ABNORMAL")
    private String severity;

    @Schema(nullable = true)
    private String notes;

    @Schema(description = "Clinic wall-clock time (no offset)", example = "2026-11-10T08:30:00")
    private LocalDateTime recordedAt;

    @Schema(description = "Who entered the reading; null on legacy rows and device readings", nullable = true, example = "PAT-00001")
    private String recordedBySystemId;

    @Schema(description = "First and last name of the recorder; null when unknown", nullable = true, example = "Mary Davis")
    private String recordedByName;

    @Schema(description = "Whether the caller may delete it: a MANUAL reading and the caller is the patient or the recorder")
    private Boolean canDelete;
}
