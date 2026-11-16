package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Health-status counts over the calling doctor's WHOLE related patient set; search filters and paging never change
 * it. {@code stable + monitor + critical == total}.
 */
@Schema(description = "Counts per health status over all of the doctor's patients (filters do not apply)")
public record DoctorPatientSummaryResponse(
        @Schema(description = "All related patients", example = "42") long total,
        @Schema(description = "Patients whose health status is STABLE", example = "30") long stable,
        @Schema(description = "Patients whose health status is MONITOR", example = "9") long monitor,
        @Schema(description = "Patients whose health status is CRITICAL", example = "3") long critical) {

    public static DoctorPatientSummaryResponse empty() {
        return new DoctorPatientSummaryResponse(0, 0, 0, 0);
    }
}
